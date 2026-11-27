package com.axonbase.core.pl;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.Executor;
import com.axonbase.core.engine.QueryContext;
import com.axonbase.core.security.Authz;
import com.axonbase.pl.PlCallable;
import com.axonbase.pl.PlHost;
import com.axonbase.pl.PlLimitError;
import com.axonbase.pl.PlProgram;
import com.axonbase.pl.rt.Errors;
import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ponte entre o programa e o banco, na mesma {@link Session} de quem chamou: mesmo usuário, mesmo escopo, mesmas
 * Data Rules e permissões. Expõe os módulos nativos {@code db} e {@code log}.
 */
final class SessionHost implements PlHost {

    /** Estado de uma chamada: se pode escrever, se executou DDL e o que registrou no log. */
    static final class CallState {
        final boolean writable;
        final ProgramDef def;
        boolean ddlRan;
        final List<String> log = new ArrayList<>();
        /** Depuração: registra as consultas db.* disparadas (null fora da depuração). */
        QueryProfile profile;
        /** Depuração sem commit: DDL não é permitido (não há como desfazê-lo com garantia). */
        boolean noDdl;

        CallState(boolean writable, ProgramDef def) {
            this.writable = writable;
            this.def = def;
        }
    }

    private static final Set<String> NATIVE = Set.of("db", "log");

    static boolean isNative(String name) {
        return NATIVE.contains(name);
    }

    private final ProgramService service;
    private final Datastore ds;
    private final Session session;
    private final ProgramCatalog catalog;
    private final CallState state;

    SessionHost(ProgramService service, Datastore ds, Session session, ProgramCatalog catalog, CallState state) {
        this.service = service;
        this.ds = ds;
        this.session = session;
        this.catalog = catalog;
        this.state = state;
    }

    @Override
    public PlProgram resolverImport(String nome) {
        ProgramDef d = catalog == null ? null : catalog.get(nome);
        return d != null && d.kind() == ProgramDef.Kind.PACKAGE ? d.program() : null;
    }

    @Override
    public Set<String> modulosNativos() {
        return NATIVE;
    }

    @Override
    public void log(String nivel, String mensagem, List<AxonValue> args) {
        service.log(state, nivel, mensagem);
    }

    @Override
    public Object attr(String modulo, String nome) {
        if (modulo.equals("log")) {
            return switch (nome) {
                case "debug" -> logFn("DEBUG");
                case "info" -> logFn("INFO");
                case "warn", "warning" -> logFn("WARN");
                case "error" -> logFn("ERROR");
                default -> null;
            };
        }
        if (!modulo.equals("db")) {
            return null;
        }
        return switch (nome) {
            case "query" -> (PlCallable) (a, kw) -> query(a, kw);
            case "one" -> (PlCallable) (a, kw) -> one(a, kw);
            case "exec" -> (PlCallable) (a, kw) -> exec(a, kw);
            case "count" -> (PlCallable) (a, kw) -> count(a, kw);
            case "rid" -> (PlCallable) (a, kw) -> rid(a);
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // log
    // ------------------------------------------------------------------

    private PlCallable logFn(String level) {
        return (args, kw) -> {
            if (args.isEmpty()) {
                throw Errors.type("log." + level.toLowerCase() + "() espera a mensagem");
            }
            service.log(state, level, format(text(args.get(0)), args.subList(1, args.size())));
            return AxonValue.none();
        };
    }

    /** Substitui {@code {}} ou {@code %s} pelos argumentos, na ordem; sobras vão no fim. */
    static String format(String msg, List<AxonValue> args) {
        if (args.isEmpty()) {
            return msg;
        }
        StringBuilder sb = new StringBuilder();
        int next = 0;
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            boolean ph = (c == '{' && i + 1 < msg.length() && msg.charAt(i + 1) == '}')
                || (c == '%' && i + 1 < msg.length() && msg.charAt(i + 1) == 's');
            if (ph && next < args.size()) {
                sb.append(text(args.get(next++)));
                i++;
            } else {
                sb.append(c);
            }
        }
        for (; next < args.size(); next++) {
            sb.append(' ').append(text(args.get(next)));
        }
        return sb.toString();
    }

    private static String text(AxonValue v) {
        return v.isString() ? v.asString() : v.toString();
    }

    // ------------------------------------------------------------------
    // db
    // ------------------------------------------------------------------

    private AxonValue query(List<AxonValue> a, Map<String, AxonValue> kw) {
        AxonValue r = run("db.query", a, kw);
        if (r == null || r.isNull() || r.isNone()) {
            return AxonValue.array(List.of());
        }
        return r.isArray() ? r : AxonValue.array(List.of(r));
    }

    private AxonValue one(List<AxonValue> a, Map<String, AxonValue> kw) {
        AxonValue r = run("db.one", a, kw);
        if (r == null || r.isNull() || r.isNone()) {
            return AxonValue.none();
        }
        if (r.isArray()) {
            return r.asArray().isEmpty() ? AxonValue.none() : r.asArray().get(0);
        }
        return r;
    }

    private AxonValue exec(List<AxonValue> a, Map<String, AxonValue> kw) {
        AxonValue r = run("db.exec", a, kw);
        return r == null ? AxonValue.none() : r;
    }

    /** Número de linhas, ou o valor de um {@code count()} (uma linha com um único campo numérico). */
    private AxonValue count(List<AxonValue> a, Map<String, AxonValue> kw) {
        AxonValue r = run("db.count", a, kw);
        if (r == null || r.isNull() || r.isNone()) {
            return AxonValue.num(0L);
        }
        if (r.isNumber()) {
            return r;
        }
        if (r.isArray()) {
            List<AxonValue> rows = r.asArray();
            if (rows.size() == 1 && rows.get(0).isObject() && rows.get(0).asObject().size() == 1) {
                AxonValue only = rows.get(0).asObject().values().iterator().next();
                if (only.isNumber()) {
                    return only;
                }
            }
            return AxonValue.num((long) rows.size());
        }
        return AxonValue.num(1L);
    }

    private AxonValue rid(List<AxonValue> a) {
        if (a.size() != 2 || !a.get(0).isString()) {
            throw Errors.type("db.rid(tabela, chave) espera o nome da tabela e a chave");
        }
        return AxonValue.record(a.get(0).asString(), a.get(1));
    }

    private AxonValue run(String fn, List<AxonValue> a, Map<String, AxonValue> kw) {
        if (a.isEmpty() || !a.get(0).isString()) {
            throw Errors.type(fn + "() espera o texto SQL como primeiro argumento");
        }
        AxonValue params = a.size() > 1 ? a.get(1) : kw.get("params");
        if (params != null && !params.isNull() && !params.isNone() && !params.isObject()) {
            throw Errors.type(fn + "(): params deve ser um dict {nome: valor}");
        }
        String sql = a.get(0).asString();
        SqlText.Parsed parsed;
        try {
            parsed = SqlText.parse(sql);
        } catch (RuntimeException e) {
            throw Errors.value(fn + "(): SQL invalido: " + e.getMessage());
        }
        guard(fn, parsed);
        service.checkCancelled();
        Map<String, AxonValue> saved = bind(params);
        long started = System.nanoTime();
        try {
            AxonValue r = new Executor(ds).execute(parsed.query(), session, null);
            if (parsed.ddl()) {
                state.ddlRan = true;
            }
            if (state.profile != null) {
                state.profile.registrar(sql, System.nanoTime() - started, QueryProfile.rows(r));
            }
            return r;
        } catch (AxonError e) {
            if (e.code() == QueryContext.CANCELLED) {
                throw new PlLimitError(PlLimitError.Tipo.CANCELADO, "execucao cancelada");
            }
            throw Errors.runtime(fn + "(): " + e.getMessage());
        } finally {
            restore(params, saved);
        }
    }

    private void guard(String fn, SqlText.Parsed parsed) {
        if (parsed.forbidden()) {
            throw Errors.runtime(fn + "(): programas AxonPL nao podem usar " + parsed.forbiddenWhat());
        }
        if (state.noDdl && parsed.ddl()) {
            throw Errors.runtime(fn + "(): sentencas de esquema nao sao permitidas numa depuracao sem commit");
        }
        if (!state.writable && !parsed.readOnly()) {
            throw Errors.runtime(fn + "(): a funcao '" + state.def.name() + "' e somente leitura e nao pode "
                + "escrever; use uma PROCEDURE chamada com CALL");
        }
        if (parsed.ddl()) {
            AxonValue auth = session.auth();
            if (Authz.authenticated(auth) && !Authz.atLeast(auth, Authz.Role.ADMIN)) {
                throw Errors.runtime(fn + "(): DEFINE, CREATE TABLE, DROP e outras sentencas de esquema dentro de "
                    + "funcoes e procedures exigem papel admin");
            }
        }
    }

    /** Liga {@code $nome} na sessão e devolve os valores anteriores (nulo marca ausência). */
    private Map<String, AxonValue> bind(AxonValue params) {
        if (params == null || !params.isObject() || params.asObject().isEmpty()) {
            return Map.of();
        }
        params.asObject().keySet().forEach(session::requireDefinable);
        Map<String, AxonValue> saved = new HashMap<>();
        for (Map.Entry<String, AxonValue> e : params.asObject().entrySet()) {
            saved.put(e.getKey(), session.vars().get(e.getKey()));
            session.vars().set(e.getKey(), e.getValue());
        }
        return saved;
    }

    private void restore(AxonValue params, Map<String, AxonValue> saved) {
        for (Map.Entry<String, AxonValue> e : saved.entrySet()) {
            if (e.getValue() == null) {
                session.vars().unset(e.getKey());
            } else {
                session.vars().set(e.getKey(), e.getValue());
            }
        }
    }
}
