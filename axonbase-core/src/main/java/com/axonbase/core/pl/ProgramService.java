package com.axonbase.core.pl;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.QueryContext;
import com.axonbase.core.security.Authz;
import com.axonbase.parser.Render;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.pl.PlCompiler;
import com.axonbase.pl.PlDebugHook;
import com.axonbase.pl.PlError;
import com.axonbase.pl.PlLimitError;
import com.axonbase.pl.PlLimits;
import com.axonbase.pl.PlNamespace;
import com.axonbase.pl.PlProgram;
import com.axonbase.pl.PlRuntime;
import com.axonbase.pl.PlSyntaxError;
import com.axonbase.pl.PlTraceFrame;
import com.axonbase.value.AxonValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Funções, procedures e packages AxonPL: criação, remoção, descrição e execução dentro da sessão de quem chamou.
 * Uma instância por {@link Datastore}.
 */
public final class ProgramService {

    /** Código de erro de um programa que falhou (traceback no texto). */
    public static final int PL_ERROR = -32040;
    /** Código de erro de um limite estourado (passos, prazo, memória, profundidade). */
    public static final int PL_LIMIT = -32041;
    /** Aninhamento máximo SQL para AxonPL para SQL. */
    public static final int MAX_NESTING = 16;
    private static final int LOG_RING = 1000;

    private final Datastore ds;
    private final PlRuntime runtime = new PlRuntime();
    private final PlStats stats = new PlStats();
    private final DebugManager debug;
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);
    private final ThreadLocal<List<String>> lastLog = new ThreadLocal<>();
    private final AtomicLong savepoints = new AtomicLong();
    private final ArrayDeque<String> ring = new ArrayDeque<>();
    private volatile long timeoutMs = 30_000;
    private volatile BiConsumer<String, String> logSink;

    public ProgramService(Datastore ds) {
        this.ds = ds;
        this.debug = new DebugManager(ds, this);
    }

    public PlStats stats() {
        return stats;
    }

    /** Gerenciador das sessões de depuração deste banco de dados. */
    public DebugManager debug() {
        return debug;
    }

    /** Prazo de cada chamada, em milissegundos (0 desliga). O servidor usa AXON_QUERY_TIMEOUT. */
    public void timeoutMs(long ms) {
        this.timeoutMs = ms;
    }

    /** Destino das linhas de {@code log.*} e {@code print} (o servidor liga ao AxonLog). Recebe nível e texto. */
    public void logSink(BiConsumer<String, String> sink) {
        this.logSink = sink;
    }

    /** Linhas de log da última chamada feita nesta thread (nível: texto). */
    public List<String> lastCallLog() {
        List<String> l = lastLog.get();
        return l == null ? List.of() : List.copyOf(l);
    }

    /** Últimas linhas de log de todos os programas (as mais novas por último). */
    public List<String> recentLog() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    void log(SessionHost.CallState state, String level, String message) {
        String line = level + ": " + message;
        state.log.add(line);
        synchronized (ring) {
            ring.addLast("[" + state.def.name() + "] " + line);
            while (ring.size() > LOG_RING) {
                ring.removeFirst();
            }
        }
        BiConsumer<String, String> sink = logSink;
        if (sink != null) {
            sink.accept(level, "[" + state.def.name() + "] " + message);
        }
    }

    void checkCancelled() {
        try {
            QueryContext.checkCancelled();
        } catch (AxonError e) {
            throw new PlLimitError(PlLimitError.Tipo.CANCELADO, "execucao cancelada");
        }
    }

    /** Catálogo de programas de um banco, ou {@code null} se o banco não existe. */
    public ProgramCatalog catalog(String ns, String db) {
        Database d = ds.findDatabase(ns, db);
        return d == null ? null : d.programs();
    }

    // ------------------------------------------------------------------
    // sentenças
    // ------------------------------------------------------------------

    /** Executa CREATE/DROP/CALL. {@code eval} avalia expressões AxonQL na sessão (padrões e argumentos). */
    public AxonValue run(Statement s, Session session, Function<Expr, AxonValue> eval) {
        return switch (s) {
            case Statement.CreateFunction f -> create(session, eval, ProgramDef.Kind.FUNCTION, f.orReplace(),
                f.name(), f.params(), f.returns(), f.definer(), f.body());
            case Statement.CreateProcedure p -> create(session, eval, ProgramDef.Kind.PROCEDURE, p.orReplace(),
                p.name(), p.params(), p.returns(), p.definer(), p.body());
            case Statement.CreatePackage p -> create(session, eval, ProgramDef.Kind.PACKAGE, p.orReplace(),
                p.name(), List.of(), null, p.definer(), p.body());
            case Statement.DropProgram d -> drop(session, d);
            case Statement.CallProgram c -> callStatement(session, c, eval);
            default -> throw new AxonError(-32000, "sentenca AxonPL desconhecida");
        };
    }

    private AxonValue create(Session session, Function<Expr, AxonValue> eval, ProgramDef.Kind kind,
                             boolean orReplace, String name, List<Statement.ProgramParam> params, String returns,
                             boolean definer, String body) {
        Database db = ds.requireDatabase(session);
        boolean replaying = ds.isReplaying();
        String what = kindName(kind);
        if (!replaying) {
            checkName(kind, name);
            if (definer && Authz.authenticated(session.auth()) && !Authz.atLeast(session.auth(), Authz.Role.ADMIN)) {
                throw new AxonError(-32002, "SECURITY DEFINER exige papel admin");
            }
        }
        ProgramDef existing = db.programs().get(name);
        if (!replaying && !administers(session)) {
            // repetir a definição em vigor não muda nada: nem a versão, nem as datas, nem o dono
            if (orReplace && repeats(existing, renderable(kind, name, List.of(), params, returns, definer, body))) {
                return AxonValue.nul();
            }
            throw adminRequired();
        }
        if (existing != null && !replaying) {
            if (!orReplace) {
                throw new AxonError(-32000, "ja existe " + what(existing.kind()) + " '" + name
                    + "'; use CREATE OR REPLACE para substituir");
            }
            if (existing.kind() != kind) {
                throw new AxonError(-32000, "'" + name + "' ja e " + what(existing.kind())
                    + " e nao pode ser substituido por " + what);
            }
        }
        List<String> refusedDefaults = new ArrayList<>();
        List<ProgramDef.Param> ps = bindParams(eval, what(kind), name, params, replaying, refusedDefaults);
        if (returns != null && !ParamTypes.known(returns) && !replaying) {
            throw new AxonError(-32000, "tipo de retorno desconhecido em '" + name + "': " + returns);
        }

        ProgramMeta meta = replaying ? ProgramMeta.replaying() : null;
        long now = System.currentTimeMillis();
        long version = meta != null ? meta.version() : existing == null ? 1 : existing.version() + 1;
        long created = meta != null ? meta.created() : existing == null ? now : existing.created();
        long updated = meta != null ? meta.updated() : now;
        AxonValue owner = meta != null ? meta.owner() : ownerClaims(session);
        ProgramMeta effective = new ProgramMeta(version, created, updated, owner);

        String programName = (kind == ProgramDef.Kind.FUNCTION ? "funcao " : kind == ProgramDef.Kind.PROCEDURE
            ? "procedure " : "package ") + name;
        String source = kind == ProgramDef.Kind.PACKAGE ? body : wrapSource(name, ps, body);
        PlProgram program = null;
        String error = null;
        try {
            program = PlCompiler.compile(source, programName);
        } catch (PlSyntaxError e) {
            error = syntaxMessage(kind, name, e);
            if (!replaying) {
                throw new AxonError(PL_ERROR, error);
            }
            System.err.println("{\"event\":\"axonpl_replay_broken\",\"name\":\"" + name + "\",\"error\":\""
                + error.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}");
        }
        if (program != null && !replaying) {
            checkImports(db, name, program);
        }
        String text = Render.stmt(renderable(kind, name, ps, params, returns, definer, body));
        String definition = effective.header() + text;
        if (error == null && !refusedDefaults.isEmpty()) {
            error = refusedDefaults.get(0);
        }
        ProgramDef def = new ProgramDef(kind, name, ps, returns, definer, owner, body, definition, version, created,
            updated, error, program);
        ds.applyControl(session, ControlCommand.of(controlKind(kind), db.ns(), db.db(), name, definition));
        db.programs().put(def);
        return AxonValue.nul();
    }

    /** Recusa import de package inexistente, de objeto que não é package e ciclos de import. */
    private void checkImports(Database db, String name, PlProgram program) {
        for (com.axonbase.pl.PlImportInfo imp : program.imports()) {
            String mod = imp.modulo();
            if (com.axonbase.pl.rt.Modules.embutido(mod) || SessionHost.isNative(mod)) {
                continue;
            }
            ProgramDef target = db.programs().get(mod);
            if (mod.equals(name)) {
                throw new AxonError(-32000, "import circular: '" + name + "' importa a si mesmo");
            }
            if (target == null) {
                throw new AxonError(-32000, "o package '" + mod + "' importado por '" + name + "' (linha "
                    + imp.linha() + ") nao existe; crie-o antes com CREATE PACKAGE");
            }
            if (target.kind() != ProgramDef.Kind.PACKAGE) {
                throw new AxonError(-32000, "'" + mod + "' importado por '" + name + "' (linha " + imp.linha()
                    + ") e " + what(target.kind()) + ", nao um package");
            }
            List<String> path = new ArrayList<>();
            path.add(name);
            if (reaches(db.programs(), mod, name, new HashSet<>(), path)) {
                throw new AxonError(-32000, "import circular detectado: " + String.join(" -> ", path));
            }
        }
    }

    /** Busca em profundidade: {@code from} alcança {@code goal} pelos imports já cadastrados? */
    private static boolean reaches(ProgramCatalog catalog, String from, String goal, Set<String> seen,
                                   List<String> path) {
        path.add(from);
        if (from.equals(goal)) {
            return true;
        }
        if (seen.add(from)) {
            ProgramDef d = catalog.get(from);
            if (d != null && d.program() != null) {
                for (com.axonbase.pl.PlImportInfo i : d.program().imports()) {
                    if (reaches(catalog, i.modulo(), goal, seen, path)) {
                        return true;
                    }
                }
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    private static Statement renderable(ProgramDef.Kind kind, String name, List<ProgramDef.Param> ps,
                                        List<Statement.ProgramParam> params, String returns, boolean definer,
                                        String body) {
        return switch (kind) {
            case FUNCTION -> new Statement.CreateFunction(true, name, params, returns, definer, body);
            case PROCEDURE -> new Statement.CreateProcedure(true, name, params, returns, definer, body);
            case PACKAGE -> new Statement.CreatePackage(true, name, definer, body);
        };
    }

    private void checkName(ProgramDef.Kind kind, String name) {
        String what = kindName(kind);
        if (!ProgramNames.validIdentifier(name)) {
            throw new AxonError(-32000, "nome invalido para " + what + ": '" + name
                + "' (use letras, digitos e sublinhado, sem comecar por digito)");
        }
        if (ProgramNames.reservedName(name)) {
            throw new AxonError(-32000, "o nome '" + name + "' e reservado (namespace ou funcao embutida) e nao "
                + "pode ser usado por " + what);
        }
    }

    private List<ProgramDef.Param> bindParams(Function<Expr, AxonValue> eval, String what, String name,
                                              List<Statement.ProgramParam> params, boolean replaying,
                                              List<String> refused) {
        List<ProgramDef.Param> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean sawDefault = false;
        for (Statement.ProgramParam p : params) {
            if (!replaying) {
                if (!ProgramNames.validIdentifier(p.name()) || !seen.add(p.name())) {
                    throw new AxonError(-32000, "parametro invalido ou repetido em " + what + " '" + name + "': "
                        + p.name());
                }
                if (p.type() != null && !ParamTypes.known(p.type())) {
                    throw new AxonError(-32000, "tipo desconhecido no parametro '" + p.name() + "' de '" + name
                        + "': " + p.type() + " (use int, float, decimal, string, bool, datetime, array, object, "
                        + "record ou any)");
                }
                if (p.defaultExpr() == null && sawDefault) {
                    throw new AxonError(-32000, "parametro sem padrao depois de parametro com padrao em '" + name
                        + "': " + p.name());
                }
            }
            sawDefault |= p.defaultExpr() != null;
            AxonValue dv = null;
            String text = null;
            if (p.defaultExpr() != null) {
                text = Render.expr(p.defaultExpr());
                try {
                    dv = eval.apply(p.defaultExpr());
                } catch (RuntimeException e) {
                    String message = "o padrao do parametro '" + p.name() + "' de '" + name
                        + "' nao e uma expressao constante valida: " + e.getMessage();
                    // no reinício a definição abre assim mesmo, e a chamada responde com este erro
                    if (!replaying) {
                        throw new AxonError(-32000, message);
                    }
                    refused.add(message);
                    dv = AxonValue.nul();
                }
            }
            out.add(new ProgramDef.Param(p.name(), p.type(), p.defaultExpr() != null, dv, text));
        }
        return out;
    }

    private static String wrapSource(String name, List<ProgramDef.Param> ps, String body) {
        StringBuilder sb = new StringBuilder("def ").append(name).append('(');
        for (int i = 0; i < ps.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(ps.get(i).name());
        }
        sb.append("):\n");
        String b = body.isBlank() ? "pass" : body;
        for (String line : b.split("\n", -1)) {
            sb.append(line.isEmpty() ? "" : "    " + line).append('\n');
        }
        return sb.toString();
    }

    private static String syntaxMessage(ProgramDef.Kind kind, String name, PlSyntaxError e) {
        int line = kind == ProgramDef.Kind.PACKAGE ? e.linha() : Math.max(1, e.linha() - 1);
        return "Erro de sintaxe em " + what(kind) + " '" + name + "', linha " + line + ", coluna " + e.coluna()
            + ": " + e.mensagemSimples() + (e.trecho().isBlank() ? "" : "\n  " + e.trecho().strip());
    }

    private static AxonValue ownerClaims(Session session) {
        AxonValue auth = session.auth();
        if (!Authz.authenticated(auth)) {
            return null;
        }
        java.util.Map<String, AxonValue> keep = new java.util.LinkedHashMap<>();
        for (String k : new String[] {"id", "user", "scope", "ns", "db", "roles", "data_rules"}) {
            AxonValue v = auth.asObject().get(k);
            if (v != null) {
                keep.put(k, v);
            }
        }
        return AxonValue.object(keep);
    }

    /**
     * O corpo de um programa roda com a identidade de quem o chama, inclusive a do root, e uma função pode decidir uma
     * PERMISSIONS. Por isso criar, substituir e apagar é do administrador cujo escopo cobre o banco. Sessão sem auth é
     * o modo embutido, o replay ou o servidor sem autenticação.
     */
    private static boolean administers(Session session) {
        AxonValue auth = session.auth();
        return !Authz.authenticated(auth)
            || Authz.atLeast(auth, Authz.Role.ADMIN) && Authz.allows(auth, session.namespace(), session.database());
    }

    private static AxonError adminRequired() {
        return new AxonError(-32002, Messages.get("auth_role_required", "admin"));
    }

    /** O texto pedido é o da definição em vigor, sem contar a linha de metadados que a precede. */
    private static boolean repeats(ProgramDef current, Statement wanted) {
        if (current == null) {
            return false;
        }
        String definition = current.definition();
        return definition.substring(definition.indexOf('\n') + 1).equals(Render.stmt(wanted));
    }

    private AxonValue drop(Session session, Statement.DropProgram d) {
        if (!administers(session)) {
            throw adminRequired();
        }
        Database db = ds.requireDatabase(session);
        ProgramDef.Kind kind = ProgramDef.Kind.valueOf(d.kind());
        ProgramDef existing = db.programs().get(d.name());
        if (existing == null || existing.kind() != kind) {
            throw new AxonError(-32000, kindName(kind) + " '" + d.name() + "' nao existe");
        }
        if (kind == ProgramDef.Kind.PACKAGE) {
            List<String> users = new ArrayList<>();
            for (ProgramDef other : db.programs().all()) {
                if (other.program() != null && !other.name().equals(d.name())
                    && other.program().imports().stream().anyMatch(i -> i.modulo().equals(d.name()))) {
                    users.add(what(other.kind()) + " " + other.name());
                }
            }
            if (!users.isEmpty()) {
                throw new AxonError(-32000, "o package '" + d.name() + "' e importado por " + String.join(", ", users)
                    + " e nao pode ser removido; remova ou altere esses objetos antes");
            }
        }
        db.programs().remove(d.name());
        ds.removeControl(session, db.ns(), db.db(), controlKind(kind), d.name());
        return AxonValue.nul();
    }

    // ------------------------------------------------------------------
    // INFO
    // ------------------------------------------------------------------

    /** {@code INFO FOR FUNCTION|PROCEDURE|PACKAGE nome}. */
    public AxonValue info(Session session, String kind, String name) {
        Database db = ds.requireDatabase(session);
        ProgramDef d = db.programs().get(name);
        if (d == null || !d.kind().name().equalsIgnoreCase(kind)) {
            throw new AxonError(-32000, kindName(ProgramDef.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)))
                + " '" + name + "' nao existe");
        }
        return ProgramInfo.full(d);
    }

    /** As chaves {@code functions}, {@code procedures} e {@code packages} do INFO FOR DATABASE. */
    public java.util.Map<String, AxonValue> summary(Database db) {
        java.util.Map<String, AxonValue> out = new java.util.LinkedHashMap<>();
        for (ProgramDef.Kind k : ProgramDef.Kind.values()) {
            java.util.Map<String, AxonValue> m = new java.util.LinkedHashMap<>();
            for (ProgramDef d : db.programs().of(k)) {
                m.put(d.name(), ProgramInfo.brief(d));
            }
            out.put(k == ProgramDef.Kind.FUNCTION ? "functions" : k == ProgramDef.Kind.PROCEDURE ? "procedures"
                : "packages", AxonValue.object(m));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // chamadas
    // ------------------------------------------------------------------

    /**
     * Chamada em expressão SQL ({@code nome(...)}, {@code fn::nome(...)}, {@code pacote::funcao(...)}). Devolve
     * {@code null} se o nome não é de um objeto do usuário; a chamada é somente leitura.
     */
    public AxonValue tryCall(Session session, String rawName, List<AxonValue> args) {
        ProgramCatalog catalog = catalogOf(session);
        if (catalog == null) {
            return null;
        }
        String name = rawName.startsWith("fn::") ? rawName.substring(4) : rawName;
        int sep = name.indexOf("::");
        ProgramDef def;
        String fn;
        if (sep > 0) {
            def = catalog.get(name.substring(0, sep));
            fn = name.substring(sep + 2);
            if (def == null || def.kind() != ProgramDef.Kind.PACKAGE) {
                return null;
            }
        } else {
            def = catalog.get(name);
            fn = name;
            if (def == null || def.kind() == ProgramDef.Kind.PACKAGE) {
                return null;
            }
            if (def.kind() == ProgramDef.Kind.PROCEDURE) {
                throw new AxonError(PL_ERROR, "'" + name + "' e uma procedure: chame com CALL " + name + "(...)");
            }
        }
        return invoke(session, catalog, def, fn, args, false);
    }

    private ProgramCatalog catalogOf(Session session) {
        if (session.namespace() == null || session.database() == null) {
            return null;
        }
        return catalog(session.namespace(), session.database());
    }

    private AxonValue callStatement(Session session, Statement.CallProgram c, Function<Expr, AxonValue> eval) {
        Database db = ds.requireDatabase(session);
        ProgramCatalog catalog = db.programs();
        String name = c.name().startsWith("fn::") ? c.name().substring(4) : c.name();
        int sep = name.indexOf("::");
        String owner = sep > 0 ? name.substring(0, sep) : name;
        String fn = sep > 0 ? name.substring(sep + 2) : name;
        ProgramDef def = catalog.get(owner);
        if (def == null) {
            throw new AxonError(PL_ERROR, "a funcao ou procedure '" + owner + "' nao existe");
        }
        if ((sep > 0) != (def.kind() == ProgramDef.Kind.PACKAGE)) {
            throw new AxonError(PL_ERROR, def.kind() == ProgramDef.Kind.PACKAGE
                ? "'" + owner + "' e um package: chame com CALL " + owner + "::funcao(...)"
                : "'" + owner + "' nao e um package");
        }
        List<AxonValue> args = new ArrayList<>();
        for (Expr a : c.args()) {
            args.add(eval.apply(a));
        }
        if (def.kind() == ProgramDef.Kind.FUNCTION) {
            return invoke(session, catalog, def, fn, args, false);
        }
        return atomically(session, () -> invoke(session, catalog, def, fn, args, true));
    }

    /** Roda o corpo numa transação implícita (ou num savepoint, se já há transação): tudo ou nada. */
    private AxonValue atomically(Session session, java.util.function.Supplier<AxonValue> body) {
        if (session.inTransaction()) {
            String sp = "__pl_" + savepoints.incrementAndGet();
            session.savepoint(sp);
            try {
                AxonValue r = body.get();
                session.release(sp);
                return r;
            } catch (RuntimeException | Error e) {
                try {
                    session.rollbackTo(sp);
                } catch (RuntimeException ignored) {
                    // a transação já morreu; o erro original é o que importa
                }
                throw e;
            }
        }
        ds.beginSession(session);
        try {
            AxonValue r = body.get();
            ds.commitSession(session);
            return r;
        } catch (RuntimeException | Error e) {
            if (session.inTransaction()) {
                ds.cancelSession(session);
            }
            throw e;
        }
    }

    private AxonValue invoke(Session session, ProgramCatalog catalog, ProgramDef def, String fn,
                             List<AxonValue> rawArgs, boolean writable) {
        return invoke(session, catalog, def, fn, rawArgs, writable, null);
    }

    /** Execução sob depuração (módulo recarregado do zero, gancho, rastro e limites do depurador). */
    AxonValue invokeDebug(Session session, ProgramCatalog catalog, ProgramDef def, String fn,
                          List<AxonValue> rawArgs, boolean writable, DebugRun dbg) {
        return invoke(session, catalog, def, fn, rawArgs, writable, dbg);
    }

    private AxonValue invoke(Session session, ProgramCatalog catalog, ProgramDef def, String fn,
                             List<AxonValue> rawArgs, boolean writable, DebugRun dbg) {
        if (def.broken()) {
            throw new AxonError(PL_ERROR, kindName(def.kind()) + " '" + def.name() + "' esta quebrado: "
                + def.error());
        }
        int[] level = depth.get();
        if (level[0] >= MAX_NESTING) {
            throw new AxonError(PL_LIMIT, "aninhamento de chamadas SQL e AxonPL excedeu " + MAX_NESTING
                + " niveis (recursao entre SQL e programas?)");
        }
        boolean isPackage = def.kind() == ProgramDef.Kind.PACKAGE;
        List<AxonValue> args = isPackage ? rawArgs : bindArgs(def, rawArgs);
        PlLimits limits = dbg != null ? dbg.limits() : PlLimits.padrao();
        long ms = timeoutMs;
        if (ms > 0) {
            limits.prazoNanos(ms * 1_000_000L);
        }
        QueryContext.Handle handle = QueryContext.current();
        Runnable cancel = limits::cancelar;
        if (handle != null) {
            handle.onCancel(cancel);
        }
        SessionHost.CallState state = new SessionHost.CallState(writable, def);
        if (dbg != null) {
            state.profile = dbg.profile();
            state.noDdl = dbg.noDdl();
        }
        SessionHost host = new SessionHost(this, ds, session, catalog, state);
        AxonValue savedAuth = session.auth();
        String savedNs = session.namespace();
        String savedDb = session.database();
        long started = System.nanoTime();
        level[0]++;
        boolean savedReadOnly = session.readOnlyCall();
        // a classificação do SQL em db.* olha só a primeira camada: subconsultas são barradas na execução
        session.readOnlyCall(savedReadOnly || !writable);
        try {
            if (def.definer()) {
                session.auth(def.owner());
            }
            checkCancelled();
            PlNamespace ns = dbg != null ? runtime.exec(def.program(), host, limits, dbg.hook())
                : namespaceOf(def, host, limits);
            if (isPackage && !publicFunction(ns, fn)) {
                throw new AxonError(PL_ERROR, "o package '" + def.name() + "' nao tem a funcao publica '" + fn + "'");
            }
            AxonValue r = callLocked(def, ns, fn, args, host, limits, dbg == null ? null : dbg.hook());
            return r == null ? AxonValue.nul() : r;
        } catch (PlError e) {
            stats.error(e instanceof PlLimitError);
            throw translate(def, e);
        } catch (RuntimeException e) {
            stats.error(false);
            throw e;
        } finally {
            level[0]--;
            session.readOnlyCall(savedReadOnly);
            session.auth(savedAuth);
            session.namespace(savedNs);
            session.database(savedDb);
            if (handle != null) {
                handle.removeOnCancel(cancel);
            }
            stats.call(System.nanoTime() - started, limits.passosExecutados());
            lastLog.set(state.log);
        }
    }

    /**
     * Semântica das globais de package: o estado global (variáveis do módulo) é compartilhado entre as chamadas.
     * Se o package tem estado mutável (alguma função usa {@code global} ou há list/dict/set no módulo), cada
     * chamada a ele toma a trava do package (reentrante) e as chamadas são serializadas: nenhuma atualização se
     * perde. Package só com constantes imutáveis e funções puras não toma trava e roda em paralelo.
     * A espera pela trava respeita o prazo da chamada.
     */
    private AxonValue callLocked(ProgramDef def, PlNamespace ns, String fn, List<AxonValue> args, SessionHost host,
                                 PlLimits limits, PlDebugHook hook) {
        if (def.kind() != ProgramDef.Kind.PACKAGE || !ns.temEstadoMutavel()) {
            return ns.call(fn, args, null, host, limits, hook);
        }
        java.util.concurrent.locks.ReentrantLock lock = def.stateLock();
        long ms = timeoutMs > 0 ? timeoutMs : 30_000;
        boolean got;
        try {
            got = lock.tryLock(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AxonError(PL_LIMIT, "interrompido esperando a trava do package '" + def.name() + "'");
        }
        if (!got) {
            throw new AxonError(PL_LIMIT, "tempo esgotado esperando a trava do estado global do package '"
                + def.name() + "' (outra chamada o mantem ocupado; possivel espera circular entre packages)");
        }
        try {
            return ns.call(fn, args, null, host, limits, hook);
        } finally {
            lock.unlock();
        }
    }

    private static boolean publicFunction(PlNamespace ns, String fn) {
        return !fn.startsWith("_") && ns.funcoes().contains(fn);
    }

    private PlNamespace namespaceOf(ProgramDef def, SessionHost host, PlLimits limits) {
        PlNamespace ns = def.loaded();
        if (ns != null) {
            return ns;
        }
        synchronized (def) {
            ns = def.loaded();
            if (ns == null) {
                ns = runtime.exec(def.program(), host, limits, null);
                def.loaded(ns);
            }
        }
        return ns;
    }

    /** Aplica padrões e valida/converte os tipos dos argumentos posicionais. */
    private static List<AxonValue> bindArgs(ProgramDef def, List<AxonValue> given) {
        List<ProgramDef.Param> ps = def.params();
        if (given.size() > ps.size()) {
            throw new AxonError(PL_ERROR, "a " + what(def.kind()) + " '" + def.name() + "' espera no maximo "
                + ps.size() + " argumento(s), mas recebeu " + given.size());
        }
        List<AxonValue> out = new ArrayList<>(ps.size());
        for (int i = 0; i < ps.size(); i++) {
            ProgramDef.Param p = ps.get(i);
            AxonValue v;
            if (i < given.size()) {
                v = given.get(i);
            } else if (p.hasDefault()) {
                v = p.defaultValue();
            } else {
                throw new AxonError(PL_ERROR, "falta o argumento '" + p.name() + "' na chamada de '" + def.name()
                    + "'");
            }
            out.add(ParamTypes.coerce(def.name(), p.name(), p.type(), v));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // erros
    // ------------------------------------------------------------------

    private AxonError translate(ProgramDef def, PlError e) {
        if (e instanceof PlLimitError le) {
            if (le.limite() == PlLimitError.Tipo.CANCELADO) {
                return new AxonError(QueryContext.CANCELLED, Messages.get("query_cancelled"));
            }
            return new AxonError(PL_LIMIT, text(def, e, "Limite excedido em "));
        }
        return new AxonError(PL_ERROR, text(def, e, "Erro em "));
    }

    private static String text(ProgramDef def, PlError e, String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append(what(def.kind())).append(" '").append(def.name())
            .append("': ").append(e.tipo()).append(": ").append(e.mensagem());
        List<PlTraceFrame> frames = e.traceback();
        if (!frames.isEmpty()) {
            sb.append("\nTraceback (chamada mais recente por ultimo):");
            for (PlTraceFrame q : frames) {
                boolean wrapped = q.programa().startsWith("funcao ") || q.programa().startsWith("procedure ");
                int line = wrapped ? q.linha() - 1 : q.linha();
                if (line < 1) {
                    continue;
                }
                sb.append("\n  Arquivo \"").append(q.programa()).append("\", linha ").append(line)
                    .append(", em ").append(q.funcao());
            }
        }
        return sb.toString();
    }

    private static String what(ProgramDef.Kind kind) {
        return switch (kind) {
            case FUNCTION -> "funcao";
            case PROCEDURE -> "procedure";
            case PACKAGE -> "package";
        };
    }

    private static String kindName(ProgramDef.Kind kind) {
        return switch (kind) {
            case FUNCTION -> "a funcao";
            case PROCEDURE -> "a procedure";
            case PACKAGE -> "o package";
        };
    }

    private static ControlCommand.Kind controlKind(ProgramDef.Kind kind) {
        return switch (kind) {
            case FUNCTION -> ControlCommand.Kind.FUNCTION;
            case PROCEDURE -> ControlCommand.Kind.PROCEDURE;
            case PACKAGE -> ControlCommand.Kind.PACKAGE;
        };
    }
}
