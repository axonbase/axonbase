package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.core.audit.AiAuditCatalog;
import com.axonbase.core.audit.AiAuditDef;
import com.axonbase.core.audit.AiAuditRule;
import com.axonbase.core.audit.AuditCase;
import com.axonbase.core.audit.AuditCaseEvent;
import com.axonbase.core.audit.BlockedUser;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.catalog.Document;
import com.axonbase.core.catalog.RecordId;
import com.axonbase.core.engine.DatabaseLinkClient;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.value.AxonValue;
import com.axonbase.value.AxonJson;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.parser.ast.Statement.Assignment;
import com.axonbase.parser.ast.Statement.Data;
import com.axonbase.parser.ast.Statement.DefineEvent;
import com.axonbase.parser.ast.Statement.DefineField;
import com.axonbase.parser.ast.Statement.DefineIndex;
import com.axonbase.parser.ast.Statement.DefineTable;
import com.axonbase.parser.ast.Statement.DefineDatabaseLink;
import com.axonbase.parser.ast.Statement.Explain;
import com.axonbase.parser.ast.Statement.Info;
import com.axonbase.parser.ast.Statement.Kill;
import com.axonbase.parser.ast.Statement.ReturnSpec;
import com.axonbase.parser.ast.Statement.CreateSaga;
import com.axonbase.parser.ast.Statement.DescribeSaga;
import com.axonbase.parser.ast.Statement.Select;
import com.axonbase.parser.ast.Statement.OrderTerm;
import com.axonbase.parser.ast.Statement.Live;
import com.axonbase.parser.ast.Statement.TimeTravel;
import com.axonbase.core.control.AuthCodec;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.Transaction;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.parser.ast.Statement.Assignment;
import com.axonbase.parser.ast.Statement.Branch;
import com.axonbase.parser.ast.Statement.Data;
import com.axonbase.parser.ast.Statement.DefineEvent;
import com.axonbase.parser.ast.Statement.DefineField;
import com.axonbase.parser.ast.Statement.DefineIndex;
import com.axonbase.parser.ast.Statement.DefineTable;
import com.axonbase.parser.ast.Statement.DefineDatabaseLink;
import com.axonbase.parser.ast.Statement.DropDatabaseLink;
import com.axonbase.parser.ast.Statement.CreateSaga;
import com.axonbase.parser.ast.Statement.DescribeSaga;
import com.axonbase.parser.ast.Statement.ShowSagaTransaction;
import com.axonbase.parser.ast.Statement.BeginSaga;
import com.axonbase.parser.ast.Statement.CommitSaga;
import com.axonbase.parser.ast.Statement.CancelSaga;
import com.axonbase.parser.ast.Statement.JoinSaga;
import com.axonbase.parser.ast.Statement.LeaveSaga;
import com.axonbase.parser.ast.Statement.Info;
import com.axonbase.parser.ast.Statement.Kill;
import com.axonbase.parser.ast.Statement.OrderTerm;
import com.axonbase.parser.ast.Statement.ReturnKind;
import com.axonbase.parser.ast.Statement.ReturnSpec;
import com.axonbase.parser.ast.Statement.UpdateMode;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

import static com.axonbase.value.AxonValue.AxonType.ARRAY;
import static com.axonbase.value.AxonValue.AxonType.NUMBER;
import static com.axonbase.value.AxonValue.AxonType.OBJECT;
import static com.axonbase.value.AxonValue.AxonType.STRING;

/**
 * Executor das sentenças AxonQL. Converte o AST en operacións sobre o
 * {@link Datastore}, procesando cada fila como un {@link Document}
 * copy-on-write. É o equivalente ao executor do SurrealDB, en versión compacta
 * para o MVP.
 */
public final class Executor {

    /** Tabela sintética dos documentos que não vêm do armazenamento. */
    private static final String VIRTUAL_TABLE = "__virtual";

    private final Datastore ds;
    private Session session;
    private final DatabaseLinkClient linkClient = new DatabaseLinkClient();
    private SagaLedger sagaLedger;
    /** SQL original vindo do Datastore, usado pela intercepção de AI Audit. */
    String rawSql;
    /** Quando true, ignora a intercepção de AI Audit (ex.: execução de SQL autorizado). */
    private boolean bypassAudit;

    public Executor(Datastore ds) {
        this.ds = ds;
        this.sagaLedger = new SagaLedger(ds);
    }

    // ------------------------------------------------------------------
    // Execución principal
    // ------------------------------------------------------------------

    public AxonValue execute(Query query, Session session, Map<String, AxonValue> webVars) {
        this.session = session;
        if (webVars != null) {
            webVars.forEach(session.vars()::set);
        }
        AxonValue result = AxonValue.nul();
        for (Statement s : query.statements()) {
            if (!bypassAudit && rawSql != null) {
                AxonValue auditResult = interceptWithAiAudit(rawSql, s);
                if (auditResult != null) {
                    result = auditResult;
                }
            }
            result = runStatement(s);
        }
        return result;
    }

    private Database db() {
        return ds.requireDatabase(session);
    }

    /**
     * Publica uma mudança que o consenso já confirmou nas live queries deste nó.
     *
     * <p>Usado no caminho de replicação: o batch chega aplicado, sem transação
     * local, e o evento tem de sair imediatamente. É o que faz uma subscription num
     * nó que não é líder ver a escrita, e o motivo de nada sair quando o quórum
     * falha, porque aí nenhum batch é aplicado.</p>
     */
    public void publishConfirmed(Session carrier, Database db, RecordId rid, String action,
                                 AxonValue before, AxonValue after) {
        this.session = carrier;
        notifyLive(db, rid, action, before, after);
    }

    /**
     * Persiste uma aresta de grafo no KV com chave direcional dupla.
     * Formato da chave: {@code E|<ns>|<db>|out|<fromTbl>|<fromKey>|<kind>|<toTbl>|<toKey>}.
     */
    private void persistEdge(com.axonbase.core.catalog.Database db, AxonValue.RecordId from,
                             String kind, AxonValue.RecordId to) {
        records(db).put(edgeKey(db, "out", from, kind, to), new byte[0]);
        records(db).put(edgeKey(db, "in", to, kind, from), new byte[0]);
    }

    private String edgeKey(com.axonbase.core.catalog.Database db, String dir, AxonValue.RecordId from,
                           String kind, AxonValue.RecordId to) {
        return edgePrefix(db, dir, from) + kind + "|" + to.table() + "|" + keyToken(to.key());
    }

    /** Prefixo das arestas de um vértice numa direção: tudo até o tipo da aresta. */
    private String edgePrefix(com.axonbase.core.catalog.Database db, String dir, AxonValue.RecordId from) {
        return "E|" + db.ns() + "|" + db.db() + "|" + dir + "|" + from.table()
            + "|" + keyToken(from.key()) + "|";
    }

    /**
     * Forma canónica da chave de um record id nas chaves de aresta
     * ({@code n1}, {@code sana}), igual à usada no armazenamento dos registros.
     */
    private String keyToken(Object key) {
        return keyOf(key).keyString();
    }

    /**
     * Vista de almacenamento do banco de datos: se a sesión ten unha transación
     * activa, usa esa transación; senón, o backend directo.
     */
    private KvBackend records(Database db) {
        Transaction tx = session.tx();
        return tx != null && tx.isOpen() ? tx : db.records();
    }

    private AxonError errorStmt(String message) {
        return AxonError.internal(message);
    }

    // ------------------------------------------------------------------
    // Permisos de táboa (PERMISSIONS FOR ... WHERE ...)
    // ------------------------------------------------------------------

    private boolean allowed(Document doc, String table, String action) {
        Catalog.TableDef def = db().catalog().table(table);
        if (def == null || def.permissions() == null) {
            return true;
        }
        com.axonbase.parser.ast.Statement.Permissions perms = def.permissions();
        com.axonbase.parser.ast.Expr cond = switch (action) {
            case "select" -> perms.select();
            case "create" -> perms.create();
            case "update" -> perms.update();
            case "delete" -> perms.delete();
            default -> null;
        };
        if (cond == null) {
            return true;
        }
        Document target = doc;
        if (target == null) {
            target = emptyDoc();
        }
        return truthy(evalExprInDoc(cond, target));
    }

    private Document emptyDoc() {
        return new Document(new RecordId("", com.axonbase.value.AxonValue.str("")),
            com.axonbase.value.AxonValue.object(Map.of()));
    }

    private String tableName(com.axonbase.parser.ast.Expr from) {
        if (from instanceof com.axonbase.parser.ast.Expr.Ident id) {
            return id.name();
        }
        if (from instanceof com.axonbase.parser.ast.Expr.Qualified q) {
            return q.table();
        }
        if (from instanceof com.axonbase.parser.ast.Expr.RecordId rid) {
            if (rid.table() instanceof com.axonbase.parser.ast.Expr.Ident tid) {
                return tid.name();
            }
            if (rid.table() instanceof com.axonbase.parser.ast.Expr.Qualified q) {
                return q.table();
            }
        }
        return "";
    }

    private Catalog.TableDef ensureTable(Database db, String name) {
        Catalog.TableDef def = db.catalog().table(name);
        if (def == null) {
            def = new Catalog.TableDef(name, false, false);
            db.catalog().defineTable(def);
            control(db, ControlCommand.Kind.TABLE, name, "DEFINE TABLE " + name + " SCHEMALESS");
        }
        return def;
    }

    private AxonValue runStatement(Statement s) {
        return switch (s) {
            case Statement.Use u -> runUse(u);
            case Statement.Let l -> runLet(l);
            case Statement.Create c -> runCreate(c);
            case Statement.CreateJks jks -> runCreateJks(jks);
            case Statement.Insert i -> runInsert(i);
            case Statement.Update u2 -> runUpdate(u2);
            case Statement.Delete d -> runDelete(d);
            case Statement.Select sel -> runSelect(sel);
            case Statement.Relate r -> runRelate(r);
            case DefineTable dt -> runDefineTable(dt);
            case DefineField df -> runDefineField(df);
            case DefineIndex di -> runDefineIndex(di);
            case Statement.DefineAnalyzer da -> runDefineAnalyzer(da);
            case DefineEvent de -> runDefineEvent(de);
            case Statement.DefineUser du -> runDefineUser(du);
            case Statement.DefineAccess da -> runDefineAccess(da);
            case Info inf -> runInfo(inf);
            case Statement.Return r2 -> runReturn(r2);
            case Statement.IfElse ie -> runIfElse(ie);
            case Statement.Live lv -> runLive(lv);
            case Statement.Explain ex -> runExplain(ex);
            case Kill k -> runKill(k);
            case Statement.ErrorStmt ee -> throw errorStmt(eval(ee.message()).asString());
            case Statement.Join j -> throw new IllegalArgumentException(Messages.get("stmt_join_select"));
            case Statement.Begin ignored -> runBegin();
            case Statement.Commit ignored -> runCommit();
            case Statement.Cancel ignored -> runCancel();
            case Statement.Savepoint sp -> {
                session.savepoint(sp.name());
                yield AxonValue.nul();
            }
            case Statement.Release rl -> {
                session.release(rl.name());
                yield AxonValue.nul();
            }
            case Statement.RollbackTo rb -> {
                session.rollbackTo(rb.name());
                yield AxonValue.nul();
            }
            case DefineDatabaseLink ddl -> runDefineDatabaseLink(ddl);
            case DropDatabaseLink ddl2 -> runDropDatabaseLink(ddl2);
            case CreateSaga cs -> runCreateSaga(cs);
            case DescribeSaga ds -> runDescribeSaga(ds);
            case Statement.DescribeTable dt -> runDescribeTable(dt);
            case Statement.ShowSagaTransaction st -> runShowSagaTransaction(st);
            case Statement.BeginSaga bs -> runBeginSaga(bs);
            case Statement.CommitSaga cs2 -> runCommitSaga(cs2);
            case Statement.CancelSaga cs3 -> runCancelSaga(cs3);
            case Statement.JoinSaga js -> runJoinSaga(js);
            case Statement.LeaveSaga ignored -> runLeaveSaga();
            case Statement.CreateDataRule cd -> runCreateDataRule(cd);
            case Statement.DropDataRule dr -> runDropDataRule(dr);
            case Statement.ShowDataRules ignored -> runShowDataRules();
            case Statement.DefineAiAudit aa -> runDefineAiAudit(aa);
            case Statement.DropAiAudit da -> runDropAiAudit(da);
            case Statement.RemoveTable rt -> runRemoveTable(rt);
            case Statement.CreateTable ct -> runCreateTable(ct);
            case Statement.ShowAiAudit sa -> runShowAiAudit(sa);
            case Statement.SetReasonAudit sra -> runSetReasonAudit(sra);
            case Statement.SetAuditCase sac -> runSetAuditCase(sac);
        };
    }

    private AxonValue runCreateJks(Statement.CreateJks jks) {
        AxonValue auth = session.auth();
        AxonValue scope = auth != null && auth.isObject() ? auth.asObject().get("scope") : null;
        if (scope == null || !scope.isString() || !"ROOT".equals(scope.asString())) {
            throw errorStmt(Messages.get("auth_forbidden"));
        }
        ds.registerJks(jks.name(), jks.path(), jks.password(), jks.collector(), jks.oids());
        return AxonValue.nul();
    }

    // ------------------------------------------------------------------
    // Live queries: LIVE SELECT / KILL
    // ------------------------------------------------------------------

    /**
     * Regista uma live query. Devolve o identificador (string UUID) que o
     * cliente usa depois no KILL e que vem em cada notificação.
     */
    private AxonValue runLive(Statement.Live lv) {
        Database db = db();
        String table = tableName(lv.select().from());
        if (table.isEmpty()) {
            throw errorStmt(Messages.get("stmt_live_table_required"));
        }
        ensureTable(db, table);
        String id = ds.liveBus().register(db.ns(), db.db(), table, lv.select(), lv.diff(), session);
        return AxonValue.str(id);
    }

    /** Cancela uma live query pelo identificador. */
    private AxonValue runKill(Kill k) {
        AxonValue target = eval(k.target());
        String id = target.isString() ? target.asString() : String.valueOf(target.asString());
        return AxonValue.bool(ds.liveBus().kill(id));
    }

    /**
     * Publica uma mudança nas live queries da tabela. Com transação aberta, a
     * entrega fica retida até o COMMIT.
     */
    private void notifyLive(Database db, RecordId rid, String action,
                            AxonValue before, AxonValue after) {
        LiveBus bus = ds.liveBus();
        if (bus.isEmpty()) {
            return;
        }
        List<LiveBus.Subscription> subs = bus.forTable(db.ns(), db.db(), rid.table());
        if (subs.isEmpty()) {
            return;
        }
        AxonValue candidate = LiveBus.DELETE.equals(action) ? before : after;
        if (candidate == null || !candidate.isObject()) {
            return;
        }
        for (LiveBus.Subscription sub : subs) {
            Document doc = new Document(rid, candidate);
            if (sub.select().cond() != null && !truthy(evalExprInDoc(sub.select().cond(), doc))) {
                continue;
            }
            AxonValue payload;
            if (sub.diff() && !LiveBus.DELETE.equals(action)) {
                payload = diffOf(before, after);
            } else {
                payload = project(sub.select(), doc);
            }
            LiveBus.Notification note = new LiveBus.Notification(sub.id(), action, payload);
            if (session.inTransaction()) {
                session.queueLive(() -> bus.deliver(sub, note));
            } else {
                bus.deliver(sub, note);
            }
        }
    }

    /**
     * Diff no estilo JSON Patch entre o registro anterior e o novo: lista de
     * operações {@code add}, {@code replace} e {@code remove}.
     */
    private AxonValue diffOf(AxonValue before, AxonValue after) {
        Map<String, AxonValue> prev = before != null && before.isObject()
            ? before.asObject() : Map.of();
        Map<String, AxonValue> next = after != null && after.isObject()
            ? after.asObject() : Map.of();
        List<AxonValue> ops = new ArrayList<>();
        for (Map.Entry<String, AxonValue> e : next.entrySet()) {
            AxonValue old = prev.get(e.getKey());
            if (old == null) {
                ops.add(patchOp("add", e.getKey(), e.getValue()));
            } else if (!old.equals(e.getValue())) {
                ops.add(patchOp("replace", e.getKey(), e.getValue()));
            }
        }
        for (String key : prev.keySet()) {
            if (!next.containsKey(key)) {
                ops.add(patchOp("remove", key, null));
            }
        }
        return AxonValue.array(ops);
    }

    private AxonValue patchOp(String op, String path, AxonValue value) {
        Map<String, AxonValue> m = new LinkedHashMap<>();
        m.put("op", AxonValue.str(op));
        m.put("path", AxonValue.str("/" + path));
        if (value != null) {
            m.put("value", value);
        }
        return AxonValue.object(m);
    }

    // ------------------------------------------------------------------
    // Eventos de tabela (DEFINE EVENT)
    // ------------------------------------------------------------------

    /**
     * Dispara os eventos definidos na tabela. Expõe {@code $event} (CREATE,
     * UPDATE ou DELETE), {@code $before}, {@code $after} e {@code $value}.
     */
    private void fireEvents(Database db, RecordId rid, String action,
                            AxonValue before, AxonValue after) {
        Catalog.TableDef def = db.catalog().table(rid.table());
        if (def == null || def.events().isEmpty()) {
            return;
        }
        AxonValue value = after != null ? after : before;
        AxonValue prevEvent = session.vars().get("event");
        AxonValue prevBefore = session.vars().get("before");
        AxonValue prevAfter = session.vars().get("after");
        AxonValue prevValue = session.vars().get("value");
        session.vars().set("event", AxonValue.str(action));
        session.vars().set("before", before == null ? AxonValue.nul() : before);
        session.vars().set("after", after == null ? AxonValue.nul() : after);
        session.vars().set("value", value == null ? AxonValue.nul() : value);
        try {
            for (Catalog.EventDef ev : def.events().values()) {
                if (ev.then().isEmpty()) {
                    continue;
                }
                Document doc = new Document(rid, value != null ? value : AxonValue.object(Map.of()));
                if (ev.whenExpr() != null && !truthy(evalExprInDoc(ev.whenExpr(), doc))) {
                    continue;
                }
                for (Statement st : ev.then()) {
                    runStatement(st);
                }
            }
        } finally {
            restoreVar("event", prevEvent);
            restoreVar("before", prevBefore);
            restoreVar("after", prevAfter);
            restoreVar("value", prevValue);
        }
    }

    private void restoreVar(String name, AxonValue previous) {
        session.vars().set(name, previous == null ? AxonValue.none() : previous);
    }

    private AxonValue runBegin() {
        ds.beginSession(session);
        return AxonValue.nul();
    }

    private AxonValue runCommit() {
        ds.commitSession(session);
        return AxonValue.nul();
    }

    private AxonValue runCancel() {
        ds.cancelSession(session);
        return AxonValue.nul();
    }

    // ------------------------------------------------------------------
    // Sentencias de control
    // ------------------------------------------------------------------

    private AxonValue runUse(Statement.Use u) {
        if (u.ns() != null) {
            session.namespace(literalIdent(u.ns()));
        }
        if (u.db() != null) {
            session.database(literalIdent(u.db()));
        }
        return AxonValue.nul();
    }

    private AxonValue runLet(Statement.Let l) {
        AxonValue v = eval(l.value());
        session.vars().set(l.name(), v);
        return v;
    }

    private AxonValue runReturn(Statement.Return r) {
        return eval(r.value());
    }

    private AxonValue runIfElse(Statement.IfElse ie) {
        for (Branch b : ie.branches()) {
            if (truthy(eval(b.cond()))) {
                return eval(b.thenExpr());
            }
        }
        if (ie.elseBranch() != null) {
            return eval(ie.elseBranch());
        }
        return AxonValue.nul();
    }

    /**
     * Metadados do catálogo. Os nomes são ordenados para que INFO seja estável
     * para conectores, diffs e testes.
     */
    private AxonValue runInfo(Info inf) {
        return switch (inf.kind()) {
            case "root" -> infoRoot();
            case "namespace" -> infoNamespace();
            case "database" -> infoDatabase();
            case "table" -> infoTable(inf.table());
            default -> throw errorStmt(Messages.get("stmt_info_scope_unknown", inf.kind()));
        };
    }

    private AxonValue infoRoot() {
        Map<String, AxonValue> namespaces = new LinkedHashMap<>();
        ds.namespaces().stream().sorted().forEach(ns -> namespaces.put(ns,
            AxonValue.object(Map.of("databases", AxonValue.array(strings(
                ds.databases(ns).stream().sorted().toList()))))));
        Map<String, AxonValue> users = new LinkedHashMap<>();
        ds.authCatalog().users().forEach(user -> users.put(user.name() + "@" + user.scope(),
            AxonValue.object(Map.of(
                "scope", AxonValue.str(user.scope().name()),
                "namespace", user.namespace() == null ? AxonValue.nul() : AxonValue.str(user.namespace()),
                "database", user.database() == null ? AxonValue.nul() : AxonValue.str(user.database()),
                "roles", AxonValue.array(strings(user.roles()))))));
        Map<String, AxonValue> accesses = new LinkedHashMap<>();
        ds.authCatalog().accesses().forEach(access -> accesses.put(access.name() + "@" + access.scope(),
            AxonValue.object(Map.of(
                "scope", AxonValue.str(access.scope().name()),
                "namespace", access.namespace() == null ? AxonValue.nul() : AxonValue.str(access.namespace()),
                "database", access.database() == null ? AxonValue.nul() : AxonValue.str(access.database())))));
        return AxonValue.object(Map.of(
            "namespaces", AxonValue.array(strings(ds.namespaces().stream().sorted().toList())),
            "details", AxonValue.object(namespaces),
            "functions", AxonValue.array(strings(Functions.names())),
            "users", AxonValue.object(users),
            "accesses", AxonValue.object(accesses)));
    }

    private AxonValue infoNamespace() {
        String ns = session.namespace();
        if (ns == null || ns.isBlank()) {
            throw errorStmt(Messages.get("stmt_info_namespace_required"));
        }
        return AxonValue.object(Map.of(
            "namespace", AxonValue.str(ns),
            "databases", AxonValue.array(strings(ds.databases(ns).stream().sorted().toList()))));
    }

    private AxonValue infoDatabase() {
        Database db = db();
        List<String> tables = db.catalog().tables().stream().map(Catalog.TableDef::name).sorted().toList();
        Map<String, AxonValue> detail = new LinkedHashMap<>();
        for (String table : tables) {
            detail.put(table, tableInfo(db.catalog().table(table)));
        }
        return AxonValue.object(Map.of(
            "namespace", AxonValue.str(db.ns()),
            "database", AxonValue.str(db.db()),
            "tables", AxonValue.array(strings(tables)),
            "details", AxonValue.object(detail)));
    }

    private AxonValue infoTable(String table) {
        if (table == null || table.isBlank()) {
            throw errorStmt(Messages.get("stmt_info_table_required"));
        }
        Catalog.TableDef def = db().catalog().table(table);
        if (def == null) {
            throw errorStmt(Messages.get("stmt_table_undefined", table));
        }
        return tableInfo(def);
    }

    private AxonValue tableInfo(Catalog.TableDef def) {
        Map<String, AxonValue> fields = new LinkedHashMap<>();
        def.fields().values().stream().sorted(Comparator.comparing(Catalog.FieldDef::name)).forEach(f -> {
            Map<String, AxonValue> info = new LinkedHashMap<>();
            info.put("type", f.type() == null ? AxonValue.nul() : AxonValue.str(f.type()));
            info.put("readonly", AxonValue.bool(f.readonly()));
            if (f.assertExpr() != null) {
                info.put("assert", AxonValue.str(com.axonbase.parser.Render.expr(f.assertExpr())));
            }
            if (f.defaultExpr() != null) {
                info.put("default", AxonValue.str(com.axonbase.parser.Render.expr(f.defaultExpr())));
            }
            fields.put(f.name(), AxonValue.object(info));
        });
        Map<String, AxonValue> indexes = new LinkedHashMap<>();
        def.indexes().values().stream().sorted(Comparator.comparing(Catalog.IndexDef::name)).forEach(i -> {
            Map<String, AxonValue> info = new LinkedHashMap<>();
            info.put("columns", AxonValue.array(strings(i.columns())));
            info.put("unique", AxonValue.bool(i.unique()));
            info.put("count", AxonValue.bool(i.count()));
            info.put("search", i.searchAnalyzer() == null ? AxonValue.nul() : AxonValue.str(i.searchAnalyzer()));
            info.put("geo", AxonValue.bool(i.geo()));
            info.put("vector_dimension", i.vectorDimension() == null ? AxonValue.nul() : AxonValue.num(i.vectorDimension()));
            info.put("vector_distance", i.vectorDistance() == null ? AxonValue.nul() : AxonValue.str(i.vectorDistance()));
            indexes.put(i.name(), AxonValue.object(info));
        });
        Map<String, AxonValue> events = new LinkedHashMap<>();
        def.events().values().stream().sorted(Comparator.comparing(Catalog.EventDef::name)).forEach(e ->
            events.put(e.name(), AxonValue.object(Map.of(
                "when", AxonValue.str(e.when()),
                "then", AxonValue.num(e.then().size())))));
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("name", AxonValue.str(def.name()));
        out.put("schema", AxonValue.str(def.schemafull() ? "SCHEMAFULL" : "SCHEMALESS"));
        out.put("drop", AxonValue.bool(def.drop()));
        out.put("fields", AxonValue.object(fields));
        out.put("indexes", AxonValue.object(indexes));
        out.put("events", AxonValue.object(events));
        return AxonValue.object(out);
    }

    private List<AxonValue> strings(List<String> list) {
        return list.stream().map(AxonValue::str).toList();
    }

    // ------------------------------------------------------------------
    // DML: Create / Insert
    // ------------------------------------------------------------------

    private AxonValue runCreate(Statement.Create c) {
        Database db = db();
        if (c.target() instanceof Expr.Qualified q) {
            return runCreateViaLink(c, q, null);
        }
        if (c.target() instanceof Expr.RecordId rid && rid.table() instanceof Expr.Qualified q2) {
            return runCreateViaLink(c, q2, rid);
        }
        String table = tableOf(c.target());
        ensureTable(db, table);
        AxonValue explicitKey = c.target() instanceof Expr.RecordId rid
            ? recordKey(rid.key()) : null;
        AxonValue record = createRecord(db, table, evalData(c.data()), explicitKey);
        // Saga: record step (before=null)
        String sagaCorr = sagaCorrelation();
        if (sagaCorr != null && record.isObject()) {
            AxonValue ridVal = record.asObject().get("id");
            if (ridVal != null && ridVal.isRecordId()) {
                var ar = ridVal.asRecordId();
                RecordId rId = new RecordId(ar.table(), keyOf(ar.key()));
                sagaLedger.recordStep(sagaCorr, db.ns(), db.db(), "", "local", rId.table(), rId, AxonValue.nul(), "CREATE");
            }
        }
        return c.only() ? record : AxonValue.array(List.of(record));
    }

    private AxonValue runCreateViaLink(Statement.Create create, Expr.Qualified target, Expr.RecordId recordId) {
        String sagaCorr = sagaCorrelation();
        if (sagaCorr != null && recordId == null) {
            throw errorStmt(Messages.get("stmt_saga_record_required"));
        }
        AxonValue result = runWriteViaLink(target, com.axonbase.parser.Render.stmt(create));
        if (sagaCorr != null) {
            Datastore.DatabaseLinkDef linkDef = ds.databaseLink(session.namespace(), target.link());
            if (linkDef == null) {
                throw errorStmt(Messages.get("stmt_database_link_missing", target.link()));
            }
            sagaLedger.recordStep(sagaCorr, linkDef.ns(), linkDef.db(), session.namespace(), target.link(),
                target.table(), new RecordId(target.table(), recordKey(recordId.key())), AxonValue.nul(), "CREATE");
        }
        return result;
    }

    private AxonValue runInsert(Statement.Insert i) {
        Database db = db();
        ensureTable(db, i.table());
        AxonValue data = eval(i.data());
        List<AxonValue> records = new ArrayList<>();
        if (data.isArray()) {
            for (AxonValue item : data.asArray()) {
                if (item.isObject()) {
                    records.add(createRecord(db, i.table(), item));
                }
            }
        } else if (data.isObject()) {
            records.add(createRecord(db, i.table(), data));
        }
        return output(i.ret(), AxonValue.array(records));
    }

    private AxonValue evalData(Data data) {
        return switch (data) {
            case Data.Content c -> eval(c.content());
            case Data.SetClause sc -> {
                Map<String, AxonValue> m = new LinkedHashMap<>();
                for (Assignment a : sc.assignments()) {
                    m.put(fieldOf(a.path()), eval(a.value()));
                }
                yield AxonValue.object(m);
            }
            case Data.Merge m -> eval(m.merge());
            case Data.Patch p -> eval(p.patch());
            case Data.Replace r -> eval(r.replace());
            case null -> AxonValue.object(Map.of());
        };
    }

    private AxonValue createRecord(Database db, String table, AxonValue input) {
        return createRecord(db, table, input, null);
    }

    private AxonValue createRecord(Database db, String table, AxonValue input, AxonValue explicitKey) {
        Map<String, AxonValue> obj = new LinkedHashMap<>();
        if (input.isObject()) {
            obj.putAll(input.asObject());
        }
        Object idKey = obj.containsKey("id") ? unwrapId(obj.remove("id")) : null;
        AxonValue key = explicitKey != null ? explicitKey : resolveKey(idKey);
        RecordId rid = new RecordId(table, key);
        obj.put("id", ridAsValue(rid));
        applyFieldSchema(db, table, obj, false);
        injectDataRuleFields(obj);
        AxonValue record = AxonValue.object(obj);
        Document document = new Document(rid, record);
        ds.auditLog(session, "CREATE", table, rid, null, record);
        if (!dataRuleFilter(document) || !allowed(document, table, "create")) {
            throw errorStmt("permission denied");
        }
        enforceUnique(db, rid, null, record);
        records(db).put(rid.storageKey(db.ns(), db.db()), encode(record));
        indexSearch(db, rid, null, record);
        indexAdvanced(db, rid, null, record);
        indexColumnar(db, rid, null, record);
        notifyLive(db, rid, LiveBus.CREATE, null, record);
        fireEvents(db, rid, LiveBus.CREATE, null, record);
        return record;
    }

    /**
     * Aplica o schema do campo (DEFINE FIELD): prepótelo default, coerce o tipo e
     * valida o assert quando o valor é fornecido. Em {@code create}, os defaults
     * são aplicados; em {@code update}, só coerção/assert.
     */
    private void applyFieldSchema(Database db, String table, Map<String, AxonValue> obj, boolean update) {
        Catalog.TableDef def = db.catalog().table(table);
        if (def == null) {
            return;
        }
        for (Catalog.FieldDef fd : def.fields().values()) {
            String fieldName = fd.name();
            boolean present = obj.containsKey(fieldName);
            AxonValue value = present ? obj.get(fieldName) : null;
            if (!present && fd.defaultExpr() != null) {
                obj.put(fieldName, eval(fd.defaultExpr()));
                present = true;
                value = obj.get(fieldName);
            }
            if (present && value != null && fd.type() != null && !value.isNull() && !value.isNone()) {
                AxonValue coerced = coerceType(fd.type(), value);
                if (coerced == null) {
                    throw AxonError.internal(Messages.get("stmt_field_type_invalid",
                        fieldName, fd.type(), value));
                }
                obj.put(fieldName, coerced);
                value = coerced;
            }
            if (present && value != null && fd.assertExpr() != null) {
                AxonValue ok = evalWithValue(fd.assertExpr(), value);
                if (!truthy(ok)) {
                    throw AxonError.internal(Messages.get("stmt_assert_failed", fieldName, value));
                }
            }
            if (present && value != null && fd.references() != null && (value.isRecordId() || value.isString())) {
                validateReference(db, fd.references(), value);
            }
        }
    }

    private void validateReference(Database db, String targetTable, AxonValue value) {
        String table = value.isRecordId() ? value.asRecordId().table() : null;
        Object key = value.isRecordId() ? value.asRecordId().key() : null;
        if (key == null && value.isString()) {
            String s = value.asString();
            int i = s.indexOf(':');
            table = i >= 0 ? s.substring(0, i) : targetTable;
            key = i >= 0 ? s.substring(i + 1) : s;
        }
        if (table == null || key == null) {
            return;
        }
        RecordId ref = new RecordId(table, key instanceof AxonValue kv ? kv : AxonValue.str(String.valueOf(key)));
        if (loadDoc(db, ref) == null) {
            throw AxonError.internal(Messages.get("stmt_broken_reference", ref, targetTable));
        }
    }

    private AxonValue evalWithValue(Expr e, AxonValue value) {
        // A expressão pode referenciar $value; ligamos um param temporário
        boolean had = session.vars().contains("value");
        AxonValue prev = session.vars().get("value");
        session.vars().set("value", value);
        try {
            return eval(e);
        } finally {
            if (had) {
                session.vars().set("value", prev);
            } else {
                session.vars().unset("value");
            }
        }
    }

    private AxonValue coerceType(String type, AxonValue v) {
        String t = type.toLowerCase();
        return switch (t) {
            case "int" -> v.isNumber() ? AxonValue.num(v.asLong())
                : v.isString() ? tryLong(v.asString()) : null;
            case "float", "number" -> v.isNumber() ? AxonValue.num(v.asDouble())
                : v.isString() ? tryDouble(v.asString()) : null;
            case "string" -> v.isString() ? v : AxonValue.str(v.toString());
            case "bool" -> v.isBool() ? v : null;
            case "datetime" -> v.isDatetime() ? v
                : v.isString() ? tryInstant(v.asString()) : null;
            case "array" -> v.isArray() ? v : null;
            case "object" -> v.isObject() ? v : null;
            case "geometry" -> isGeometry(v) ? v : null;
            case "vector" -> v.isArray() && v.asArray().stream().allMatch(AxonValue::isNumber) ? v : null;
            default -> v;
        };
    }

    private boolean isGeometry(AxonValue value) {
        return value.isObject() && value.asObject().get("type") != null
            && value.asObject().get("type").isString() && value.asObject().get("coordinates") != null;
    }

    private AxonValue tryLong(String s) {
        try {
            return AxonValue.num(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AxonValue tryInstant(String s) {
        try {
            return AxonValue.datetime(java.time.Instant.parse(s));
        } catch (Exception e) {
            return null;
        }
    }

    private AxonValue tryDouble(String s) {
        try {
            return AxonValue.num(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> stringKeys(List<AxonValue> l) {
        return l.stream().filter(AxonValue::isString).map(AxonValue::asString).toList();
    }

    // ------------------------------------------------------------------
    // DML: Update / Delete
    // ------------------------------------------------------------------

    private AxonValue runUpdate(Statement.Update u) {
        Database db = db();
        Expr uTarget = u.target();
        Expr.RecordId qRid = uTarget instanceof Expr.RecordId r && r.table() instanceof Expr.Qualified ? r : null;
        if (uTarget instanceof Expr.Qualified || qRid != null) {
            Expr.Qualified q = qRid != null ? (Expr.Qualified) qRid.table() : (Expr.Qualified) uTarget;
            String sessionNs = session.namespace();
            boolean isLocal = sessionNs != null && q.link().equals(sessionNs);
            if (!isLocal) {
                // Saga: capture BEFORE state for remote write
                String sagaCorr = sagaCorrelation();
                if (sagaCorr != null && qRid == null) {
                    throw errorStmt(Messages.get("stmt_saga_record_required"));
                }
                if (sagaCorr != null) {
                    try {
                        Datastore.DatabaseLinkDef linkDef = ds.databaseLink(session.namespace(), q.link());
                        if (linkDef != null) {
                            String selectSql = "SELECT * FROM " + q.table() + ":"
                                + com.axonbase.parser.Render.expr(qRid.key());
                            List<AxonValue> rows = linkClient.query(linkDef, selectSql);
                            AxonValue before = rows.isEmpty() ? AxonValue.nul() : rows.get(0);
                            sagaLedger.recordStep(sagaCorr, linkDef.ns(), linkDef.db(), session.namespace(), q.link(),
                                q.table(), new RecordId(q.table(), recordKey(qRid.key())), before, "UPDATE");
                        }
                    } catch (Exception e) {
                        System.err.println("Saga remote step recording failed: " + e.getMessage());
                    }
                }
                return runWriteViaLink(q, com.axonbase.parser.Render.stmt(u));
            }
        }
        boolean retNone = u.ret() != null && u.ret().kind() == ReturnKind.NONE;
        List<RecordId> targets = resolveTargets(u.target());
        List<AxonValue> results = new ArrayList<>();
        for (RecordId rid : targets) {
            Document doc = loadDoc(db, rid);
            if (doc == null) {
                continue;
            }
            if (u.cond() != null && !truthy(evalOnDocument(u.cond(), doc))) {
                continue;
            }
            if (!dataRuleFilter(doc)) {
                ds.auditLog(session, "UPDATE", rid.table(), rid, doc.current(), null);
                continue;
            }
            if (!allowed(doc, tableName(u.target()), "update")) {
                ds.auditLog(session, "UPDATE_BLOCKED", rid.table(), rid, doc.current(), null);
                continue;
            }
            // Saga: capture BEFORE state
            String sagaCorr = sagaCorrelation();
            if (sagaCorr != null) {
                try {
                    sagaLedger.recordStep(sagaCorr, db.ns(), db.db(), "", "local", rid.table(), rid, doc.current(), "UPDATE");
                } catch (Exception e) {
                    // Log but don't fail the main operation
                    System.err.println("Saga step recording failed (non-fatal): " + e.getMessage());
                }
            }
            Map<String, AxonValue> current = new LinkedHashMap<>(doc.current().asObject());
            if (u.data() != null) {
                applyMutate(current, u.data(), doc);
            }
            applyFieldSchema(db, tableName(u.target()), current, true);
            injectDataRuleFields(current);
            AxonValue next = AxonValue.object(current);
            doc.setCurrent(next);
            enforceUnique(db, rid, doc.initial(), next);
            records(db).put(rid.storageKey(db.ns(), db.db()), encode(next));
            indexSearch(db, rid, doc.initial(), next);
            indexAdvanced(db, rid, doc.initial(), next);
            indexColumnar(db, rid, doc.initial(), next);
            notifyLive(db, rid, LiveBus.UPDATE, doc.initial(), next);
            fireEvents(db, rid, LiveBus.UPDATE, doc.initial(), next);
            ds.auditLog(session, "UPDATE", rid.table(), rid, doc.initial(), next);
            if (u.ret() != null && u.ret().kind() == ReturnKind.BEFORE) {
                results.add(doc.initial());
            } else {
                results.add(next);
            }
        }
        if (u.mode() == UpdateMode.UPSERT && results.isEmpty() && u.data() != null) {
            if (u.target() instanceof Expr.Ident idTarget) {
                String table = idTarget.name();
                ensureTable(db, table);
                results.add(createRecord(db, table, evalData(u.data())));
            } else if (u.target() instanceof Expr.RecordId rid
                && rid.table() instanceof Expr.Ident ridTable) {
                String table = ridTable.name();
                ensureTable(db, table);
                results.add(createRecord(db, table, evalData(u.data()), recordKey(rid.key())));
            }
        }
        return retNone ? AxonValue.none() : AxonValue.array(results);
    }

    private AxonValue runDelete(Statement.Delete d) {
        Database db = db();
        Expr dTarget = d.target();
        Expr.RecordId dqRid = dTarget instanceof Expr.RecordId r && r.table() instanceof Expr.Qualified ? r : null;
        if (dTarget instanceof Expr.Qualified || dqRid != null) {
            Expr.Qualified q = dqRid != null ? (Expr.Qualified) dqRid.table() : (Expr.Qualified) dTarget;
            String sessionNs = session.namespace();
            boolean isLocal = sessionNs != null && q.link().equals(sessionNs);
            if (!isLocal) {
                String sagaCorr = sagaCorrelation();
                if (sagaCorr != null && dqRid == null) {
                    throw errorStmt(Messages.get("stmt_saga_record_required"));
                }
                if (sagaCorr != null) {
                    try {
                        Datastore.DatabaseLinkDef linkDef = ds.databaseLink(session.namespace(), q.link());
                        if (linkDef != null) {
                            String selectSql = "SELECT * FROM " + q.table() + ":"
                                + com.axonbase.parser.Render.expr(dqRid.key());
                            List<AxonValue> rows = linkClient.query(linkDef, selectSql);
                            AxonValue before = rows.isEmpty() ? AxonValue.nul() : rows.get(0);
                            sagaLedger.recordStep(sagaCorr, linkDef.ns(), linkDef.db(), session.namespace(), q.link(),
                                q.table(), new RecordId(q.table(), recordKey(dqRid.key())), before, "DELETE");
                        }
                    } catch (Exception e) {
                        System.err.println("Saga remote step recording failed: " + e.getMessage());
                    }
                }
                return runWriteViaLink(q, com.axonbase.parser.Render.stmt(d));
            }
        }
        List<RecordId> targets = resolveTargets(d.target());
        List<AxonValue> deleted = new ArrayList<>();
        for (RecordId rid : targets) {
            Document doc = loadDoc(db, rid);
            if (doc == null) {
                continue;
            }
            if (d.cond() != null && !truthy(evalExprInDoc(d.cond(), doc))) {
                continue;
            }
            if (!dataRuleFilter(doc)) {
                ds.auditLog(session, "DELETE", rid.table(), rid, doc.current(), null);
                continue;
            }
            if (!allowed(doc, tableName(d.target()), "delete")) {
                ds.auditLog(session, "DELETE_BLOCKED", rid.table(), rid, doc.current(), null);
                continue;
            }
            // Saga: capture BEFORE state
            String sagaCorr = sagaCorrelation();
            if (sagaCorr != null) {
                sagaLedger.recordStep(sagaCorr, db.ns(), db.db(), "", "local", rid.table(), rid, doc.current(), "DELETE");
            }
            records(db).delete(rid.storageKey(db.ns(), db.db()));
            indexSearch(db, rid, doc.current(), null);
            indexAdvanced(db, rid, doc.current(), null);
            indexColumnar(db, rid, doc.current(), null);
            notifyLive(db, rid, LiveBus.DELETE, doc.current(), null);
            fireEvents(db, rid, LiveBus.DELETE, doc.current(), null);
            ds.auditLog(session, "DELETE", rid.table(), rid, doc.current(), null);
            if (d.ret() != null && d.ret().kind() == ReturnKind.BEFORE) {
                deleted.add(doc.current());
            }
        }
        return deleted.isEmpty() ? AxonValue.array(List.of()) : AxonValue.array(deleted);
    }

    private void applyMutate(Map<String, AxonValue> current, Data data, Document doc) {
        switch (data) {
            case Data.SetClause sc -> {
                for (Assignment a : sc.assignments()) {
                    String fieldName;
                    if (a.path() instanceof Expr.Ident id) {
                        fieldName = id.name();
                    } else if (a.path() instanceof Expr.Idiom idiom) {
                        fieldName = fieldOf(idiom);
                    } else {
                        continue;
                    }
                    AxonValue newValue = evalOnDocument(a.value(), doc);
                    AxonValue existing = current.get(fieldName);
                    if (existing != null && existing.isNumber() && newValue.isString()) {
                        try {
                            String s = newValue.asString().trim();
                            newValue = s.contains(".") || s.contains("e") || s.contains("E")
                                ? AxonValue.num(Double.parseDouble(s))
                                : AxonValue.num(Long.parseLong(s));
                        } catch (NumberFormatException ignored) {}
                    }
                    current.put(fieldName, newValue);
                }
            }
            case Data.Merge m -> {
                AxonValue mv = evalExpr(m.merge());
                if (mv.isObject()) {
                    current.putAll(mv.asObject());
                }
            }
            case Data.Patch p -> {
                AxonValue pv = evalExpr(p.patch());
                if (pv.isObject()) {
                    current.putAll(pv.asObject());
                }
            }
            case Data.Replace r -> {
                AxonValue rv = evalExpr(r.replace());
                if (rv.isObject()) {
                    current.clear();
                    current.putAll(rv.asObject());
                }
            }
            case Data.Content c -> {
                AxonValue cv = evalExpr(c.content());
                if (cv.isObject()) {
                    current.clear();
                    current.putAll(cv.asObject());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // DQL: Select
    // ------------------------------------------------------------------

    private AxonValue runSelect(Statement.Select sel) {
        // Check if FROM is AUDIT_CASES virtual table
        if (sel.from() instanceof Expr.Ident id) {
            String tableName = id.name();
            if (isAuditCasesTable(tableName)) return runSelectAuditCases(sel);
            if (isAuditLogTable(tableName)) return runSelectAuditLog(sel);
        }
        // Check if FROM references a DATABASE LINK → delegate entire query
        if (sel.from() instanceof Expr.RecordId selRid && selRid.table() instanceof Expr.Qualified selQ) {
            String ns = session.namespace();
            if (ns != null && !ns.isBlank()) {
                Datastore.DatabaseLinkDef linkDef = ds.databaseLink(ns, selQ.link());
                if (linkDef != null) {
                    return runSelectViaLink(linkDef, selQ, sel);
                }
            }
        }
        long timeTravelTs = -1;
        if (sel.timeTravel() != null) {
            AxonValue tv = eval(sel.timeTravel().value());
            // Subquery SELECT VALUE retorna array com 1 elemento
            if (tv.isArray() && tv.asArray().size() == 1) {
                tv = tv.asArray().get(0);
            }
            if (tv.isDatetime()) {
                timeTravelTs = tv.asInstant().toEpochMilli();
            } else if (tv.isNumber()) {
                timeTravelTs = tv.asLong();
            } else if (tv.isString()) {
                try {
                    timeTravelTs = java.time.Instant.parse(tv.asString()).toEpochMilli();
                } catch (Exception e) {
                    throw errorStmt(Messages.get("stmt_time_travel_invalid", tv.asString()));
                }
            } else {
                throw errorStmt(Messages.get("stmt_tt_type"));
            }
        }
        Database db = db();
        HybridSearch hy = hybridSearch(sel);
        List<Document> docs;
        if (sel.from() == null) {
            // SELECT VALUE expr() — sem FROM, cria documento virtual vazio
            docs = virtualDocs(AxonValue.object(Map.of()));
        } else if (timeTravelTs >= 0) {
            docs = historicalDocs(db, sel, timeTravelTs);
        } else if (hy != null) {
            docs = hybridSourceDocs(db, sel, hy);
        } else {
            docs = uniqueLookupSourceDocs(db, sel,
                vectorSourceDocs(db, sel, geoSourceDocs(db, sel,
                    columnarSourceDocs(db, sel, searchSourceDocs(db, sel)))));
        }
        if (sel.cond() != null) {
            docs.removeIf(doc -> !truthy(evalCond(sel.cond(), doc)));
        }
        applyDataRulesFilter(docs);
        applySearchScores(sel, docs);
        docs.removeIf(doc -> sel.from() != null && !allowed(doc, tableName(sel.from()), "select"));
        boolean joined = !sel.joins().isEmpty();
        if (joined) {
            docs = joinDocs(db, sel, docs);
        }
        List<OrderTerm> orders = sel.orders();
        if (hy != null && orders.isEmpty()) {
            docs.sort((a, b) -> Double.compare(b.searchScore(), a.searchScore()));
        } else if (!orders.isEmpty()) {
            Comparator<Document> cmp = orderComparator(orders);
            docs.sort(cmp);
        }
        int start = sel.start() != null ? (int) eval(sel.start()).asLong() : 0;
        int limit = sel.limit() != null ? (int) eval(sel.limit()).asLong() : docs.size();
        int stop = Math.min(docs.size(), start + limit);
        List<AxonValue> out = new ArrayList<>();
        boolean hasAgg = !joined && sel.fields().stream().anyMatch(this::isAggregateCall);
        if (hasAgg) {
            // FETCH resolve antes da agregación, que traballa sobre todo o conxunto
            if (!sel.fetch().isEmpty()) {
                docs.forEach(d -> fetchDocs(d, sel.fetch()));
            }
            out.addAll(aggregate(sel, docs));
        } else {
            // FETCH resolvase só sobre a fiestra [start, stop), non sobre todos os docs
            for (int i = Math.min(start, docs.size()); i < stop; i++) {
                Document d = docs.get(i);
                if (!sel.fetch().isEmpty() && !joined) {
                    fetchDocs(d, sel.fetch());
                }
                out.add(joined ? projectJoin(sel, d) : project(sel, d));
            }
        }
        if (!sel.only() && !sel.valueOnly()) {
            out.replaceAll(this::applyDataRuleMask);
        }
        if (out.size() == 1 && (sel.only() || (sel.from() != null && isSingleTarget(sel.from())))) {
            if (ds.auditSelect() && sel.from() != null) {
                String tableName = tableName(sel.from());
                if (tableName != null && !tableName.isBlank()) {
                    ds.auditLog(session, "SELECT", tableName, null, null, null);
                }
            }
            return out.get(0);
        }
        if (ds.auditSelect() && sel.from() != null) {
            String tableName = tableName(sel.from());
            if (tableName != null && !tableName.isBlank()) {
                ds.auditLog(session, "SELECT", tableName, null, null, null);
            }
        }
        return AxonValue.array(out);
    }

    // ------------------------------------------------------------------
    // JOIN: bucle aniñado sobre a fonte do FROM e a(s) fonte(s) enlazada(s)
    // ------------------------------------------------------------------

    private List<Document> joinDocs(Database db, Statement.Select sel, List<Document> outer) {
        List<Document> current = outer;
        for (var join : sel.joins()) {
            current = joinPair(db, current, tableName(join.source()), join, tableName(sel.from()));
        }
        return current;
    }

    private List<Document> joinPair(Database db, List<Document> outer, String innerTable,
                                    Statement.Join join, String outerTable) {
        String outerKey = lastFieldName(join.left());
        String innerKey = lastFieldName(join.right());
        List<Document> inner = dbDocs(db, join.source());
        List<Document> out = new ArrayList<>();
        for (Document lo : outer) {
            AxonValue ok = fieldValue(lo.current(), outerKey);
            if (unspecified(ok)) {
                continue;
            }
            for (Document li : inner) {
                AxonValue ik = fieldValue(li.current(), innerKey);
                if (!unspecified(ik) && ok.equals(ik)) {
                    out.add(flattenJoin(outerTable, lo, innerTable, li));
                }
            }
        }
        return out;
    }

    private static boolean unspecified(AxonValue v) {
        return v.isNone() || v.isNull();
    }

    private List<Document> dbDocs(Database db, com.axonbase.parser.ast.Expr source) {
        return sourceDocs(db, source);
    }

    /** Funde as dúas orixes nun obxecto achatado coas claves prefixadas pola táboa. */
    private Document flattenJoin(String outerTable, Document outer, String innerTable, Document inner) {
        Map<String, AxonValue> flat = new LinkedHashMap<>();
        if (outer.current().isObject()) {
            outer.current().asObject().forEach((k, v) -> flat.put(outerTable + "." + k, v));
        }
        if (inner.current().isObject()) {
            inner.current().asObject().forEach((k, v) -> flat.put(innerTable + "." + k, v));
        }
        return new Document(outer.id(), AxonValue.object(flat));
    }

    /** Nome do campo que casa no lado esquerdo/dereito do JOIN. */
    private static String lastFieldName(Expr field) {
        if (field instanceof Expr.Alias a) {
            return lastFieldName(a.expr());
        }
        if (field instanceof Expr.Idiom i) {
            String last = null;
            for (Expr.Part p : i.parts()) {
                if (p instanceof Expr.Part.Field f) {
                    last = f.name();
                }
            }
            return last != null ? last : "";
        }
        if (field instanceof Expr.Ident id) {
            return id.name();
        }
        return "";
    }

    /** Proxección sobre unha fila xa achatada: as claves prefixadas están en {@code táboa.campo}. */
    private AxonValue projectJoin(Statement.Select sel, Document doc) {
        if (sel.valueOnly() && !sel.fields().isEmpty()) {
            Expr first = sel.fields().get(0);
            return isStar(first) ? doc.current() : joinFlatValue(first, doc);
        }
        if (sel.fields().size() == 1 && isStar(sel.fields().get(0))) {
            return doc.current();
        }
        Map<String, AxonValue> m = new LinkedHashMap<>();
        for (Expr f : sel.fields()) {
            if (isStar(f)) {
                if (doc.current().isObject()) {
                    m.putAll(doc.current().asObject());
                }
                continue;
            }
            m.put(joinFieldName(f), joinFlatValue(f, doc));
        }
        return AxonValue.object(m);
    }

    private String joinFieldName(Expr f) {
        if (f instanceof Expr.Alias a) {
            return a.name();
        }
        if (f instanceof Expr.Idiom i) {
            return idiomToString(i);
        }
        if (f instanceof Expr.Ident id) {
            return id.name();
        }
        return "value";
    }

    private AxonValue joinFlatValue(Expr f, Document doc) {
        if (f instanceof Expr.Alias a) {
            return joinFlatValue(a.expr(), doc);
        }
        if (f instanceof Expr.Idiom i && i.base() instanceof Expr.Ident base
            && i.parts().size() == 1 && i.parts().get(0) instanceof Expr.Part.Field pf) {
            return fieldValue(doc.current(), base.name() + "." + pf.name());
        }
        if (f instanceof Expr.Ident id) {
            return doc.current().isObject() && id.name().equals("*")
                ? doc.current() : fieldValue(doc.current(), id.name());
        }
        return AxonValue.nul();
    }

/**
     * Para ORDER BY vector::distance::* usa o grafo HNSW multicamada. O índice
     * devolve um conjunto de candidatos via efSearch; a ordenação final sobre
     * esses candidatos continua exata no ORDER BY do SELECT.
     */
    private List<Document> vectorSourceDocs(Database db, Statement.Select sel, List<Document> fallback) {
        if (!(sel.from() instanceof Expr.Ident table) || sel.orders().isEmpty()
            || !(sel.orders().get(0).field() instanceof Expr.Call call)
            || !call.name().startsWith("vector::distance::") || call.args().isEmpty()
            || !(call.args().get(0) instanceof Expr.Ident field)) {
            return fallback;
        }
        Catalog.TableDef def = db.catalog().table(table.name());
        if (def == null) {
            return fallback;
        }
        Catalog.IndexDef index = def.indexes().values().stream()
            .filter(i -> i.vector() && i.columns().contains(field.name())).findFirst().orElse(null);
        if (index == null) {
            return fallback;
        }
        AxonValue query = call.args().size() > 1 ? eval(call.args().get(1)) : AxonValue.nul();
        if (!validVector(query, index.vectorDimension())) {
            return fallback;
        }
        if (maxLevel(db, table.name(), index) < 0) {
            return new ArrayList<>();
        }
        int wanted = sel.limit() == null ? 10 : Math.max(1, (int) eval(sel.limit()).asLong());
        int efSearch = Math.max(index.hnswEfs(), wanted * 8);
        Set<String> candidateIds = hnswCandidates(db, table.name(), index, query, efSearch);
        return docsFromKeys(db, table.name(), candidateIds);
    }

    /**
     * Busca best-first no HNSW multicamada. Desce do nível mais alto ao nível 0
     * seguindo o vizinho mais próximo em cada nível, e no nível 0 recolhe até
     * {@code efSearch} candidatos ordenados por distância.
     */
    private Set<String> hnswCandidates(Database db, String table, Catalog.IndexDef index,
                                       AxonValue query, int efSearch) {
        int top = maxLevel(db, table, index);
        String entry = entryAt(db, table, index.name(), top);
        if (entry == null) {
            return new LinkedHashSet<>();
        }
        for (int level = top; level > 0; level--) {
            List<Neighbor> near = searchLevel(db, table, index, level, entry, query, 1);
            if (!near.isEmpty()) {
                entry = near.get(0).key();
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (Neighbor candidate : searchLevel(db, table, index, 0, entry, query, Math.max(1, efSearch))) {
            out.add(candidate.key());
        }
        return out;
    }

    private List<Document> docsFromKeys(Database db, String table, Set<String> keys) {
        List<Document> docs = new ArrayList<>();
        for (String id : keys) {
            Document doc = loadDoc(db, new RecordId(table, parseKey(id)));
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    /** Maior nível com entrada persistida no grafo HNSW, ou -1 quando vazio. */
    private int maxLevel(Database db, String table, Catalog.IndexDef index) {
        String base = "VH|" + db.ns() + "|" + db.db() + "|" + table + "|" + index.name() + "|";
        int max = -1;
        for (String key : records(db).keysWithPrefix(base)) {
            String[] parts = key.split("\\|", -1);
            if (parts.length > 6 && parts[6].equals("@entry")) {
                try {
                    max = Math.max(max, Integer.parseInt(parts[5]));
                } catch (NumberFormatException ignored) {
                    // prefixo de outro índice não casaria aqui
                }
            }
        }
        return max;
    }

    /** Prefixo VH|...|<nível>| para um nível específico. */
    private String vhLevelPrefix(Database db, String table, String index, int level) {
        return "VH|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|" + level + "|";
    }

    /** Prefixo de arestas de um nó num nível: <code>VH|...|<nivel>|<key>|</code>. */
    private String vhNodePrefix(Database db, String table, String index, int level, String key) {
        return vhLevelPrefix(db, table, index, level) + key + "|";
    }

    /** Marcador de presença de um nó num nível: <code>VH|...|<nivel>|@N|<key></code>. */
    private String vhPresenceKey(Database db, String table, String index, int level, String key) {
        return vhLevelPrefix(db, table, index, level) + "@N|" + key;
    }

    /** Chave da entrada global do nível; o valor guarda o key do nó de entrada. */
    private String vhEntryKey(Database db, String table, String index, int level) {
        return vhLevelPrefix(db, table, index, level) + "@entry";
    }

    /** Entrada global do nível, com reparo de referência para nó removido. */
    private String entryAt(Database db, String table, String index, int level) {
        if (level < 0) {
            return null;
        }
        Optional<byte[]> raw = records(db).get(vhEntryKey(db, table, index, level));
        if (raw.isPresent()) {
            String key = new String(raw.get(), java.nio.charset.StandardCharsets.UTF_8);
            if (records(db).get(vhPresenceKey(db, table, index, level, key)).isPresent()) {
                return key;
            }
        }
        return repairEntry(db, table, index, level);
    }

    /**
     * Reposiciona a entrada de um nível para o primeiro nó com presença no nível,
     * apagando a entrada quando nenhum nó sobrevive.
     */
    private String repairEntry(Database db, String table, String index, int level) {
        String prefix = vhLevelPrefix(db, table, index, level);
        for (String key : records(db).keysWithPrefix(prefix)) {
            if (key.endsWith("|@entry")) {
                continue;
            }
            String body = key.substring(prefix.length());
            String node;
            if (body.startsWith("@N|")) {
                node = body.substring(3);
            } else {
                int bar = body.indexOf('|');
                node = bar < 0 ? body : body.substring(0, bar);
            }
            if (!node.isEmpty()) {
                records(db).put(vhEntryKey(db, table, index, level),
                    node.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return node;
            }
        }
        records(db).delete(vhEntryKey(db, table, index, level));
        return null;
    }

    /**
     * Busca greedy num nível: devolve até <code>ef</code> candidatos ordenados pela
     * menor distância, usando as arestas persistidas VH|...|<nivel>|<key>|<vizinho>.
     */
    private List<Neighbor> searchLevel(Database db, String table, Catalog.IndexDef index, int level,
                                       String entry, AxonValue query, int ef) {
        PriorityQueue<Neighbor> candidates = new PriorityQueue<>(
            Comparator.comparingDouble(Neighbor::distance));
        PriorityQueue<Neighbor> results = new PriorityQueue<>(
            java.util.Comparator.comparingDouble(Neighbor::distance).reversed());
        Set<String> visited = new HashSet<>();
        double start = vectorNodeDistance(db, table, index, entry, query);
        visited.add(entry);
        candidates.add(new Neighbor(entry, start));
        results.add(new Neighbor(entry, start));
        while (!candidates.isEmpty()) {
            Neighbor current = candidates.poll();
            Neighbor furthest = results.peek();
            if (results.size() >= ef && current.distance() > furthest.distance()) {
                break;
            }
            for (String edge : records(db).keysWithPrefix(
                vhNodePrefix(db, table, index.name(), level, current.key()))) {
                String next = edge.substring(edge.lastIndexOf('|') + 1);
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next);
                double distance = vectorNodeDistance(db, table, index, next, query);
                if (results.size() < ef || distance < results.peek().distance()) {
                    candidates.add(new Neighbor(next, distance));
                    results.add(new Neighbor(next, distance));
                    if (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }
        List<Neighbor> out = new ArrayList<>(results);
        out.sort(Comparator.comparingDouble(Neighbor::distance));
        return out;
    }

    private double vectorNodeDistance(Database db, String table, Catalog.IndexDef index, String key,
                                      AxonValue query) {
        Document doc = loadDoc(db, new RecordId(table, parseKey(key)));
        if (doc == null || !doc.current().isObject()) {
            return Double.MAX_VALUE;
        }
        AxonValue vector = doc.current().asObject().get(index.columns().get(0));
        return validVector(vector, index.vectorDimension())
            ? vectorDistance(index.vectorDistance(), vector, query) : Double.MAX_VALUE;
    }

    /** Usa a grade GEO para pré-filtrar uma distância radial antes do WHERE exato. */
    private List<Document> geoSourceDocs(Database db, Statement.Select sel, List<Document> fallback) {
        if (!(sel.from() instanceof Expr.Ident table) || !(sel.cond() instanceof Expr.Binary comparison)
            || (comparison.op() != Expr.BinaryOp.LT && comparison.op() != Expr.BinaryOp.LE)
            || !(comparison.left() instanceof Expr.Call distance)
            || !distance.name().equals("geo::distance") || distance.args().size() != 2
            || !(distance.args().get(0) instanceof Expr.Ident field)) {
            return fallback;
        }
        AxonValue center = eval(distance.args().get(1));
        AxonValue radius = eval(comparison.right());
        double[] point = geoPoint(center);
        if (point == null || !radius.isNumber()) {
            return fallback;
        }
        Catalog.TableDef def = db.catalog().table(table.name());
        if (def == null) {
            return fallback;
        }
        Catalog.IndexDef index = def.indexes().values().stream()
            .filter(i -> i.geo() && i.columns().contains(field.name())).findFirst().orElse(null);
        if (index == null) {
            return fallback;
        }
        double meters = radius.asDouble();
        double latRange = meters / 111_320d;
        double lonRange = meters / Math.max(1d, 111_320d * Math.cos(Math.toRadians(point[1])));
        int precision = geoPrecision(meters);
        java.util.Set<String> cells = GeoHash.covering(
            point[1] - latRange, point[0] - lonRange,
            point[1] + latRange, point[0] + lonRange, precision);
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        String base = geoIndexBase(db, table.name(), index.name());
        for (String cell : cells) {
            for (String key : records(db).keysWithPrefix(base + cell)) {
                ids.add(key.substring(key.lastIndexOf('|') + 1));
            }
        }
        List<Document> docs = new ArrayList<>();
        for (String id : ids) {
            Document doc = loadDoc(db, new RecordId(table.name(), parseKey(id)));
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    /** Precisão do geohash usado pelo índice espacial (≈5 km por célula). */
    private static final int GEO_PRECISION = 6;

    private int geoPrecision(double meters) {
        for (int p = GEO_PRECISION; p >= 0; p--) {
            double cellMeters = 111_320d * GeoHash.cellSizeDegrees(p);
            if (cellMeters * 2 >= meters) {
                return p;
            }
        }
        return 0;
    }

    /** Atualiza as células geohash de um documento após uma mutação geo. */
    private void cellsUpdate(Database db, RecordId rid, Catalog.IndexDef index, AxonValue value) {
        String base = geoIndexBase(db, rid.table(), index.name())
            + rid.key().keyString() + "|";
        for (String key : records(db).keysWithPrefix(base)) {
            records(db).delete(key);
        }
        for (String cell : geoCoverCells(value)) {
            records(db).put(geoIndexBase(db, rid.table(), index.name())
                + cell + "|" + rid.key().keyString(), new byte[0]);
        }
    }

    private String geoIndexBase(Database db, String table, String index) {
        return "SP|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|";
    }

    /** Células geohash que cobrem a geometria (ponto, linha ou polígono). */
    private java.util.Set<String> geoCoverCells(AxonValue geometry) {
        java.util.Set<String> cells = new java.util.LinkedHashSet<>();
        if (geometry == null || !geometry.isObject()) {
            return cells;
        }
        AxonValue coords = geometry.asObject().get("coordinates");
        if (coords == null || !coords.isArray()) {
            return cells;
        }
        for (double[] p : geoFlatten(coords)) {
            cells.add(GeoHash.encode(p[1], p[0], GEO_PRECISION));
        }
        double[] centroid = geoCentroid(coords);
        if (centroid != null) {
            cells.add(GeoHash.encode(centroid[1], centroid[0], GEO_PRECISION));
        }
        return cells;
    }

    /** Achata coordenadas de Point, LineString ou Polygon numa lista [lon, lat]. */
    private List<double[]> geoFlatten(AxonValue coords) {
        List<double[]> out = new ArrayList<>();
        if (!coords.isArray()) {
            return out;
        }
        if (coords.asArray().get(0).isNumber()) {
            double[] p = geoPoint(AxonValue.object(Map.of("coordinates", coords)));
            if (p != null) {
                out.add(p);
            }
            return out;
        }
        for (AxonValue child : coords.asArray()) {
            out.addAll(geoFlatten(child));
        }
        return out;
    }

    private double[] geoCentroid(AxonValue coords) {
        List<double[]> pts = geoFlatten(coords);
        if (pts.isEmpty()) {
            return null;
        }
        double x = 0, y = 0;
        for (double[] p : pts) {
            x += p[0];
            y += p[1];
        }
        return new double[] {x / pts.size(), y / pts.size()};
    }

    private double[] geoPoint(AxonValue geometry) {
        if (geometry == null || !geometry.isObject()) {
            return null;
        }
        AxonValue coords = geometry.asObject().get("coordinates");
        if (coords == null || !coords.isArray() || coords.asArray().size() < 2
            || !coords.asArray().get(0).isNumber() || !coords.asArray().get(1).isNumber()) {
            return null;
        }
        return new double[] { coords.asArray().get(0).asDouble(), coords.asArray().get(1).asDouble() };
    }

    /** Parámetros BM25 clásicos (Robertson–Walker). */
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;

    /** Estatísticas do índice invertido para o BM25: nº de docs e lonxitude media. */
    private record Bm25Stats(double totalDocs, double avgLength, Map<String, Integer> docFreq) {
    }

    /**
     * Calcula o ranking BM25 real con estatísticas persistidas: frecuencia de
     * termo (posting FT), frecuencia documental (FTDF) e lonxitude por doc (FTLEN).
     * Os termos da consulta saen do analyzer do índice, non de un split cru.
     */
    private void applySearchScores(Statement.Select sel, List<Document> docs) {
        if (!(sel.cond() instanceof Expr.Binary match) || match.op() != Expr.BinaryOp.MATCH
            || !(match.left() instanceof Expr.Ident field)) {
            return;
        }
        Catalog.IndexDef index = searchIndexFor(db(), tableName(sel.from()), sel);
        if (index == null) {
            return;
        }
        Catalog.AnalyzerDef analyzer = db().catalog().analyzer(index.searchAnalyzer());
        if (analyzer == null) {
            return;
        }
        AxonValue query = eval(match.right());
        if (!query.isString()) {
            return;
        }
        List<String> terms = analyze(query.asString(), analyzer).stream().distinct().toList();
        if (terms.isEmpty()) {
            return;
        }
        String table = tableName(sel.from());
        Bm25Stats stats = bm25Stats(db(), table, index);
        for (Document doc : docs) {
            doc.searchScore(bm25Score(db(), table, index, stats, terms, doc));
            doc.searchTerms(terms);
        }
    }

    /** Agrega o conxunto de FTLEN do índice: nº de docs e lonxitude media. */
    private Bm25Stats bm25Stats(Database db, String table, Catalog.IndexDef index) {
        long total = 0;
        double sum = 0;
        for (String key : records(db).keysWithPrefix(docLenPrefix(db, table, index.name()))) {
            total++;
            sum += intValue(records(db).get(key));
        }
        return new Bm25Stats(total, total > 0 ? sum / total : 0, new LinkedHashMap<>());
    }

    /**
     * BM25 dun documento polos termos da consulta: usa a frecuencia persistida
     * (posting), a frecuencia documental (FTDF) e a lonxitude do documento (FTLEN).
     */
    private double bm25Score(Database db, String table, Catalog.IndexDef index, Bm25Stats stats,
                             List<String> terms, Document doc) {
        int docLen = intValue(records(db).get(docLenKey(db, table, index.name(), doc.id())));
        if (docLen <= 0) {
            return 0;
        }
        double ratio = stats.avgLength() > 0 ? docLen / stats.avgLength() : 1;
        double score = 0;
        for (String term : terms) {
            int tf = intValue(records(db).get(
                searchKey(db, table, index.name(), term, doc.id())));
            if (tf <= 0) {
                continue;
            }
            int df = stats.docFreq().computeIfAbsent(term,
                k -> intValue(records(db).get(docFreqKey(db, table, index.name(), k))));
            double n = stats.totalDocs();
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            double denom = tf + BM25_K1 * (1 - BM25_B + BM25_B * ratio);
            score += idf * (tf * (BM25_K1 + 1)) / denom;
        }
return score;
    }

    // ------------------------------------------------------------------
    // Busca híbrida (FULLTEXT + VECTOR nun mesmo SELECT)
    // ------------------------------------------------------------------

    private record HybridSearch(Catalog.IndexDef searchIndex, Catalog.IndexDef vectorIndex,
                                String queryText, AxonValue queryVector) {
    }

    /**
     * Detecta unha consulta híbrida: un AND con un lado {@code campo @@ "termos"}
     * (índice de busca) e outro lado {@code vector::similarity::cosine(campo, vec)
     * &gt; limiar} (índice vectorial). Devolve {@code null} cando non é híbrida.
     */
    private HybridSearch hybridSearch(Statement.Select sel) {
        if (!(sel.from() instanceof Expr.Ident) || !(sel.cond() instanceof Expr.Binary and)
            || and.op() != Expr.BinaryOp.AND) {
            return null;
        }
        Expr.Binary searchSide = null;
        Expr.Binary vectorSide = null;
        if (isSearchMatch(and.left())) {
            searchSide = (Expr.Binary) and.left();
        } else if (isSearchMatch(and.right())) {
            searchSide = (Expr.Binary) and.right();
        }
        if (isVectorCond(and.left())) {
            vectorSide = (Expr.Binary) and.left();
        } else if (isVectorCond(and.right())) {
            vectorSide = (Expr.Binary) and.right();
        }
        if (searchSide == null || vectorSide == null || searchSide == vectorSide) {
            return null;
        }
        String searchField = ((Expr.Ident) searchSide.left()).name();
        AxonValue query = eval(searchSide.right());
        if (!query.isString()) {
            return null;
        }
        Expr.Call vc = (Expr.Call) vectorSide.left();
        String vectorField = ((Expr.Ident) vc.args().get(0)).name();
        AxonValue queryVector = eval(vc.args().get(1));
        Catalog.TableDef def = db().catalog().table(tableName(sel.from()));
        if (def == null) {
            return null;
        }
        Catalog.IndexDef searchIndex = def.indexes().values().stream()
            .filter(i -> i.search() && i.columns().contains(searchField)).findFirst().orElse(null);
        Catalog.IndexDef vectorIndex = def.indexes().values().stream()
            .filter(i -> i.vector() && i.columns().contains(vectorField)).findFirst().orElse(null);
        if (searchIndex == null || vectorIndex == null) {
            return null;
        }
        return new HybridSearch(searchIndex, vectorIndex, query.asString(), queryVector);
    }

    private boolean isSearchMatch(Expr e) {
        return e instanceof Expr.Binary b && b.op() == Expr.BinaryOp.MATCH
            && b.left() instanceof Expr.Ident;
    }

    private boolean isVectorCond(Expr e) {
        return e instanceof Expr.Binary b
            && (b.op() == Expr.BinaryOp.GT || b.op() == Expr.BinaryOp.GE)
            && b.left() instanceof Expr.Call c && !c.args().isEmpty()
            && c.name().startsWith("vector::similarity::")
            && c.args().get(0) instanceof Expr.Ident;
    }

    /**
     * Fonte híbrida: candidatos de postings FULLTEXT pontuados co BM25, e cada
     * un coa similaridade coseno do vector propia. Devolve os documentos co
     * {@code searchScore} igual ao score combinado (BM25 normalizado + similitud),
     * listos para o WHERE, a sort e o LIMIT do SELECT.
     */
    private List<Document> hybridSourceDocs(Database db, Statement.Select sel, HybridSearch hy) {
        Catalog.AnalyzerDef analyzer = db.catalog().analyzer(hy.searchIndex().searchAnalyzer());
        if (analyzer == null) {
            return List.of();
        }
        List<String> tokens = analyze(hy.queryText(), analyzer).stream().distinct().toList();
        if (tokens.isEmpty()) {
            return List.of();
        }
        String table = tableName(sel.from());
        java.util.Set<String> keys = null;
        for (String term : tokens) {
            java.util.Set<String> termKeys = records(db).keysWithPrefix(
                searchPrefix(db, table, hy.searchIndex().name(), term)).stream()
                .map(key -> key.substring(key.lastIndexOf('|') + 1))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (keys == null) {
                keys = termKeys;
            } else {
                keys.retainAll(termKeys);
            }
        }
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        String vectorColumn = hy.vectorIndex().columns().get(0);
        Bm25Stats stats = bm25Stats(db, table, hy.searchIndex());
        List<Document> docs = new ArrayList<>();
        Map<RecordId, Double> bm25 = new LinkedHashMap<>();
        for (String key : keys) {
            Document doc = loadDoc(db, new RecordId(table, parseKey(key)));
            if (doc == null) {
                continue;
            }
            docs.add(doc);
            bm25.put(doc.id(), bm25Score(db, table, hy.searchIndex(), stats, tokens, doc));
        }
        if (docs.isEmpty()) {
            return List.of();
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : bm25.values()) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        for (Document doc : docs) {
            double raw = bm25.get(doc.id());
            double normBm25 = max > min ? (raw - min) / (max - min) : 0.5;
            double similarity = vectorSimilarity(doc.current(), vectorColumn, hy.queryVector());
            doc.searchScore(normBm25 + similarity);
            doc.searchTerms(tokens);
        }
        return docs;
    }

    /** Similaridade do coseno [0,1] do campo do documento co vector de consulta. */
    private double vectorSimilarity(AxonValue record, String column, AxonValue queryVector) {
        if (!record.isObject() || queryVector == null || !arrayVector(queryVector)) {
            return 0;
        }
        AxonValue docVector = record.asObject().get(column);
        if (!arrayVector(docVector)) {
            return 0;
        }
        double sim = GeoVector.cosine(docVector, queryVector).asDouble();
        return Math.max(0, Math.min(1, sim));
    }

    private boolean arrayVector(AxonValue v) {
        return v != null && v.isArray() && v.asArray().stream().allMatch(AxonValue::isNumber);
    }

    /** Usa o índice invertido cando o WHERE é {@code campo @@ "termos"}. */
    private List<Document> searchSourceDocs(Database db, Statement.Select sel) {
        if (!(sel.from() instanceof Expr.Ident table) || !(sel.cond() instanceof Expr.Binary match)
            || match.op() != Expr.BinaryOp.MATCH || !(match.left() instanceof Expr.Ident field)) {
            return sourceDocs(db, sel.from());
        }
        Catalog.TableDef def = db.catalog().table(table.name());
        if (def == null) {
            return new ArrayList<>();
        }
        Catalog.IndexDef index = def.indexes().values().stream()
            .filter(i -> i.search() && i.columns().contains(field.name())).findFirst().orElse(null);
        if (index == null) {
            return sourceDocs(db, sel.from());
        }
        AxonValue query = eval(match.right());
        if (!query.isString()) {
            return new ArrayList<>();
        }
        Catalog.AnalyzerDef analyzer = db.catalog().analyzer(index.searchAnalyzer());
        if (analyzer == null) {
            return new ArrayList<>();
        }
        java.util.Set<String> keys = null;
        for (String term : analyze(query.asString(), analyzer)) {
            java.util.Set<String> termKeys = records(db).keysWithPrefix(
                searchPrefix(db, table.name(), index.name(), term)).stream()
                .map(key -> key.substring(key.lastIndexOf('|') + 1))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (keys == null) {
                keys = termKeys;
            } else {
                keys.retainAll(termKeys);
            }
        }
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> docs = new ArrayList<>();
        for (String key : keys) {
            Document doc = loadDoc(db, new RecordId(table.name(), parseKey(key)));
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    /**
     * Documentos de origem de um SELECT. Além de tabelas e record ids, o FROM
     * aceita uma subquery ou um array: cada objeto do resultado vira um
     * documento sintético, e os valores nus entram no campo {@code value}.
     */
    private List<Document> sourceDocs(Database db, Expr from) {
        if (from instanceof Expr.SubQuery || from instanceof Expr.ArrayLit) {
            return virtualDocs(eval(from));
        }
        // DATABASE LINK or local qualified database (table reference)
        if (from instanceof Expr.Qualified q) {
            return sourceDocsQualified(q);
        }
        // Record ID with qualified table: "link"."table":key
        if (from instanceof Expr.RecordId rid && rid.table() instanceof Expr.Qualified q) {
            return sourceDocsQualifiedRecordId(q, rid);
        }
        List<Document> docs = new ArrayList<>();
        for (RecordId rid2 : resolveTargets(from)) {
            Document doc = loadDoc(db, rid2);
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    /**
     * Delegates an entire SELECT query to a remote server via DATABASE LINK.
     * Strips the link qualifier from the FROM clause and sends the rendered SQL.
     */
    private AxonValue runSelectViaLink(Datastore.DatabaseLinkDef linkDef, Expr.Qualified q,
                                        Statement.Select sel) {
        // Render the SELECT, then replace qualified FROM with unqualified one
        String rendered = com.axonbase.parser.Render.stmt(sel);
        String qualifiedFrom = q.link() + "." + q.table();
        String unqualifiedFrom = q.table();
        String axonql = rendered.replace(qualifiedFrom, unqualifiedFrom);
        // Fix internal AST rendering: *->expr should be just ->expr
        axonql = axonql.replace("SELECT *->", "SELECT ->");
        axonql = axonql.replace("VALUE *->", "VALUE ->");
        try {
            List<AxonValue> rows = linkClient.query(linkDef, axonql);
            if (rows.isEmpty()) return AxonValue.array(List.of());
            if (rows.size() == 1 && !rows.get(0).isObject() && !rows.get(0).isArray()) {
                return rows.get(0);
            }
            return AxonValue.array(rows);
        } catch (Exception e) {
            throw errorStmt(Messages.get("stmt_database_link_error", e.getMessage()));
        }
    }
    private List<Document> sourceDocsQualified(Expr.Qualified q) {
        String ns = session.namespace();
        if (ns == null || ns.isBlank()) {
            throw errorStmt(Messages.get("stmt_database_link_namespace"));
        }
        // Check DATABASE LINK registry first
        Datastore.DatabaseLinkDef linkDef = ds.databaseLink(ns, q.link());
        if (linkDef != null) {
            String axonql = "SELECT * FROM " + q.table();
            List<AxonValue> rows = linkClient.query(linkDef, axonql);
            return rowsToDocs(rows, q.table());
        }
        // Local database (other db in same ns)
        Database targetDb = ds.ensureDatabase(session, ns, q.link());
        String tableName = q.table();
        ensureTable(targetDb, tableName);
        String prefix = targetDb.ns() + "\u0000" + targetDb.db() + "\u0000" + tableName + "\u0000";
        List<Document> docs = new ArrayList<>();
        for (String k : records(targetDb).keysWithPrefix(prefix)) {
            String keyPart = k.substring(prefix.length());
            RecordId rid = new RecordId(tableName, parseKey(keyPart));
            Document doc = loadDoc(targetDb, rid);
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    /**
     * Roteia uma escrita qualificada ({@code CREATE/UPDATE/DELETE/RELATE "link"."table"})
     * para o servidor remoto via DATABASE LINK. A sentença renderizada tem o prefixo
     * do link removido e é enviada ao endpoint {@code /sql} do link.
     */
    private AxonValue runWriteViaLink(Expr.Qualified q, String renderedSql) {
        String ns = session.namespace();
        if (ns == null || ns.isBlank()) {
            throw errorStmt(Messages.get("stmt_database_link_namespace"));
        }
        Datastore.DatabaseLinkDef linkDef = ds.databaseLink(ns, q.link());
        if (linkDef == null) {
            throw errorStmt(Messages.get("stmt_database_link_missing", q.link()));
        }
        String labeled = q.link() + "." + q.table();
        String axonql = renderedSql.replace(labeled, q.table());
        try {
            List<AxonValue> rows = linkClient.query(linkDef, axonql);
            if (rows.isEmpty()) return AxonValue.array(List.of());
            if (rows.size() == 1 && !rows.get(0).isObject() && !rows.get(0).isArray()) {
                return rows.get(0);
            }
            return AxonValue.array(rows);
        } catch (Exception e) {
            throw errorStmt(Messages.get("stmt_database_link_error", e.getMessage()));
        }
    }

    /** Resolve a qualified record ID {@code "link"."table":key}. */
    private List<Document> sourceDocsQualifiedRecordId(Expr.Qualified q, Expr.RecordId rid) {
        String ns = session.namespace();
        if (ns == null || ns.isBlank()) {
            throw errorStmt(Messages.get("stmt_database_link_namespace"));
        }
        Datastore.DatabaseLinkDef linkDef = ds.databaseLink(ns, q.link());
        if (linkDef != null) {
            String keyStr = com.axonbase.parser.Render.expr(rid.key());
            String axonql = "SELECT * FROM " + q.table() + ":" + keyStr;
            List<AxonValue> rows = linkClient.query(linkDef, axonql);
            return rowsToDocs(rows, q.table());
        }
        // Local database
        Database targetDb = ds.ensureDatabase(session, ns, q.link());
        RecordId targetRid = configRecordId(rid);
        Document doc = loadDoc(targetDb, targetRid);
        return doc != null ? List.of(doc) : List.of();
    }

    private List<Document> rowsToDocs(List<AxonValue> rows, String tableName) {
        List<Document> docs = new ArrayList<>();
        for (AxonValue row : rows) {
            if (row.isObject()) {
                AxonValue rowId = row.asObject().get("id");
                RecordId recordId = rowId != null && rowId.isRecordId()
                    ? new RecordId(rowId.asRecordId().table(), keyOf(rowId.asRecordId().key()))
                    : new RecordId(tableName, AxonValue.str(""));
                docs.add(new Document(recordId, row));
            }
        }
        return docs;
    }

    // ------------------------------------------------------------------
    // Plan de execución (EXPLAIN)
    // ------------------------------------------------------------------

    private static final String PS_SCAN = "FULL_SCAN";
    private static final String PS_UNIQUE = "UNIQUE_LOOKUP";
    private static final String PS_FULLTEXT = "FULLTEXT";
    private static final String PS_GEO = "GEO";
    private static final String PS_VECTOR = "VECTOR";
    private static final String PS_HYBRID = "HYBRID";
    private static final String PS_COLUMNAR = "COLUMNAR";

    private record ExplainPlan(String strategy, Catalog.IndexDef index, String table) {
    }

    /**
     * EXPLAIN devolve un object determinista co plan: strategy, table, indexName
     * (se hai) e examinedRows. Sen ANALYZE as filas son unha estimación e o SELECT
     * non se executa; con ANALYZE execútase a mesma fonte elixida e contánse as
     * filas reais que pasan pola etapa de orixe.
     */
    private AxonValue runExplain(Statement.Explain ex) {
        Database db = db();
        Statement.Select sel = ex.select();
        ExplainPlan plan = planExplain(db, sel);
        Map<String, AxonValue> info = new LinkedHashMap<>();
        info.put("strategy", AxonValue.str(plan.strategy()));
        if (plan.table() != null && !plan.table().isBlank()) {
            info.put("table", AxonValue.str(plan.table()));
        }
        if (plan.index() != null) {
            info.put("indexName", AxonValue.str(plan.index().name()));
        }
        long rows = ex.analyze() ? explainedRows(db, sel, plan) : estimatedRows(db, sel, plan);
        info.put("examinedRows", AxonValue.num(rows));
        return AxonValue.object(info);
    }

    /**
     * Elixe a estratexia na mesma orde que a cadea real de orixes: única,
     * vector, geo, full-text e por último varrido completo.
     */
    private ExplainPlan planExplain(Database db, Statement.Select sel) {
        String table = tableName(sel.from());
        Catalog.IndexDef ix = uniqueIndexFor(db, table, sel);
        if (ix != null) {
            return new ExplainPlan(PS_UNIQUE, ix, table);
        }
        HybridSearch hy = hybridSearch(sel);
        if (hy != null) {
            return new ExplainPlan(PS_HYBRID, hy.searchIndex(), table);
        }
        ix = columnarIndexFor(db, table, sel);
        if (ix != null) {
            return new ExplainPlan(PS_COLUMNAR, ix, table);
        }
        ix = vectorIndexFor(db, table, sel);
        if (ix != null) {
            return new ExplainPlan(PS_VECTOR, ix, table);
        }
        ix = geoIndexFor(db, table, sel);
        if (ix != null) {
            return new ExplainPlan(PS_GEO, ix, table);
        }
        ix = searchIndexFor(db, table, sel);
        if (ix != null) {
            return new ExplainPlan(PS_FULLTEXT, ix, table);
        }
        return new ExplainPlan(PS_SCAN, null, table);
    }

    /** Igualdade `campo = literal` sobre unha columna con índice único. */
    private Catalog.IndexDef uniqueIndexFor(Database db, String table, Statement.Select sel) {
        if (table.isBlank() || !(sel.cond() instanceof Expr.Binary eq)
            || eq.op() != Expr.BinaryOp.EQ || !(eq.left() instanceof Expr.Ident field)
            || !(eq.right() instanceof Expr.Literal)) {
            return null;
        }
        Catalog.TableDef def = db.catalog().table(table);
        if (def == null) {
            return null;
        }
        return def.indexes().values().stream()
            .filter(i -> i.unique() && i.columns().size() == 1 && i.columns().contains(field.name()))
            .findFirst().orElse(null);
    }

    /** ORDER BY vector::distance::&lt;campo&gt;: usa o índice vetorial do campo. */
    private Catalog.IndexDef vectorIndexFor(Database db, String table, Statement.Select sel) {
        if (table.isBlank() || sel.orders().isEmpty()
            || !(sel.orders().get(0).field() instanceof Expr.Call call)
            || !call.name().startsWith("vector::distance::") || call.args().isEmpty()
            || !(call.args().get(0) instanceof Expr.Ident field)) {
            return null;
        }
        Catalog.TableDef def = db.catalog().table(table);
        if (def == null) {
            return null;
        }
        return def.indexes().values().stream()
            .filter(i -> i.vector() && i.columns().contains(field.name())).findFirst().orElse(null);
    }

    /** `geo::distance(campo, centro) &lt; radio`: índice GEO do campo. */
    private Catalog.IndexDef geoIndexFor(Database db, String table, Statement.Select sel) {
        if (table.isBlank() || !(sel.cond() instanceof Expr.Binary comparison)
            || (comparison.op() != Expr.BinaryOp.LT && comparison.op() != Expr.BinaryOp.LE)
            || !(comparison.left() instanceof Expr.Call distance)
            || !distance.name().equals("geo::distance") || distance.args().size() != 2
            || !(distance.args().get(0) instanceof Expr.Ident field)) {
            return null;
        }
        Catalog.TableDef def = db.catalog().table(table);
        if (def == null) {
            return null;
        }
        return def.indexes().values().stream()
            .filter(i -> i.geo() && i.columns().contains(field.name())).findFirst().orElse(null);
    }

    /** `campo @@ "termos"` con índice SEARCH do campo. */
    private Catalog.IndexDef searchIndexFor(Database db, String table, Statement.Select sel) {
        if (table.isBlank() || !(sel.cond() instanceof Expr.Binary match)
            || match.op() != Expr.BinaryOp.MATCH || !(match.left() instanceof Expr.Ident field)) {
            return null;
        }
        Catalog.TableDef def = db.catalog().table(table);
        if (def == null) {
            return null;
        }
        return def.indexes().values().stream()
            .filter(i -> i.search() && i.columns().contains(field.name())).findFirst().orElse(null);
    }

    /**
     * Carrega documentos do ledger histórico no timestamp dado.
     */
    private List<Document> historicalDocs(Database db, Statement.Select sel, long timestamp) {
        String table = tableName(sel.from());
        if (table == null) {
            throw errorStmt(Messages.get("stmt_no_table"));
        }
        KvBackend bk = records(db);
        if (!(bk instanceof VersionedKvBackend vbk)) {
            throw errorStmt(Messages.get("stmt_no_wal"));
        }
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
        List<String> keys = bk.keysWithPrefix(prefix);
        List<Document> docs = new ArrayList<>();
        for (String key : keys) {
            // Primeiro tenta o ledger histórico
            Optional<byte[]> raw = vbk.snapshotAt(key, timestamp);
            if (raw.isEmpty()) {
                // Sem entrada no ledger: tenta o valor atual (registro nunca modificado)
                raw = bk.get(key);
            }
            if (raw.isEmpty()) continue;
            AxonValue record = decode(raw.get());
            if (!record.isObject()) continue;
            String keyPart = key.substring(prefix.length());
            RecordId rid = new RecordId(table, parseKey(keyPart));
            docs.add(new Document(rid, record));
        }
        return docs;
    }

    private List<Document> fullScanDocs(Database db, Statement.Select sel) {
        return sourceDocs(db, sel.from());
    }

    private long estimatedRows(Database db, Statement.Select sel, ExplainPlan plan) {
        // Unha igualdade única devolve como moito unha fila; o resto estima o
        // custo como o varrido completo da táboa.
        if (PS_UNIQUE.equals(plan.strategy())) {
            return 1;
        }
        return fullScanDocs(db, sel).size();
    }

    private long explainedRows(Database db, Statement.Select sel, ExplainPlan plan) {
        List<Document> docs = switch (plan.strategy()) {
            case PS_UNIQUE -> loadUniqueDocs(db, sel, plan.index());
            case PS_FULLTEXT -> searchSourceDocs(db, sel);
            case PS_GEO -> geoSourceDocs(db, sel, fullScanDocs(db, sel));
            case PS_VECTOR -> vectorSourceDocs(db, sel, fullScanDocs(db, sel));
            case PS_HYBRID -> hybridSourceDocs(db, sel, hybridSearch(sel));
            case PS_COLUMNAR -> columnarSourceDocs(db, sel);
            default -> fullScanDocs(db, sel);
        };
        return docs.size();
    }

    /**
     * Estratexia de índice único: igualdade `campo = literal` con UNIQUE en
     * `campo` consulta o prefixo {@code !uidx} directo, sen varrido completo.
     */
    private List<Document> uniqueLookupSourceDocs(Database db, Statement.Select sel,
                                                  List<Document> fallback) {
        Catalog.IndexDef index = uniqueIndexFor(db, tableName(sel.from()), sel);
        if (index == null) {
            return fallback;
        }
        return loadUniqueDocs(db, sel, index);
    }

    private List<Document> loadUniqueDocs(Database db, Statement.Select sel, Catalog.IndexDef index) {
        Expr.Binary eq = (Expr.Binary) sel.cond();
        String field = eq.left() instanceof Expr.Ident id ? id.name() : "";
        AxonValue value = eval(eq.right());
        if (value == null || value.isNull() || value.isNone()) {
            return List.of();
        }
        String table = tableName(sel.from());
        AxonValue probe = AxonValue.object(Map.of(field, value));
        String prefix = uniqueIndexPrefix(db, table, index, probe);
        if (prefix == null) {
            return List.of();
        }
        List<Document> docs = new ArrayList<>();
        for (String key : records(db).keysWithPrefix(prefix)) {
            String keyPart = key.substring(prefix.length());
            Document doc = loadDoc(db, new RecordId(table, parseKey(keyPart)));
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    /** Converte um valor (array ou objeto) em documentos sem armazenamento. */
    private List<Document> virtualDocs(AxonValue value) {
        List<AxonValue> rows = value.isArray() ? value.asArray()
            : value.isSet() ? value.asSet() : List.of(value);
        List<Document> docs = new ArrayList<>();
        int i = 0;
        for (AxonValue row : rows) {
            AxonValue body = row.isObject() ? row
                : AxonValue.object(Map.of("value", row));
            docs.add(new Document(new RecordId(VIRTUAL_TABLE, AxonValue.num(i++)), body));
        }
        return docs;
    }

    private boolean isAggregateCall(Expr f) {
        if (f instanceof Expr.Alias a) {
            return isAggregateCall(a.expr());
        }
        if (f instanceof Expr.Call c) {
            String n = c.name();
            return n.equals("count") || n.equals("sum") || n.equals("avg")
                || n.equals("min") || n.equals("max");
        }
        return false;
    }

    private java.util.List<AxonValue> aggregate(Statement.Select sel, java.util.List<Document> docs) {
        java.util.List<AxonValue> result = new java.util.ArrayList<>();
        if (sel.group() != null && !sel.group().isEmpty()) {
            // agrupar por valor das expr de group
            java.util.Map<String, java.util.List<Document>> groups = new java.util.LinkedHashMap<>();
            for (Document d : docs) {
                StringBuilder key = new StringBuilder();
                for (Expr ge : sel.group()) {
                    key.append(evalField(ge, d)).append('');
                }
                groups.computeIfAbsent(key.toString(), k -> new java.util.ArrayList<>()).add(d);
            }
            for (var e : groups.entrySet()) {
                java.util.List<Document> groupDocs = e.getValue();
                java.util.Map<String, AxonValue> row = new LinkedHashMap<>();
                // campos de group
                java.util.List<Expr> groupExprs = sel.group();
                for (int i = 0; i < groupExprs.size(); i++) {
                    Document rep = groupDocs.get(0);
                    row.put("group." + i, evalField(groupExprs.get(i), rep));
                }
                for (Expr f : sel.fields()) {
                    if (isAggregateCall(f)) {
                        Expr unwrapped = f instanceof Expr.Alias a ? a.expr() : f;
                        Expr.Call c = (Expr.Call) unwrapped;
                        String n = c.name();
                        Expr arg = c.args().isEmpty() ? null : c.args().get(0);
                        row.put(fieldName(f), evalAggregate(n, arg, groupDocs));
                    }
                }
                result.add(AxonValue.object(row));
            }
            return result;
        }
        // sem group: agregar sobre todos os docs num só campo (count(*) padrão)
        Map<String, AxonValue> row = new LinkedHashMap<>();
        for (Expr f : sel.fields()) {
            if (isAggregateCall(f)) {
                Expr unwrapped = f instanceof Expr.Alias a ? a.expr() : f;
                Expr.Call c = (Expr.Call) unwrapped;
                Expr arg = c.args().isEmpty() ? null : c.args().get(0);
                row.put(fieldName(f), evalAggregate(c.name(), arg, docs));
            }
        }
        result.add(AxonValue.object(row));
        return result;
    }

    private boolean AggregateExpr(Expr f) {
        return isAggregateCall(f);
    }

    private AxonValue evalAggregate(String name, Expr arg, java.util.List<Document> docs) {
        java.util.List<AxonValue> values = new java.util.ArrayList<>();
        for (Document d : docs) {
            AxonValue v = arg == null ? AxonValue.num(1) : evalField(arg, d);
            if (name.equals("count")) {
                values.add(AxonValue.num(1));
            } else {
                values.add(v);
            }
        }
        switch (name) {
            case "count" -> {
                long c = 0;
                for (AxonValue v : values) {
                    if (v.isNumber()) {
                        c += v.asLong();
                    } else if (!v.isNull() && !v.isNone()) {
                        c += 1;
                    }
                }
                return AxonValue.num(c);
            }
            case "sum" -> {
                java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                for (AxonValue v : values) {
                    if (v.isNumber()) {
                        sum = sum.add(v.asDecimal());
                    }
                }
                return AxonValue.num(sum);
            }
            case "avg" -> {
                java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                long count = 0;
                for (AxonValue v : values) {
                    if (v.isNumber()) {
                        sum = sum.add(v.asDecimal());
                        count++;
                    }
                }
                return count == 0 ? AxonValue.nul() : AxonValue.num(sum.divide(java.math.BigDecimal.valueOf(count)));
            }
            case "min" -> {
                AxonValue best = null;
                for (AxonValue v : values) {
                    if (!v.isNull() && !v.isNone() && (best == null || v.compareTo(best) < 0)) {
                        best = v;
                    }
                }
                return best == null ? AxonValue.nul() : best;
            }
            case "max" -> {
                AxonValue best = null;
                for (AxonValue v : values) {
                    if (!v.isNull() && !v.isNone() && (best == null || v.compareTo(best) > 0)) {
                        best = v;
                    }
                }
                return best == null ? AxonValue.nul() : best;
            }
            default -> {
                return AxonValue.nul();
            }
        }
    }

    private void fetchDocs(Document d, java.util.List<String> fields) {
        for (String fname : fields) {
            AxonValue cur = fieldValue(d.current(), fname);
            if (cur != null && cur.isRecordId()) {
                AxonValue filled = fetchOne(cur.asRecordId());
                if (filled != null) {
                    java.util.Map<String, AxonValue> map = new LinkedHashMap<>(d.current().asObject());
                    map.put(fname, filled);
                    d.setCurrent(AxonValue.object(map));
                }
            } else if (cur != null && cur.isArray()) {
                java.util.List<AxonValue> filled = new java.util.ArrayList<>();
                boolean any = false;
                for (AxonValue it : cur.asArray()) {
                    if (it.isRecordId()) {
                        AxonValue f = fetchOne(it.asRecordId());
                        filled.add(f != null ? f : it);
                        any = true;
                    } else {
                        filled.add(it);
                    }
                }
                if (any) {
                    java.util.Map<String, AxonValue> map = new LinkedHashMap<>(d.current().asObject());
                    map.put(fname, AxonValue.array(filled));
                    d.setCurrent(AxonValue.object(map));
                }
            }
        }
    }

    private AxonValue fetchOne(AxonValue.RecordId rid) {
        try {
            Document doc = loadDoc(db(), new RecordId(rid.table(), rid.key() instanceof AxonValue ? (AxonValue) rid.key() : keyOf(rid.key())));
            return doc != null ? doc.current() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSingleTarget(Expr from) {
        return from instanceof Expr.RecordId;
    }

    private Comparator<Document> orderComparator(List<OrderTerm> orders) {
        return (a, b) -> {
            for (OrderTerm ot : orders) {
                int c = evalField(ot.field(), a).compareTo(evalField(ot.field(), b));
                if (c != 0) {
                    return ot.descending() ? -c : c;
                }
            }
            return 0;
        };
    }

    private AxonValue project(Statement.Select sel, Document doc) {
        // SELECT VALUE devolve o valor nu do primeiro campo
        if (sel.valueOnly() && !sel.fields().isEmpty()) {
            Expr first = sel.fields().get(0);
            return isStar(first) ? doc.current() : evalField(first, doc);
        }
        if (sel.fields().size() == 1 && isStar(sel.fields().get(0))) {
            return doc.current();
        }
        Map<String, AxonValue> m = new LinkedHashMap<>();
        for (Expr f : sel.fields()) {
            if (isStar(f)) {
                // '*' misturado com outros campos expande o documento inteiro
                if (doc.current().isObject()) {
                    m.putAll(doc.current().asObject());
                }
                continue;
            }
            m.put(fieldName(f), evalField(f, doc));
        }
        return AxonValue.object(m);
    }

    private boolean isStar(Expr e) {
        return e instanceof Expr.Ident id && id.name().equals("*");
    }

    private String fieldName(Expr e) {
        if (e instanceof Expr.Alias a) {
            return a.name();
        }
        if (e instanceof Expr.Ident id) {
            return id.name();
        }
        if (e instanceof Expr.Idiom i) {
            return idiomToString(i);
        }
        if (e instanceof Expr.Call c) {
            return c.name();
        }
        return "value";
    }

    // ------------------------------------------------------------------
    // Relate
    // ------------------------------------------------------------------

    private AxonValue runRelate(Statement.Relate r) {
        Database db = db();
        // RELATE qualificado via DATABASE LINK: RELATE "link"."table":1 ->knows-> "link"."table":2
        Expr rFrom = r.from();
        Expr rTo = r.to();
        Expr.Qualified rq = (rFrom instanceof Expr.RecordId rr && rr.table() instanceof Expr.Qualified q) ? q : null;
        if (rq != null || rFrom instanceof Expr.Qualified || rTo instanceof Expr.Qualified
            || (rTo instanceof Expr.RecordId tr && tr.table() instanceof Expr.Qualified)) {
            return runWriteViaLink(rq != null ? rq : (Expr.Qualified) qualifiedOf(rFrom, rTo),
                com.axonbase.parser.Render.stmt(r));
        }
        AxonValue from = mapping(r.from());
        AxonValue to = mapping(r.to());
        String kind = tableOf(r.kind());
        if (from.isRecordId() && to.isRecordId()) {
            AxonValue.RecordId fromR = from.asRecordId();
            AxonValue.RecordId toR = to.asRecordId();
            persistEdge(db, fromR, kind, toR);
        }
        Map<String, AxonValue> edge = new LinkedHashMap<>();
        edge.put("in", from);
        edge.put("out", to);
        if (r.data() instanceof Data.SetClause sc) {
            for (Assignment a : sc.assignments()) {
                edge.put(fieldOf(a.path()), evalExpr(a.value()));
            }
        }
        return AxonValue.object(edge);
    }

    private AxonValue mapping(Expr e) {
        if (e instanceof Expr.RecordId rid) {
            return ridAsValue(configRecordId(rid));
        }
        return eval(e);
    }

    /** Recupera o qualificador usado entre FROM e TO de RELATE. */
    private Expr qualifiedOf(Expr from, Expr to) {
        if (from instanceof Expr.RecordId rr && rr.table() instanceof Expr.Qualified q) {
            return q;
        }
        if (from instanceof Expr.Qualified q) {
            return q;
        }
        if (to instanceof Expr.RecordId tr && tr.table() instanceof Expr.Qualified q2) {
            return q2;
        }
        return to instanceof Expr.Qualified q3 ? q3 : null;
    }

    // ------------------------------------------------------------------
    // Define
    // ------------------------------------------------------------------

    private AxonValue runDefineTable(DefineTable dt) {
        Database db = db();
        Catalog.TableDef previous = db.catalog().table(dt.name());
        Catalog.TableDef def = new Catalog.TableDef(dt.name(), dt.schemafull(), dt.drop());
        // Redefinir a tabela atualiza as opções, sem descartar o catálogo já criado.
        if (previous != null) {
            def.fields().putAll(previous.fields());
            def.indexes().putAll(previous.indexes());
            def.events().putAll(previous.events());
        }
        def.permissions(dt.permissions());
        db.catalog().defineTable(def);
        control(db, ControlCommand.Kind.TABLE, dt.name(), com.axonbase.parser.Render.stmt(dt));
        return AxonValue.nul();
    }

    private AxonValue runCreateTable(Statement.CreateTable ct) {
        Database db = db();
        if (ct.ifNotExists() && db.catalog().table(ct.name()) != null) {
            return AxonValue.nul();
        }
        Catalog.TableDef previous = db.catalog().table(ct.name());
        Catalog.TableDef def = new Catalog.TableDef(ct.name(), ct.schemafull(), false);
        if (previous != null) {
            def.fields().putAll(previous.fields());
            def.indexes().putAll(previous.indexes());
            def.events().putAll(previous.events());
        }
        db.catalog().defineTable(def);

        for (Statement.ColumnDef col : ct.columns()) {
            if ("id".equalsIgnoreCase(col.name())) {
                continue;
            }
            Catalog.FieldDef fd = new Catalog.FieldDef(
                col.name(),
                col.type(),
                false,
                col.checkExpr(),
                col.defaultExpr(),
                col.references()
            );
            def.fields().put(col.name(), fd);
        }

        control(db, ControlCommand.Kind.TABLE, ct.name(), com.axonbase.parser.Render.stmt(ct));
        return AxonValue.nul();
    }

    private AxonValue runDefineField(DefineField df) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, df.table());
        def.fields().put(df.name(),
            new Catalog.FieldDef(df.name(), df.type(), df.readonly(), df.assertExpr(), df.defaultExpr(),
                df.references()));
        control(db, ControlCommand.Kind.FIELD, df.table() + ":" + df.name(),
            com.axonbase.parser.Render.stmt(df));
        return AxonValue.nul();
    }

    private AxonValue runDefineIndex(DefineIndex di) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, di.table());
        if (di.searchAnalyzer() != null && db.catalog().analyzer(di.searchAnalyzer()) == null) {
            throw errorStmt(Messages.get("stmt_analyzer_missing", di.searchAnalyzer()));
        }
        def.indexes().put(di.name(), new Catalog.IndexDef(di.name(), di.columns(), di.unique(), di.count(),
            di.searchAnalyzer(), di.geo(), di.columnar(), di.vectorDimension(), di.vectorDistance(),
            di.m(), di.efConstruction(), di.efSearch()));
        // índices podem ser criados depois dos registros: indexa o estado atual
        if (di.searchAnalyzer() != null || di.geo() || di.columnar() || di.vectorDimension() != null) {
            for (RecordId rid : resolveTargets(new Expr.Ident(di.table()))) {
                Document doc = loadDoc(db, rid);
                if (doc != null) {
                    indexSearch(db, rid, null, doc.current());
                    indexAdvanced(db, rid, null, doc.current());
                    indexColumnar(db, rid, null, doc.current());
                }
            }
        }
        control(db, ControlCommand.Kind.INDEX, di.table() + ":" + di.name(),
            com.axonbase.parser.Render.stmt(di));
        return AxonValue.nul();
    }

    private AxonValue runDefineAnalyzer(Statement.DefineAnalyzer da) {
        Database db = db();
        db.catalog().defineAnalyzer(new Catalog.AnalyzerDef(da.name(), da.lowercase(),
            da.stopwords(), da.stemming()));
        control(db, ControlCommand.Kind.ANALYZER, da.name(), com.axonbase.parser.Render.stmt(da));
        return AxonValue.nul();
    }

    private AxonValue runDefineEvent(DefineEvent de) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, de.table());
        String when = de.when() == null ? "" : com.axonbase.parser.Render.expr(de.when());
        def.events().put(de.name(), new Catalog.EventDef(de.name(), when, de.when(), de.then()));
        control(db, ControlCommand.Kind.EVENT, de.table() + ":" + de.name(),
            com.axonbase.parser.Render.stmt(de));
        return AxonValue.nul();
    }

    /**
     * Define uma identidade que o servidor pode autenticar por signin.
     *
     * <p>A senha é hasheada aqui e o comando de controle guarda o texto AxonQL na
     * forma {@code PASSHASH "salt:hash"}. Assim o replay e a réplica reconstroem a
     * identidade sem conhecer a senha e sem rehashear, que é justamente onde uma
     * credencial se perderia.</p>
     */
    private AxonValue runDefineUser(Statement.DefineUser du) {
        String ns = du.scope() == Statement.AuthScope.ROOT ? null
            : du.namespace() != null ? du.namespace() : session.namespace();
        String database = du.scope() == Statement.AuthScope.DATABASE
            ? du.database() != null ? du.database() : session.database() : null;
        if (du.scope() != Statement.AuthScope.ROOT && (ns == null || ns.isBlank())) {
            throw errorStmt(Messages.get("rpc_needs_ns"));
        }
        if (du.scope() == Statement.AuthScope.DATABASE && (database == null || database.isBlank())) {
            throw errorStmt(Messages.get("rpc_needs_db"));
        }
        AuthCatalog.User stored;
        if (du.hashed()) {
            AuthCodec.Credential credential = AuthCodec.parse(du.passhash());
            stored = new AuthCatalog.User(du.name(), authScope(du.scope()), ns, database,
                credential.saltHex(), credential.hashHex(), null, null, du.roles(), du.dataRules(), du.auditName());
            ds.authCatalog().restoreUser(stored);
        } else if (du.certificateBased()) {
            stored = ds.authCatalog().defineCertificateUser(du.name(), authScope(du.scope()), ns, database,
                du.certificate(), du.fingerprint(), du.roles(), du.dataRules(), du.auditName());
        } else {
            AxonValue password = eval(du.password());
            stored = ds.authCatalog().defineUser(du.name(), authScope(du.scope()), ns, database,
                password.isString() ? password.asString() : password.toString(), du.roles(), du.dataRules(), du.auditName());
        }
        Statement.DefineUser replayable = stored.certificateBased()
            ? new Statement.DefineUser(du.name(), du.scope(), du.namespace(), du.database(), null, null,
                stored.certificate(), stored.fingerprint(), du.roles(), du.dataRules(), du.auditName())
            : new Statement.DefineUser(du.name(), du.scope(), du.namespace(), du.database(), null,
                AuthCodec.passhash(stored.saltHex(), stored.hashHex()), null, null, du.roles(), du.dataRules(),
                du.auditName());
        ds.applyControl(session, ControlCommand.of(ControlCommand.Kind.USER, ns, database,
            identity(du.name(), du.scope(), ns, database),
            com.axonbase.parser.Render.stmt(replayable)));
        return AxonValue.nul();
    }

    /** Define um access method nomeado, limitado a um escopo. */
    private AxonValue runDefineAccess(Statement.DefineAccess da) {
        String ns = da.scope() == Statement.AuthScope.ROOT ? null
            : da.namespace() != null ? da.namespace() : session.namespace();
        String database = da.scope() == Statement.AuthScope.DATABASE
            ? da.database() != null ? da.database() : session.database() : null;
        if (da.scope() != Statement.AuthScope.ROOT && (ns == null || ns.isBlank())) {
            throw errorStmt(Messages.get("stmt_access_needs_ns"));
        }
        if (da.scope() == Statement.AuthScope.DATABASE && (database == null || database.isBlank())) {
            throw errorStmt(Messages.get("stmt_access_needs_db"));
        }
        ds.authCatalog().defineAccess(da.name(), authScope(da.scope()), ns, database);
        ds.applyControl(session, ControlCommand.of(ControlCommand.Kind.ACCESS, ns, database,
            identity(da.name(), da.scope(), ns, database),
            com.axonbase.parser.Render.stmt(da)));
        return AxonValue.nul();
    }

    private AxonValue runDefineDatabaseLink(DefineDatabaseLink ddl) {
        String ns = session.namespace();
        if (ns == null || ns.isBlank()) {
            throw errorStmt(Messages.get("stmt_link_needs_ns"));
        }
        var def = new com.axonbase.core.engine.Datastore.DatabaseLinkDef(
            ddl.name(), ddl.url(), ddl.ns(), ddl.db(), ddl.user(), ddl.password());
        ds.defineDatabaseLink(ns, ddl.name(), def);
        ds.applyControl(session, ControlCommand.of(ControlCommand.Kind.DATABASE_LINK, ns, "",
            ddl.name(), com.axonbase.parser.Render.stmt(ddl)));
        return AxonValue.nul();
    }

    private AxonValue runDropDatabaseLink(DropDatabaseLink ddl) {
        String ns = session.namespace();
        if (ns == null || ns.isBlank()) {
            throw errorStmt(Messages.get("stmt_link_drop_needs_ns"));
        }
        ds.dropDatabaseLink(ns, ddl.name());
        return AxonValue.nul();
    }

    // ------------------------------------------------------------------
    // SAGA (stubs — implementação nas fases 2-3)
    // ------------------------------------------------------------------

    private AxonValue runCreateSaga(CreateSaga cs) {
        var def = new Datastore.SagaDef(cs.name(), cs.links());
        ds.defineSaga(cs.name(), def);
        return AxonValue.nul();
    }

    private AxonValue runDescribeSaga(DescribeSaga dsc) {
        var def = ds.saga(dsc.name());
        if (def == null) throw errorStmt(Messages.get("stmt_saga_missing", dsc.name()));
        Map<String, AxonValue> info = new LinkedHashMap<>();
        info.put("name", AxonValue.str(def.name()));
        info.put("links", AxonValue.array(def.links().stream().map(AxonValue::str).toList()));
        return AxonValue.object(info);
    }

    private AxonValue runDescribeTable(Statement.DescribeTable dt) {
        Database db = db();
        Catalog.TableDef def = db.catalog().table(dt.table());
        if (def == null) throw errorStmt(Messages.get("stmt_table_undefined", dt.table()));
        List<AxonValue> rows = new ArrayList<>();
        for (Catalog.FieldDef f : def.fields().values().stream()
                .sorted(java.util.Comparator.comparing(Catalog.FieldDef::name)).toList()) {
            Map<String, AxonValue> row = new LinkedHashMap<>();
            row.put("name", AxonValue.str(def.name()));
            row.put("schema", AxonValue.str(def.schemafull() ? "SCHEMAFULL" : "SCHEMALESS"));
            row.put("field", AxonValue.str(f.name()));
            row.put("type", f.type() == null ? AxonValue.str("any") : AxonValue.str(f.type()));
            row.put("readonly", AxonValue.bool(f.readonly()));
            if (f.defaultExpr() != null) {
                row.put("default", AxonValue.str(com.axonbase.parser.Render.expr(f.defaultExpr())));
            } else {
                row.put("default", AxonValue.nul());
            }
            if (f.assertExpr() != null) {
                row.put("assert", AxonValue.str(com.axonbase.parser.Render.expr(f.assertExpr())));
            } else {
                row.put("assert", AxonValue.nul());
            }
            if (f.references() != null) {
                row.put("references", AxonValue.str(f.references()));
            } else {
                row.put("references", AxonValue.nul());
            }
            rows.add(AxonValue.object(row));
        }
        if (!def.schemafull()) {
            Map<String, String> inferred = inferredFields(db, def);
            for (Map.Entry<String, String> field : inferred.entrySet()) {
                Map<String, AxonValue> row = new LinkedHashMap<>();
                row.put("name", AxonValue.str(def.name()));
                row.put("schema", AxonValue.str("SCHEMALESS"));
                row.put("field", AxonValue.str(field.getKey()));
                row.put("type", AxonValue.str(field.getValue()));
                row.put("readonly", AxonValue.bool(false));
                row.put("default", AxonValue.nul());
                row.put("assert", AxonValue.nul());
                row.put("references", AxonValue.nul());
                rows.add(AxonValue.object(row));
            }
        }
        rows.sort(java.util.Comparator.comparing(row -> row.asObject().get("field").asString()));
        if (rows.isEmpty()) {
            rows.add(AxonValue.object(Map.of(
                "name", AxonValue.str(def.name()),
                "schema", AxonValue.str(def.schemafull() ? "SCHEMAFULL" : "SCHEMALESS"),
                "field", AxonValue.nul(),
                "type", AxonValue.nul(),
                "readonly", AxonValue.nul(),
                "default", AxonValue.nul(),
                "assert", AxonValue.nul(),
                "references", AxonValue.nul())));
        }
        return AxonValue.array(rows);
    }

    private Map<String, String> inferredFields(Database db, Catalog.TableDef def) {
        Map<String, String> fields = new java.util.TreeMap<>();
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + def.name() + "\u0000";
        for (String key : records(db).keysWithPrefix(prefix)) {
            Optional<byte[]> raw = records(db).get(key);
            if (raw.isEmpty()) continue;
            AxonValue record = decode(raw.get());
            if (!record.isObject()) continue;
            for (Map.Entry<String, AxonValue> entry : record.asObject().entrySet()) {
                if (def.fields().containsKey(entry.getKey())) continue;
                String type = inferredType(entry.getValue());
                fields.merge(entry.getKey(), type, (previous, current) ->
                    previous.equals(current) ? previous : "any");
            }
        }
        return fields;
    }

    private static String inferredType(AxonValue value) {
        return switch (value.type()) {
            case BOOL -> "bool";
            case NUMBER -> value.isInteger() ? "int" : "number";
            case STRING -> "string";
            case DURATION -> "duration";
            case DATETIME -> "datetime";
            case UUID -> "uuid";
            case ARRAY -> "array";
            case SET -> "set";
            case OBJECT -> "object";
            case BYTES -> "bytes";
            case RECORD_ID -> "record";
            case TABLE -> "table";
            case NONE, NULL -> "any";
        };
    }

    private AxonValue runShowSagaTransaction(ShowSagaTransaction st) {
        // Query the saga ledger
        AxonValue saga = ds.execute(
            "SELECT * FROM saga WHERE correlation_id = \"" + SagaLedger.esc(st.correlationId()) + "\"",
            sagaSession(), null);
        AxonValue steps = ds.execute(
            "SELECT * FROM saga_step WHERE correlation_id = \"" + SagaLedger.esc(st.correlationId())
                + "\" ORDER BY step_order ASC",
            sagaSession(), null);
        Map<String, AxonValue> result = new LinkedHashMap<>();
        result.put("saga", saga.isArray() && !saga.asArray().isEmpty() ? saga.asArray().get(0) : AxonValue.nul());
        result.put("steps", steps);
        return AxonValue.object(result);
    }

    private AxonValue runBeginSaga(BeginSaga bs) {
        sagaLedger.ensureTables();
        sagaLedger.beginSaga(bs.name(), bs.correlationId());
        coordinateSagaParticipants(bs.name(), bs.correlationId(), "BEGIN", false);
        return AxonValue.object(Map.of(
            "saga", AxonValue.str(bs.name()),
            "correlation_id", AxonValue.str(bs.correlationId()),
            "status", AxonValue.str("RUNNING")
        ));
    }

    private AxonValue runCommitSaga(CommitSaga cs) {
        if (!sagaLedger.isSagaActive(cs.correlationId())) {
            throw errorStmt(Messages.get("stmt_saga_inactive", cs.correlationId()));
        }
        coordinateSagaParticipants(cs.name(), cs.correlationId(), "COMMIT", false);
        sagaLedger.commitSaga(cs.name(), cs.correlationId());
        return AxonValue.object(Map.of("status", AxonValue.str("COMMITTED")));
    }

    private AxonValue runCancelSaga(CancelSaga cs) {
        if (!sagaLedger.isSagaActive(cs.correlationId())) {
            throw errorStmt(Messages.get("stmt_saga_inactive", cs.correlationId()));
        }
        coordinateSagaParticipants(cs.name(), cs.correlationId(), "CANCEL", true);
        sagaLedger.cancelSaga(cs.name(), cs.correlationId());
        return AxonValue.object(Map.of("status", AxonValue.str("FAILED")));
    }

    private AxonValue runJoinSaga(JoinSaga js) {
        if (!sagaLedger.isSagaActive(js.correlationId())) {
            throw errorStmt(Messages.get("stmt_saga_inactive", js.correlationId()));
        }
        session.sagaBegin(js.name(), js.correlationId());
        session.vars().set("saga_corr", AxonValue.str(js.correlationId()));
        return AxonValue.object(Map.of("status", AxonValue.str("RUNNING")));
    }

    private AxonValue runLeaveSaga() {
        session.sagaEnd();
        session.vars().set("saga_corr", AxonValue.nul());
        return AxonValue.object(Map.of("ok", AxonValue.str("1")));
    }

    private void coordinateSagaParticipants(String sagaName, String correlationId, String action, boolean reverse) {
        Datastore.SagaDef definition = ds.saga(sagaName);
        if (definition == null) {
            return;
        }
        List<String> links = new ArrayList<>(definition.links());
        if (reverse) {
            java.util.Collections.reverse(links);
        }
        String statement = action + " SAGA " + sagaName + " WITH CORRELATION '"
            + SagaLedger.esc(correlationId) + "'";
        for (String linkName : links) {
            Datastore.DatabaseLinkDef link = ds.databaseLink(session.namespace(), linkName);
            if (link == null) {
                throw errorStmt(Messages.get("stmt_saga_link_missing", linkName));
            }
            try {
                // Participant cancellation owns its local steps. Mirror them first as
                // reporting-only records so the orchestrator never compensates them again.
                if (!"BEGIN".equals(action)) {
                    List<AxonValue> transaction = linkClient.query(link,
                        "SHOW SAGA TRANSACTION " + sagaName + " '" + SagaLedger.esc(correlationId) + "'");
                    if (!transaction.isEmpty() && transaction.get(0).isObject()) {
                        AxonValue steps = transaction.get(0).asObject().get("steps");
                        if (steps != null && steps.isArray()) {
                            sagaLedger.reportParticipantSteps(correlationId, session.namespace(), linkName, steps.asArray());
                        }
                    }
                }
                linkClient.query(link, statement);
            } catch (RuntimeException e) {
                throw errorStmt(Messages.get("stmt_saga_participant_failed", linkName, action, e.getMessage()));
            }
        }
    }

    // ------------------------------------------------------------------
    // DATA RULES
    // ------------------------------------------------------------------

    private AxonValue runCreateDataRule(Statement.CreateDataRule cd) {
        Database db = db();
        Map<String, Expr> injections = extractInjections(cd.predicate());
        db.catalog().defineDataRule(new Catalog.DataRuleDef(cd.name(), cd.predicate(), cd.maskPatterns(), injections));
        control(db, ControlCommand.Kind.TABLE, cd.name(), com.axonbase.parser.Render.stmt(cd));
        return AxonValue.nul();
    }

    private AxonValue runDropDataRule(Statement.DropDataRule dr) {
        Database db = db();
        if (!db.catalog().removeDataRule(dr.name())) {
            throw errorStmt(Messages.get("stmt_data_rule_missing", dr.name()));
        }
        return AxonValue.nul();
    }

    private AxonValue runRemoveTable(Statement.RemoveTable rt) {
        Database db = db();
        String table = rt.name();
        if (!db.catalog().removeTable(table)) {
            throw errorStmt(Messages.get("stmt_table_undefined", table));
        }
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
        for (String key : records(db).keysWithPrefix(prefix)) {
            records(db).delete(key);
        }
        return AxonValue.nul();
    }

    private AxonValue runShowDataRules() {
        Database db = db();
        List<AxonValue> items = new ArrayList<>();
        for (Catalog.DataRuleDef def : db.catalog().dataRules()) {
            List<AxonValue> masks = new ArrayList<>();
            for (String p : def.maskPatterns()) {
                masks.add(AxonValue.str(p));
            }
            items.add(AxonValue.object(Map.of(
                "name", AxonValue.str(def.name()),
                "predicate", AxonValue.str(com.axonbase.parser.Render.expr(def.predicate())),
                "mask_patterns", AxonValue.array(masks)
            )));
        }
        return AxonValue.array(items);
    }

    /** Coleta os predicados das Data Rules associadas ao usuário da sessão. */
    private List<Expr> dataRulePredicates() {
        AxonValue auth = session.auth();
        if (auth == null || !auth.isObject()) return List.of();
        Database db = db();
        List<Expr> predicates = new ArrayList<>();
        for (String name : dataRuleNames(auth)) {
            Catalog.DataRuleDef def = db.catalog().dataRule(name);
            if (def != null) predicates.add(def.predicate());
        }
        return predicates;
    }

    /** Extrai os nomes das Data Rules do objeto auth (array ou CSV). */
    private List<String> dataRuleNames(AxonValue auth) {
        AxonValue rules = auth.asObject().get("data_rules");
        if (rules == null) return List.of();
        if (rules.isArray()) {
            return rules.asArray().stream()
                .filter(AxonValue::isString).map(AxonValue::asString).toList();
        }
        if (rules.isString() && !rules.asString().isEmpty()) {
            return java.util.Arrays.asList(rules.asString().split(","));
        }
        return List.of();
    }

    /**
     * Extrai mapeamentos de injeção do predicado de uma data rule.
     * Reconhece padrões {@code campo = $auth.campo} e {@code campo = literal}.
     */
    private Map<String, Expr> extractInjections(Expr predicate) {
        Map<String, Expr> injections = new java.util.LinkedHashMap<>();
        extractEqInjection(predicate, injections);
        return injections;
    }

    private void extractEqInjection(Expr expr, Map<String, Expr> out) {
        if (expr instanceof Expr.Binary b && b.op() == Expr.BinaryOp.EQ) {
            if (b.left() instanceof Expr.Ident id) {
                out.putIfAbsent(id.name(), b.right());
            }
        } else if (expr instanceof Expr.Binary b && b.op() == Expr.BinaryOp.AND) {
            extractEqInjection(b.left(), out);
            extractEqInjection(b.right(), out);
        }
    }

    /** Coleta os pares (campo, expr) a injetar do usuário autenticado. */
    private Map<String, Expr> dataRuleInjections() {
        AxonValue auth = session.auth();
        if (auth == null || !auth.isObject()) return Map.of();
        Database db = db();
        Map<String, Expr> all = new java.util.LinkedHashMap<>();
        for (String name : dataRuleNames(auth)) {
            Catalog.DataRuleDef def = db.catalog().dataRule(name);
            if (def != null) {
                all.putAll(def.injections());
            }
        }
        return all;
    }

    /** Injeta nos campos do novo registro os valores mapeados pelas data rules do usuário. */
    private void injectDataRuleFields(Map<String, AxonValue> obj) {
        for (var entry : dataRuleInjections().entrySet()) {
            AxonValue value = eval(entry.getValue());
            if (value != null && !value.isNone()) {
                obj.put(entry.getKey(), value);
            }
        }
    }

    /** Aplica mascaramento nos campos do resultado conforme os padrões das regras do usuário. */
    private AxonValue applyDataRuleMask(AxonValue result) {
        if (result == null || !result.isObject()) return result;
        AxonValue auth = session.auth();
        if (auth == null || !auth.isObject()) return result;
        Database db = db();
        for (String ruleName : dataRuleNames(auth)) {
            Catalog.DataRuleDef def = db.catalog().dataRule(ruleName);
            if (def == null || def.maskPatterns().isEmpty()) continue;
            Map<String, AxonValue> masked = new java.util.LinkedHashMap<>(result.asObject());
            for (String pattern : def.maskPatterns()) {
                String regex = sqlLikeToRegex(pattern);
                for (var entry : result.asObject().entrySet()) {
                    if (entry.getKey().toLowerCase().matches(regex)) {
                        masked.put(entry.getKey(), AxonValue.str("***"));
                    }
                }
            }
            result = AxonValue.object(masked);
        }
        return result;
    }

    /** Converte padrão SQL LIKE (com %) para regex. */
    private static String sqlLikeToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("(?i)");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append(".");
            } else {
                regex.append("\\Q").append(c).append("\\E");
            }
        }
        return regex.toString();
    }

    /** Aplica predicados das Data Rules como filtro adicional nos documentos. */
    private void applyDataRulesFilter(List<Document> docs) {
        List<Expr> predicates = dataRulePredicates();
        if (predicates.isEmpty()) return;
        Expr combined = predicates.size() == 1 ? predicates.get(0)
            : Expr.and(predicates);
        docs.removeIf(doc -> !truthy(evalCond(combined, doc)));
    }

    /** Retorna true se o documento passa pelo filtro das Data Rules. */
    private boolean dataRuleFilter(Document doc) {
        List<Expr> predicates = dataRulePredicates();
        if (predicates.isEmpty()) return true;
        Expr combined = predicates.size() == 1 ? predicates.get(0)
            : Expr.and(predicates);
        return truthy(evalOnDocument(combined, doc));
    }

    private Session sagaSession() {
        Session s = Session.create();
        s.namespace("system");
        s.database("saga");
        return s;
    }

    /** Retorna a correlation ID da saga ativa nesta sessão, ou null. */
    private String sagaCorrelation() {
        AxonValue corr = session.vars().get("saga_corr");
        if (corr != null && corr.isString()) {
            String c = corr.asString();
            if (!c.isEmpty()) {
                if (sagaLedger.isSagaActive(c)) return c;
            }
        }
        return null;
    }

    /** Nome único da identidade no plano de controle, incluindo o escopo. */
    private static String identity(String name, Statement.AuthScope scope, String ns, String db) {
        return name + ":" + scope + ":" + (ns == null ? "" : ns) + ":" + (db == null ? "" : db);
    }

    /** Registra a definição no plano de controle, no escopo do banco corrente. */
    private void control(Database db, ControlCommand.Kind kind, String name, String definition) {
        ds.applyControl(session, ControlCommand.of(kind, db.ns(), db.db(), name, definition));
    }


    private com.axonbase.core.security.AuthCatalog.Scope authScope(Statement.AuthScope scope) {
        return com.axonbase.core.security.AuthCatalog.Scope.valueOf(scope.name());
    }

    // ------------------------------------------------------------------
    // Resolución de targets
    // ------------------------------------------------------------------

    private List<RecordId> resolveTargets(Expr target) {
        Database db = db();
        if (target instanceof Expr.RecordId rid) {
            return List.of(configRecordId(rid));
        }
        if (target instanceof Expr.Ident ident) {
            String table = ident.name();
            ensureTable(db, table);
            String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
            List<RecordId> out = new ArrayList<>();
            for (String k : records(db).keysWithPrefix(prefix)) {
                String keyPart = k.substring(prefix.length());
                out.add(new RecordId(table, parseKey(keyPart)));
            }
            return out;
        }
        if (target instanceof Expr.Qualified q) {
            String ns = session.namespace();
            if (ns == null) return List.of();
            String table = q.link().equals(ns) ? q.table() : q.link();
            Database targetDb = db();
            if (!q.link().equals(ns)) {
                targetDb = ds.ensureDatabase(session, ns, q.link());
            }
            String prefix = targetDb.ns() + "\u0000" + targetDb.db() + "\u0000" + table + "\u0000";
            List<RecordId> out = new ArrayList<>();
            for (String k : records(targetDb).keysWithPrefix(prefix)) {
                String keyPart = k.substring(prefix.length());
                out.add(new RecordId(table, parseKey(keyPart)));
            }
            return out;
        }
        if (target instanceof Expr.Param p) {
            AxonValue v = session.vars().get(p.name());
            if (v != null && v.isRecordId()) {
                AxonValue.RecordId r = v.asRecordId();
                return List.of(new RecordId(r.table(), keyOf(r.key())));
            }
        }
        return List.of();
    }

    private RecordId configRecordId(Expr.RecordId rid) {
        String table = rid.table() instanceof Expr.Qualified q ? q.table() : literalIdent(rid.table());
        return new RecordId(table, recordKey(rid.key()));
    }

    /**
     * Normaliza a chave de um record id. Um identificador nu ({@code person:ana})
     * vale como texto; o resto avalia-se como expressão ({@code person:1},
     * {@code person:"ana"}, {@code person:$id}).
     */
    private AxonValue recordKey(Expr keyExpr) {
        if (keyExpr instanceof Expr.Ident id) {
            return AxonValue.str(id.name());
        }
        AxonValue v = eval(keyExpr);
        if (v == null || v.isNone() || v.isNull()) {
            return AxonValue.str("");
        }
        if (v.isRecordId()) {
            return keyOf(v.asRecordId().key());
        }
        return v.isNumber() || v.isString() ? v : AxonValue.str(v.toString());
    }

    private RecordId ridNew() {
        return new RecordId("", AxonValue.str(""));
    }

    private String tableOf(Expr e) {
        return switch (e) {
            case Expr.Ident id -> id.name();
            case Expr.RecordId rid -> rid.table() instanceof Expr.Ident id2 ? id2.name() : "";
            case Expr.Param p -> {
                AxonValue v = session.vars().get(p.name());
                yield v != null && v.isRecordId() ? v.asRecordId().table() : "";
            }
            default -> "";
        };
    }

    private String fieldOf(Expr e) {
        if (e instanceof Expr.Ident id) {
            return id.name();
        }
        if (e instanceof Expr.Idiom i) {
            for (int idx = i.parts().size() - 1; idx >= 0; idx--) {
                if (i.parts().get(idx) instanceof Expr.Part.Field f) {
                    return f.name();
                }
            }
            return idiomToString(i);
        }
        return "field";
    }

    private String idiomToString(Expr.Idiom i) {
        StringBuilder sb = new StringBuilder();
        if (i.base() instanceof Expr.Ident id) {
            // '*' é a base sintética de uma travessia iniciada por seta
            if (!id.name().equals("*")) {
                sb.append(id.name());
            }
        } else if (i.base() instanceof Expr.Idiom nested) {
            sb.append(idiomToString(nested));
        }
        for (Expr.Part p : i.parts()) {
            if (p instanceof Expr.Part.Field f) {
                sb.append('.').append(f.name());
            } else if (p instanceof Expr.Part.Graph g) {
                sb.append(switch (g.direction()) {
                    case IN -> "<-";
                    case BOTH -> "<->";
                    default -> "->";
                });
                if (g.lookup() instanceof Expr.Ident lookup) {
                    sb.append(lookup.name());
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Evaluación de expresións
    // ------------------------------------------------------------------

    private AxonValue eval(Expr e) {
        if (e == null) {
            return AxonValue.nul();
        }
        return switch (e) {
            case Expr.Literal l -> l.value();
            case Expr.Ident id -> {
                String name = id.name();
                if ("current_timestamp".equalsIgnoreCase(name) || "now".equalsIgnoreCase(name)) {
                    yield AxonValue.datetime(java.time.Instant.now());
                }
                Database db = db();
                Catalog.TableDef def = db.catalog().table(name);
                yield AxonValue.table(def != null ? def.name() : name);
            }
            case Expr.Qualified q -> AxonValue.table(q.table());
            case Expr.Param p -> session.vars().getOrMissing(p.name());
            case Expr.Path p -> invokePath(p);
            case Expr.Call c -> invokeCall(c);
            case Expr.RecordId rid -> mapping(rid);
            case Expr.ObjectLit o -> { yield objectLit(o); }
            case Expr.ArrayLit a -> { yield arrayLit(a); }
            case Expr.SetLit s -> { yield AxonValue.set(s.items().stream().map(this::eval).toList()); }
            case Expr.Block b -> { yield block(b); }
            case Expr.Unary u -> evalUnary(u);
            case Expr.Binary b -> evalBinary(b);
            case Expr.Cast c -> evalCast(c);
            case Expr.Idiom i -> evalIdiom(i, null);
            case Expr.Range r -> { yield AxonValue.nul(); }
            case Expr.SubQuery sq -> evalSubQuery(sq);
            case Expr.Alias a -> eval(a.expr());
        };
    }

    private AxonValue evalIdiom(Expr.Idiom i, Document doc) {
        AxonValue base = idiomBase(i.base(), doc);
        for (Expr.Part p : i.parts()) {
            base = applyPart(base, p);
        }
        return base;
    }

    /**
     * Executes a graph idiom (e.g. {@code ->knows->person}) entirely on the remote
     * server via DATABASE LINK. Builds SELECT VALUE <parts> FROM <base>.
     */
    /**
     * Base de um idiom. Dentro de um documento, um identificador é um campo e
     * {@code *} (base sintética de uma travessia iniciada por seta) é o próprio
     * registro. Bases aninhadas resolvem-se recursivamente.
     */
    private AxonValue idiomBase(Expr base, Document doc) {
        if (doc == null) {
            return eval(base);
        }
        if (base instanceof Expr.Idiom nested) {
            return evalIdiom(nested, doc);
        }
        if (base instanceof Expr.Ident id) {
            if (id.name().equals("*")) {
                return ridAsValue(doc.id());
            }
            AxonValue fv = fieldValue(doc.current(), id.name());
            if (!fv.isNone() && !fv.isNull()) {
                return fv;
            }
            return doc.current();
        }
        return eval(base);
    }

    private AxonValue applyPart(AxonValue v, Expr.Part p) {
        return switch (p) {
            case Expr.Part.Field f -> v.isObject() ? v.asObject().getOrDefault(f.name(), AxonValue.nul()) : AxonValue.nul();
            case Expr.Part.Index ix -> {
                int idx = (int) eval(ix.index()).asLong();
                yield v.isArray() && idx >= 0 && idx < v.asArray().size() ? v.asArray().get(idx) : AxonValue.nul();
            }
            case Expr.Part.Graph g -> graphTraverse(v, g);
            default -> v;
        };
    }

    /**
     * Travessia de grafo. Parte de um record id (ou coleção deles) e devolve os
     * vértices alcançados pelas arestas na direção pedida.
     *
     * <p>Numa cadeia como {@code ->wrote->article}, o primeiro termo casa com o
     * tipo da aresta e o segundo não encontra arestas com esse nome: nesse caso
     * o termo é lido como filtro pela tabela de destino.</p>
     */
    private AxonValue graphTraverse(AxonValue v, Expr.Part.Graph g) {
        Database db = db();
        List<AxonValue> sources = new ArrayList<>();
        if (v.isArray()) {
            sources.addAll(v.asArray());
        } else if (v.isRecordId()) {
            sources.add(v);
        } else if (v.isObject() && v.asObject().get("id") != null
            && v.asObject().get("id").isRecordId()) {
            sources.add(v.asObject().get("id"));
        } else {
            return AxonValue.array(List.of());
        }
        String want = g.lookup() instanceof Expr.Ident lookup ? lookup.name() : "*";
        List<String> dirs = g.direction() == Expr.Direction.BOTH
            ? List.of("out", "in")
            : List.of(g.direction() == Expr.Direction.IN ? "in" : "out");

        List<AxonValue> results = new ArrayList<>();
        for (AxonValue src : sources) {
            if (!src.isRecordId()) {
                continue;
            }
            for (String dir : dirs) {
                results.addAll(edgeTargets(db, src.asRecordId(), dir, want));
            }
        }
        if (results.isEmpty() && !want.equals("*")) {
            // segundo termo da cadeia: filtra os vértices já alcançados pela tabela
            for (AxonValue src : sources) {
                if (src.isRecordId() && src.asRecordId().table().equals(want)) {
                    results.add(src);
                }
            }
        }
        if (results.size() == 1) {
            return results.get(0);
        }
        return AxonValue.array(results);
    }

    /** Vértices ligados a {@code from} numa direção, filtrando pelo tipo da aresta. */
    private List<AxonValue> edgeTargets(Database db, AxonValue.RecordId from, String dir, String want) {
        String prefix = edgePrefix(db, dir, from);
        List<AxonValue> out = new ArrayList<>();
        for (String k : records(db).keysWithPrefix(prefix)) {
            String[] parts = k.substring(prefix.length()).split(java.util.regex.Pattern.quote("|"));
            if (parts.length < 3) {
                continue;
            }
            String kind = parts[0];
            if (!want.equals("*") && !want.equals(kind)) {
                continue;
            }
            out.add(AxonValue.record(parts[1], parseKeyVal(parts[2])));
        }
        return out;
    }

    private Object parseKeyVal(String k) {
        if (k.startsWith("n")) {
            try {
                return java.math.BigDecimal.valueOf(Long.parseLong(k.substring(1)));
            } catch (NumberFormatException e) {
                return k;
            }
        }
        return k.substring(1);
    }

    private AxonValue fieldValue(AxonValue obj, String name) {
        if (obj.isObject()) {
            return obj.asObject().getOrDefault(name, AxonValue.nul());
        }
        return AxonValue.nul();
    }

    private AxonValue evalCond(Expr cond, Document doc) {
        return evalExprInDoc(cond, doc);
    }

    /** Avalía un campo(s) de proyección sobre un documento. */
    private AxonValue evalField(Expr f, Document doc) {
        if (f instanceof Expr.Alias a) {
            return evalField(a.expr(), doc);
        }
        if (f instanceof Expr.Ident id) {
            return fieldValue(doc.current(), id.name());
        }
        if (f instanceof Expr.SubQuery || f instanceof Expr.Call || f instanceof Expr.Binary) {
            return evalExprInDoc(f, doc);
        }
        if (f instanceof Expr.Idiom i) {
            return evalIdiom(i, doc);
        }
        return eval(f);
    }

    private AxonValue evalExprInDoc(Expr e, Document doc) {
        if (e == null) {
            return AxonValue.bool(true);
        }
        if (e instanceof Expr.Binary b) {
            return evalBinary(b, doc);
        }
        if (e instanceof Expr.Idiom i) {
            return evalIdiom(i, doc);
        }
        if (e instanceof Expr.Ident id) {
            return fieldValue(doc.current(), id.name());
        }
        if (e instanceof Expr.Unary u) {
            return evalUnaryDoc(u, doc);
        }
        if (e instanceof Expr.Literal l) {
            return l.value();
        }
        if (e instanceof Expr.Call c) {
            return invokeCall(c, doc);
        }
        if (e instanceof Expr.Alias a) {
            return evalExprInDoc(a.expr(), doc);
        }
        if (e instanceof Expr.SubQuery sq) {
            // subquery correlacionada: o registro externo fica em $parent
            return evalSubQuery(sq, doc);
        }
        if (e instanceof Expr.ObjectLit || e instanceof Expr.ArrayLit) {
            return eval(e);
        }
        return eval(e);
    }

    private boolean truthy(AxonValue v) {
        return switch (v.type()) {
            case BOOL -> v.asBool();
            case NULL, NONE -> false;
            case STRING -> !v.asString().isEmpty();
            case NUMBER -> v.asDouble() != 0;
            case ARRAY -> !v.asArray().isEmpty();
            case OBJECT -> !v.asObject().isEmpty();
            default -> true;
        };
    }

    private AxonValue evalBinary(Expr.Binary b) {
        AxonValue l = eval(b.left());
        AxonValue r = eval(b.right());
        return binaryOp(b.op(), l, r);
    }

    private AxonValue evalBinary(Expr.Binary b, Document doc) {
        AxonValue left = evalExprInDoc(b.left(), doc);
        AxonValue right = evalExprInDoc(b.right(), doc);
        return binaryOp(b.op(), left, right);
    }

    private AxonValue binaryOp(Expr.BinaryOp op, AxonValue l, AxonValue r) {
        return switch (op) {
            case AND -> truthy(l) && truthy(r) ? AxonValue.bool(true) : AxonValue.bool(false);
            case OR -> truthy(l) || truthy(r) ? AxonValue.bool(true) : AxonValue.bool(false);
            case EQ -> AxonValue.bool(equal(l, r));
            case EQ_EXACT -> AxonValue.bool(l.equals(r));
            case NE -> AxonValue.bool(!equal(l, r));
            case LT -> AxonValue.bool(l.compareTo(r) < 0);
            case GT -> AxonValue.bool(l.compareTo(r) > 0);
            case LE -> AxonValue.bool(l.compareTo(r) <= 0);
            case GE -> AxonValue.bool(l.compareTo(r) >= 0);
            case ADD -> add(l, r);
            case SUB -> sub(l, r);
            case MUL -> AxonValue.num(num(l, r, 1).multiply(num(r, l, 1)));
            case DIV -> divide(l, r);
            case MOD -> mod(l, r);
            case POW -> pow(l, r);
            case COALESCE -> l.isNull() || l.isNone() ? r : l;
            case CONTAIN -> AxonValue.bool(contains(l, r));
            case NOT_CONTAIN -> AxonValue.bool(!contains(l, r));
            case CONTAIN_ALL -> AxonValue.bool(containsEvery(l, r));
            case CONTAIN_ANY -> AxonValue.bool(containsSome(l, r));
            case CONTAIN_NONE -> AxonValue.bool(!containsSome(l, r));
            case INSIDE -> AxonValue.bool(contains(r, l));
            case NOT_INSIDE -> AxonValue.bool(!contains(r, l));
            case ALL_INSIDE -> AxonValue.bool(containsEvery(r, l));
            case ANY_INSIDE -> AxonValue.bool(containsSome(r, l));
            case NONE_INSIDE -> AxonValue.bool(!containsSome(r, l));
            case ALL_EQ -> AxonValue.bool(everyEquals(l, r));
            case ANY_EQ -> AxonValue.bool(someEquals(l, r));
            case MATCH -> AxonValue.bool(textMatches(l, r));
            default -> AxonValue.nul();
        };
    }

    // ------------------------------------------------------------------
    // Operadores de coleção (CONTAINS, IN e variantes)
    // ------------------------------------------------------------------

    /**
     * Pertença de {@code needle} em {@code haystack}: elemento de array ou
     * conjunto, subcadeia de texto ou chave de objeto.
     */
    private boolean contains(AxonValue haystack, AxonValue needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        if (haystack.isArray() || haystack.isSet()) {
            return elements(haystack).stream().anyMatch(item -> equal(item, needle));
        }
        if (haystack.isString()) {
            return needle.isString() && haystack.asString().contains(needle.asString());
        }
        if (haystack.isObject()) {
            return needle.isString() && haystack.asObject().containsKey(needle.asString());
        }
        return false;
    }

    /** Todos os elementos de {@code items} estão em {@code haystack}. */
    private boolean containsEvery(AxonValue haystack, AxonValue items) {
        List<AxonValue> list = elements(items);
        if (list.isEmpty()) {
            return contains(haystack, items);
        }
        return list.stream().allMatch(item -> contains(haystack, item));
    }

    /** Pelo menos um elemento de {@code items} está em {@code haystack}. */
    private boolean containsSome(AxonValue haystack, AxonValue items) {
        List<AxonValue> list = elements(items);
        if (list.isEmpty()) {
            return contains(haystack, items);
        }
        return list.stream().anyMatch(item -> contains(haystack, item));
    }

    private boolean everyEquals(AxonValue collection, AxonValue value) {
        List<AxonValue> list = elements(collection);
        return !list.isEmpty() && list.stream().allMatch(item -> equal(item, value));
    }

    private boolean someEquals(AxonValue collection, AxonValue value) {
        return elements(collection).stream().anyMatch(item -> equal(item, value));
    }

    /** Sem índice, {@code @@} ainda funciona por comparação de termos simples. */
    private boolean textMatches(AxonValue text, AxonValue query) {
        if (!text.isString() || !query.isString()) {
            return false;
        }
        String haystack = text.asString().toLowerCase(java.util.Locale.ROOT);
        for (String term : query.asString().toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (!term.isBlank() && !haystack.contains(term)) {
                return false;
            }
        }
        return true;
    }

    /** Elementos de um array ou conjunto; lista vazia para os demais tipos. */
    private List<AxonValue> elements(AxonValue v) {
        if (v == null) {
            return List.of();
        }
        if (v.isArray()) {
            return v.asArray();
        }
        if (v.isSet()) {
            return v.asSet();
        }
        return List.of();
    }

    private boolean equal(AxonValue a, AxonValue b) {
        if (a.isNumber() && b.isNumber()) {
            return a.compareTo(b) == 0;
        }
        if (a.isNumber() && b.isString()) {
            try {
                return a.compareTo(AxonValue.num(new java.math.BigDecimal(b.asString()))) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (a.isString() && b.isNumber()) {
            try {
                return new java.math.BigDecimal(a.asString()).compareTo(b.asDecimal()) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return a.equals(b);
    }

    private AxonValue add(AxonValue l, AxonValue r) {
        if (l.isDatetime() && r.isDuration()) {
            return AxonValue.datetime(l.asInstant().plusMillis(r.asDuration().millis()));
        }
        if (r.isDatetime() && l.isDuration()) {
            return AxonValue.datetime(r.asInstant().plusMillis(l.asDuration().millis()));
        }
        if (l.isString() || r.isString()) {
            return AxonValue.str(l.toString() + r.toString());
        }
        return AxonValue.num(num(l, r, 0).add(num(r, l, 0)));
    }

    private AxonValue sub(AxonValue l, AxonValue r) {
        if (l.isDatetime() && r.isDuration()) {
            return AxonValue.datetime(l.asInstant().minusMillis(r.asDuration().millis()));
        }
        return AxonValue.num(num(l, r, 0).subtract(num(r, l, 0)));
    }

    private AxonValue divide(AxonValue l, AxonValue r) {
        if (num(r, l, 1).signum() == 0) {
            throw AxonError.internal(Messages.get("stmt_division_by_zero"));
        }
        return AxonValue.num(num(l, r, 0).divide(num(r, l, 1)));
    }

    private AxonValue mod(AxonValue l, AxonValue r) {
        return AxonValue.num(num(l, r, 0).remainder(num(r, l, 1)));
    }

    private AxonValue pow(AxonValue l, AxonValue r) {
        return AxonValue.num(Math.pow(l.asDouble(), r.asDouble()));
    }

    private java.math.BigDecimal num(AxonValue v, AxonValue fallback, long def) {
        if (v != null && v.isNumber()) {
            return v.asDecimal();
        }
        if (fallback != null && fallback.isNumber()) {
            return fallback.asDecimal();
        }
        return java.math.BigDecimal.valueOf(def);
    }

    private AxonValue evalUnary(Expr.Unary u) {
        AxonValue operand = eval(u.operand());
        return switch (u.op()) {
            case NOT -> AxonValue.bool(!truthy(operand));
            case NEG -> AxonValue.num(num(operand, null, 0).negate());
            case POS -> operand;
        };
    }

    private AxonValue evalUnaryDoc(Expr.Unary u, Document doc) {
        AxonValue operand = evalExprInDoc(u.operand(), doc);
        return switch (u.op()) {
            case NOT -> AxonValue.bool(!truthy(operand));
            case NEG -> AxonValue.num(num(operand, null, 0).negate());
            case POS -> operand;
        };
    }

    private AxonValue evalCast(Expr.Cast c) {
        AxonValue v = eval(c.operand());
        return switch (c.type().toLowerCase()) {
            case "int" -> {
                if (v.isNumber()) yield AxonValue.num(v.asLong());
                if (v.isString()) yield AxonValue.num(Long.parseLong(v.asString()));
                yield AxonValue.nul();
            }
            case "float", "number" -> AxonValue.num(v.asDouble());
            case "string" -> AxonValue.str(v.toString());
            case "bool" -> AxonValue.bool(truthy(v));
            case "datetime" -> v;
            default -> v;
        };
    }

    private AxonValue invokeCall(Expr.Call c) {
        return invokeCall(c, null);
    }

    /**
     * Invoca uma função da biblioteca. Com um documento em contexto, os
     * argumentos são avaliados sobre ele, de modo que
     * {@code string::uppercase(name)} enxerga o campo da linha.
     */
    private AxonValue invokeCall(Expr.Call c, Document doc) {
        String name = normalizeFunction(c.name());
        if (doc != null && name.equals("search::score")) {
            return AxonValue.num(doc.searchScore());
        }
        List<AxonValue> args = new java.util.ArrayList<>();
        for (Expr a : c.args()) {
            args.add(doc != null ? evalExprInDoc(a, doc) : eval(a));
        }
        if (doc != null && name.equals("search::highlight")) {
            return highlight(args, doc.searchTerms());
        }
        if (name.startsWith("kv::")) {
            return invokeKv(name, args);
        }
        if (name.startsWith("graph::")) {
            return invokeGraph(name, args);
        }
        AxonValue result = Functions.call(name, args);
        if (result != null) {
            return result;
        }
        throw AxonError.internal(Messages.get("stmt_function_unknown", name));
    }

    /**
     * Funciones expostas do key-value store: {@code kv::get}, {@code kv::set},
     * {@code kv::del} e {@code kv::scan}. Usan o namespace/database da sesión.
     */
    private AxonValue invokeKv(String name, List<AxonValue> args) {
        String ns = session.namespace();
        String db = session.database();
        if (ns == null || ns.isBlank() || db == null || db.isBlank()) {
            throw AxonError.internal(Messages.get("stmt_kv_scope_required"));
        }
        return switch (name.substring(4)) {
            case "get" -> ds.kvGet(session, ns, db, kvStr(args, 0));
            case "set" -> ds.kvSet(session, ns, db, kvStr(args, 0), kvArg(args, 1), kvTtl(args));
            case "del" -> AxonValue.bool(ds.kvDel(session, ns, db, kvStr(args, 0)));
            case "scan" -> ds.kvScan(session, ns, db, args.isEmpty() ? "" : kvStr(args, 0));
            default -> throw AxonError.internal(Messages.get("stmt_kv_function_unknown", name));
        };
    }

    private static String kvStr(List<AxonValue> args, int i) {
        if (i >= args.size() || args.get(i) == null) {
            return "";
        }
        AxonValue v = args.get(i);
        return v.isString() ? v.asString() : v.toString();
    }

    private static AxonValue kvArg(List<AxonValue> args, int i) {
        return i < args.size() ? args.get(i) : AxonValue.none();
    }

    private static Long kvTtl(List<AxonValue> args) {
        if (args.size() > 2 && args.get(2) != null && args.get(2).isNumber()) {
            return args.get(2).asLong();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Graph functions: BFS traversal for connectedness and path finding
    // ------------------------------------------------------------------

    /**
     * Graph operations: {@code graph::connected(from, to, edge, maxDepth)} and
     * {@code graph::path(from, to, edge, maxDepth)}.
     */
    private AxonValue invokeGraph(String name, List<AxonValue> args) {
        String ns = session.namespace();
        String dbName = session.database();
        if (ns == null || ns.isBlank() || dbName == null || dbName.isBlank()) {
            throw AxonError.internal(Messages.get("stmt_graph_scope_required"));
        }
        if (args.size() < 2) {
            throw AxonError.internal(Messages.get("stmt_graph_record_ids_required"));
        }
        AxonValue.RecordId from = toRecordIdArg(args.get(0));
        AxonValue.RecordId to = toRecordIdArg(args.get(1));
        if (from == null || to == null) {
            throw AxonError.internal(Messages.get("stmt_graph_record_ids_required"));
        }
        Database db = db();
        String edge = args.size() > 2 && args.get(2).isString() ? args.get(2).asString() : "*";
        int maxDepth = args.size() > 3 && args.get(3).isNumber() ? (int) args.get(3).asLong() : 10;

        return switch (name.substring(7)) {
            case "connected" -> graphBfs(db, from, to, edge, maxDepth);
            case "path" -> graphBfsPath(db, from, to, edge, maxDepth);
            default -> throw AxonError.internal(Messages.get("stmt_graph_function_unknown", name));
        };
    }

    /** BFS reachability check. Returns true if there is a path within maxDepth. */
    private AxonValue graphBfs(Database db, AxonValue.RecordId from, AxonValue.RecordId to,
                                String edge, int maxDepth) {
        if (from.table().equals(to.table()) && keyEquals(from.key(), to.key())) {
            return AxonValue.bool(true);
        }
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<AxonValue.RecordId> queue = new java.util.ArrayDeque<>();
        queue.addLast(from);
        visited.add(from.toString());

        for (int depth = 0; !queue.isEmpty() && depth <= maxDepth; depth++) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                AxonValue.RecordId current = queue.removeFirst();
                for (AxonValue target : edgeTargets(db, current, "out", edge)) {
                    if (target.isRecordId()) {
                        AxonValue.RecordId next = target.asRecordId();
                        if (next.table().equals(to.table()) && keyEquals(next.key(), to.key())) {
                            return AxonValue.bool(true);
                        }
                        if (visited.add(next.toString())) {
                            queue.addLast(next);
                        }
                    }
                }
            }
        }
        return AxonValue.bool(false);
    }

    /** BFS path finding. Returns the path as an array of record IDs, or empty array. */
    private AxonValue graphBfsPath(Database db, AxonValue.RecordId from, AxonValue.RecordId to,
                                    String edge, int maxDepth) {
        if (from.table().equals(to.table()) && keyEquals(from.key(), to.key())) {
            return AxonValue.array(java.util.List.of(AxonValue.record(from.table(), from.key())));
        }
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<java.util.List<AxonValue.RecordId>> queue = new java.util.ArrayDeque<>();
        queue.addLast(java.util.List.of(from));
        visited.add(from.toString());

        while (!queue.isEmpty()) {
            java.util.List<AxonValue.RecordId> path = queue.removeFirst();
            AxonValue.RecordId last = path.getLast();

            if (path.size() > maxDepth) {
                continue;
            }

            for (AxonValue target : edgeTargets(db, last, "out", edge)) {
                if (!target.isRecordId()) continue;
                AxonValue.RecordId next = target.asRecordId();
                String key = next.toString();

                if (next.table().equals(to.table()) && keyEquals(next.key(), to.key())) {
                    java.util.List<AxonValue> result = new java.util.ArrayList<>();
                    for (AxonValue.RecordId p : path) {
                        result.add(AxonValue.record(p.table(), p.key()));
                    }
                    result.add(AxonValue.record(next.table(), next.key()));
                    return AxonValue.array(result);
                }

                if (visited.add(key)) {
                    java.util.List<AxonValue.RecordId> newPath = new java.util.ArrayList<>(path);
                    newPath.addLast(next);
                    queue.addLast(newPath);
                }
            }
        }
        return AxonValue.array(java.util.List.of());
    }

    private static boolean keyEquals(Object a, Object b) {
        return a == b || (a != null && a.toString().equals(b != null ? b.toString() : null));
    }

    /** Envolve cada termo da busca em tags HTML (HyperText Markup Language) configuráveis. */
    private AxonValue highlight(List<AxonValue> args, List<String> terms) {
        if (args.isEmpty() || !args.get(0).isString()) {
            return AxonValue.nul();
        }
        String start = args.size() > 1 && args.get(1).isString() ? args.get(1).asString() : "<em>";
        String end = args.size() > 2 && args.get(2).isString() ? args.get(2).asString() : "</em>";
        String text = args.get(0).asString();
        for (String term : terms) {
            text = text.replaceAll("(?i)" + java.util.regex.Pattern.quote(term), start + "$0" + end);
        }
        return AxonValue.str(text);
    }

    /** Tira os parênteses de nomes já normalizados pelo lexer e o prefixo {@code fn::}. */
    private String normalizeFunction(String name) {
        String n = name.endsWith("()") ? name.substring(0, name.length() - 2) : name;
        return n.startsWith("fn::") ? n.substring(4) : n;
    }

    /**
     * Caminho sem chamada: pode ser uma constante da biblioteca
     * ({@code math::pi}) ou um nome de tabela.
     */
    private AxonValue invokePath(Expr.Path p) {
        AxonValue constant = Functions.constant(p.name());
        if (constant != null) {
            return constant;
        }
        return AxonValue.table(p.name());
    }

    // overar dataset

    private AxonValue objectLit(Expr.ObjectLit o) {
        Map<String, AxonValue> m = new LinkedHashMap<>();
        for (Expr.ObjectLit.Entry e : o.entries()) {
            m.put(e.key(), eval(e.value()));
        }
        return AxonValue.object(m);
    }

    private AxonValue arrayLit(Expr.ArrayLit a) {
        List<AxonValue> list = new ArrayList<>();
        for (Expr e : a.items()) {
            list.add(eval(e));
        }
        return AxonValue.array(list);
    }

    private AxonValue block(Expr.Block b) {
        AxonValue result = AxonValue.nul();
        for (Statement s : b.statements()) {
            result = runStatement(s);
        }
        return result;
    }

    private AxonValue evalSubQuery(Expr.SubQuery sq) {
        return evalSubQuery(sq, null);
    }

    /**
     * Executa uma subquery. Com um documento externo, expõe-o em {@code $parent}
     * para permitir correlação, e restaura o valor anterior no fim.
     */
    private AxonValue evalSubQuery(Expr.SubQuery sq, Document outer) {
        AxonValue previousParent = session.vars().get("parent");
        if (outer != null) {
            session.vars().set("parent", outer.current());
        }
        try {
            Executor sub = new Executor(ds);
            return sub.execute(sq.query(), session, session.vars().copy());
        } finally {
            if (outer != null) {
                session.vars().set("parent",
                    previousParent == null ? AxonValue.none() : previousParent);
            }
        }
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    private Object unwrapId(AxonValue v) {
        if (v.isNumber()) {
            return v.asLong();
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.isRecordId()) {
            return v.asRecordId().key();
        }
        return v;
    }

    private AxonValue generateKey(Object id) {
        if (id == null) {
            return AxonValue.str(UUID.randomUUID().toString());
        }
        if (id instanceof Long l) {
            return AxonValue.num(l);
        }
        return AxonValue.str(id.toString());
    }

    private AxonValue keyOf(Object key) {
        if (key instanceof AxonValue v) {
            return v;
        }
        if (key instanceof Long l) {
            return AxonValue.num(l);
        }
        if (key instanceof Number n) {
            return AxonValue.num(n.doubleValue());
        }
        return AxonValue.str(String.valueOf(key));
    }

    /** Converte un identificador de AxonQL nunha String sinxela. */
    private String literalIdent(Expr e) {
        if (e instanceof Expr.Ident id) {
            return id.name();
        }
        if (e instanceof Expr.Qualified q) {
            return q.table();
        }
        if (e instanceof Expr.Literal l && l.value().isString()) {
            return l.value().asString();
        }
        throw AxonError.internal(Messages.get("stmt_identifier_expected", e == null ? "null" : e.getClass().getSimpleName()));
    }

    private AxonValue evalExpr(Expr e) {
        return eval(e);
    }

    private AxonValue evalOnDocument(Expr e, Document doc) {
        return evalExprInDoc(e, doc);
    }

    private AxonValue resolveKey(Object idKey) {
        return generateKey(idKey);
    }

    private AxonValue dataExpr(Statement.Data data) {
        return evalData(data);
    }

    private AxonValue ridAsValue(RecordId rid) {
        return AxonValue.record(rid.table(), rid.key());
    }

    /** Converte um argumento de função de grafo para RecordId, aceitando tanto o token
     *  `table:key` quanto a string `"table:key"` (necessária para chaves com hífen). */
    private AxonValue.RecordId toRecordIdArg(AxonValue v) {
        if (v.isRecordId()) {
            return v.asRecordId();
        }
        if (!v.isString()) {
            return null;
        }
        String s = v.asString();
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        int i = s.indexOf(':');
        if (i <= 0 || i == s.length() - 1) {
            return null;
        }
        String table = s.substring(0, i);
        String key = s.substring(i + 1);
        if (key.startsWith("\"") && key.endsWith("\"") && key.length() >= 2) {
            key = key.substring(1, key.length() - 1);
        }
        if (key.startsWith("s") || key.startsWith("n") || key.startsWith("x")) {
            Object parsed = parseKeyVal(key);
            if (parsed instanceof java.math.BigDecimal bd) {
                return new AxonValue.RecordId(table, bd);
            }
            return new AxonValue.RecordId(table, AxonValue.str(String.valueOf(parsed)));
        }
        if (key.matches("\\d+")) {
            return new AxonValue.RecordId(table, new java.math.BigDecimal(key));
        }
        return new AxonValue.RecordId(table, AxonValue.str(key));
    }

    private AxonValue parseKey(String k) {
        if (k.startsWith("n")) {
            try {
                return AxonValue.num(new java.math.BigDecimal(k.substring(1)));
            } catch (NumberFormatException e) {
                return AxonValue.str(k);
            }
        }
        if (k.startsWith("s")) {
            return AxonValue.str(k.substring(1));
        }
        return AxonValue.str(k.startsWith("x") ? k.substring(1) : k);
    }

    private Document loadDoc(Database db, RecordId rid) {
        Optional<byte[]> raw = records(db).get(rid.storageKey(db.ns(), db.db()));
        if (raw.isEmpty()) {
            return null;
        }
        AxonValue record = decode(raw.get());
        return new Document(rid, record);
    }

    private byte[] encode(AxonValue v) {
        return AxonJson.encode(v);
    }

    private AxonValue decode(byte[] b) {
        return AxonJson.decode(b);
    }

    private void enforceUnique(Database db, RecordId rid, AxonValue before, AxonValue after) {
        Catalog.TableDef def = db.catalog().table(rid.table());
        if (def == null) {
            return;
        }
        for (Catalog.IndexDef ix : def.indexes().values()) {
            if (!ix.unique()) {
                continue;
            }
            String nextPrefix = uniqueIndexPrefix(db, rid.table(), ix, after);
            if (nextPrefix != null) {
                var existing = records(db).keysWithPrefix(nextPrefix);
                if (!existing.isEmpty() && existing.stream()
                    .noneMatch(key -> key.endsWith(rid.key().keyString()))) {
                    throw AxonError.internal(Messages.get("stmt_unique_index_violation", ix.name()));
                }
            }

            String previousPrefix = uniqueIndexPrefix(db, rid.table(), ix, before);
            if (previousPrefix != null) {
                records(db).delete(previousPrefix + rid.key().keyString());
            }
            if (nextPrefix != null) {
                records(db).put(nextPrefix + rid.key().keyString(), new byte[0]);
            }
        }
    }

    private String uniqueIndexPrefix(Database db, String table, Catalog.IndexDef index, AxonValue record) {
        if (record == null || !record.isObject()) {
            return null;
        }
        List<AxonValue> values = new ArrayList<>();
        for (String column : index.columns()) {
            AxonValue value = record.asObject().getOrDefault(column, AxonValue.nul());
            if (value.isNull() || value.isNone()) {
                return null;
            }
            values.add(value);
        }
        if (index.columns().size() == 1) {
            return db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000!uidx\u0000"
                + index.columns().get(0) + "\u0000" + values.get(0) + "\u0000";
        }
        return db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000!uidx\u0000"
            + index.name() + "\u0000" + AxonJson.write(AxonValue.array(values)) + "\u0000";
    }

    // ------------------------------------------------------------------
    // Índice full-text invertido
    // ------------------------------------------------------------------

    /** Atualiza as entradas full-text de um registro depois de uma mutação. */
    /**
     * Mantén o índice invertido con estatísticas BM25. Cada posting garda a
     * frecuencia do termo nese documento como valor da chave {@code FT}, e fóra
     * del mantense a frecuencia documental por termo ({@code FTDF}) e a lonxitude
     * de cada documento ({@code FTLEN}). UPDATE e DELETE retiran as entradas e
     * decrementan os contadores de forma consistente.
     */
    private void indexSearch(Database db, RecordId rid, AxonValue before, AxonValue after) {
        Catalog.TableDef table = db.catalog().table(rid.table());
        if (table == null) {
            return;
        }
        for (Catalog.IndexDef index : table.indexes().values()) {
            if (!index.search()) {
                continue;
            }
            Catalog.AnalyzerDef analyzer = db.catalog().analyzer(index.searchAnalyzer());
            if (analyzer == null) {
                continue;
            }
            if (before != null) {
                for (Map.Entry<String, Integer> e : termFrequencies(before, index.columns(), analyzer).entrySet()) {
                    records(db).delete(searchKey(db, rid.table(), index.name(), e.getKey(), rid));
                    decrementCounter(db, docFreqKey(db, rid.table(), index.name(), e.getKey()));
                }
                records(db).delete(docLenKey(db, rid.table(), index.name(), rid));
            }
            if (after != null) {
                Map<String, Integer> freq = termFrequencies(after, index.columns(), analyzer);
                int docLen = 0;
                for (Integer count : freq.values()) {
                    docLen += count;
                }
                for (Map.Entry<String, Integer> e : freq.entrySet()) {
                    records(db).put(searchKey(db, rid.table(), index.name(), e.getKey(), rid), intBytes(e.getValue()));
                    increment(db, docFreqKey(db, rid.table(), index.name(), e.getKey()));
                }
                if (docLen > 0) {
                    records(db).put(docLenKey(db, rid.table(), index.name(), rid), intBytes(docLen));
                }
            }
        }
    }

    /** Frecuencia de cada termo no texto das columnas indexadas (sen dedup). */
    private Map<String, Integer> termFrequencies(AxonValue record, List<String> columns,
                                                 Catalog.AnalyzerDef analyzer) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        if (!record.isObject()) {
            return freq;
        }
        for (String column : columns) {
            AxonValue value = record.asObject().get(column);
            if (value != null && value.isString()) {
                for (String term : analyze(value.asString(), analyzer)) {
                    freq.merge(term, 1, Integer::sum);
                }
            }
        }
        return freq;
    }

    private String searchKey(Database db, String table, String index, String term, RecordId rid) {
        return "FT|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|" + term
            + "|" + rid.key().keyString();
    }

    /** Chave de frecuencia documental: nº de documentos que conteñen o termo. */
    private String docFreqKey(Database db, String table, String index, String term) {
        return "FTDF|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|" + term;
    }

    /** Chave de lonxitude do documento no índice: tokens indexados. */
    private String docLenKey(Database db, String table, String index, RecordId rid) {
        return "FTLEN|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|"
            + rid.key().keyString();
    }

    /** Prefixo das chaves de lonxitude dun índice (para o cálculo do average). */
    private String docLenPrefix(Database db, String table, String index) {
        return "FTLEN|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|";
    }

    private static byte[] intBytes(int v) {
        return java.lang.String.valueOf(v).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int intValue(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return 0;
        }
        return Integer.parseInt(new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim());
    }

    private int intValue(Optional<byte[]> raw) {
        return intValue(raw.orElse(null));
    }

    private void increment(Database db, String key) {
        records(db).put(key, intBytes(intValue(records(db).get(key)) + 1));
    }

    private void decrementCounter(Database db, String key) {
        int value = intValue(records(db).get(key));
        if (value <= 1) {
            records(db).delete(key);
        } else {
            records(db).put(key, intBytes(value - 1));
        }
    }

    /** Mantém as entradas dos índices geográfico em grade e vetorial. */
    private void indexAdvanced(Database db, RecordId rid, AxonValue before, AxonValue after) {
        Catalog.TableDef table = db.catalog().table(rid.table());
        if (table == null) {
            return;
        }
        for (Catalog.IndexDef index : table.indexes().values()) {
            if (!index.geo() && !index.vector()) {
                continue;
            }
            String prefix = advancedPrefix(db, rid.table(), index.name(), rid);
            for (String key : records(db).keysWithPrefix(prefix)) {
                if (key.endsWith("|" + rid.key().keyString())) {
                    records(db).delete(key);
                }
            }
            String vectorKey = "VX|" + db.ns() + "|" + db.db() + "|" + rid.table() + "|"
                + index.name() + "|" + rid.key().keyString();
            records(db).delete(vectorKey);
            String graphBase = "VH|" + db.ns() + "|" + db.db() + "|" + rid.table() + "|"
                + index.name() + "|";
            for (String key : records(db).keysWithPrefix(graphBase)) {
                if (key.contains("|" + rid.key().keyString() + "|")
                    || key.endsWith("|" + rid.key().keyString())) {
                    records(db).delete(key);
                }
            }
            if (after == null || !after.isObject() || index.columns().isEmpty()) {
                continue;
            }
            AxonValue value = after.asObject().get(index.columns().get(0));
            if (index.geo()) {
                cellsUpdate(db, rid, index, value);
            }
            if (index.vector() && validVector(value, index.vectorDimension())) {
                // A primeira camada é exata: o prefixo torna o conjunto de candidatos explícito
                // e permite substituir por grafo HNSW sem mudar o formato da consulta.
                records(db).put("VX|" + db.ns() + "|" + db.db() + "|" + rid.table() + "|"
                    + index.name() + "|" + rid.key().keyString(), new byte[0]);
                linkHnsw(db, rid, index, value);
            }
        }
    }

    // ---- columnar index ----

    private void indexColumnar(Database db, RecordId rid, AxonValue before, AxonValue after) {
        Catalog.TableDef table = db.catalog().table(rid.table());
        if (table == null) return;
        for (Catalog.IndexDef index : table.indexes().values()) {
            if (!index.columnar()) continue;
            for (String column : index.columns()) {
                String prefix = com.axonbase.core.storage.ColumnarIndex.columnPrefix(
                    db.ns(), db.db(), rid.table(), index.name(), column);
                if (before != null && before.isObject()) {
                    AxonValue oldVal = before.asObject().get(column);
                    String oldKey = com.axonbase.core.storage.ColumnarIndex.columnKey(
                        db.ns(), db.db(), rid.table(), index.name(), column,
                        oldVal != null ? oldVal : com.axonbase.value.AxonValue.nul(), rid);
                    records(db).delete(oldKey);
                }
                if (after != null && after.isObject()) {
                    AxonValue newVal = after.asObject().get(column);
                    String newKey = com.axonbase.core.storage.ColumnarIndex.columnKey(
                        db.ns(), db.db(), rid.table(), index.name(), column,
                        newVal != null ? newVal : com.axonbase.value.AxonValue.nul(), rid);
                    records(db).put(newKey, new byte[0]);
                }
            }
        }
    }

    private Catalog.IndexDef columnarIndexFor(Database db, String table, Statement.Select sel) {
        if (table.isBlank()) return null;
        Catalog.TableDef def = db.catalog().table(table);
        if (def == null) return null;
        for (Catalog.IndexDef idx : def.indexes().values()) {
            if (!idx.columnar()) continue;
            if (sel.cond() instanceof Expr.Binary eq && eq.op() == Expr.BinaryOp.EQ
                && eq.left() instanceof Expr.Ident && idx.columns().contains(((Expr.Ident) eq.left()).name())) {
                return idx;
            }
        }
        return null;
    }

    private List<Document> columnarSourceDocs(Database db, Statement.Select sel) {
        return columnarSourceDocs(db, sel, List.of());
    }

    private List<Document> columnarSourceDocs(Database db, Statement.Select sel, List<Document> fallback) {
        String table = tableName(sel.from());
        Catalog.IndexDef index = columnarIndexFor(db, table, sel);
        if (index == null) return fallback;

        if (sel.cond() instanceof Expr.Binary eq && eq.op() == Expr.BinaryOp.EQ
            && eq.left() instanceof Expr.Ident field
            && index.columns().contains(field.name())
            && eq.right() instanceof Expr.Literal lit) {
            String col = field.name();
            AxonValue value = lit.value();
            String prefix = com.axonbase.core.storage.ColumnarIndex.columnPrefix(
                db.ns(), db.db(), table, index.name(), col);
            String exactPrefix = com.axonbase.core.storage.ColumnarIndex.columnKey(
                db.ns(), db.db(), table, index.name(), col, value,
                new RecordId(table, com.axonbase.value.AxonValue.str("")));
            exactPrefix = exactPrefix.substring(0, exactPrefix.length() - 1);
            int prefixLen = prefix.length();
            List<Document> docs = new ArrayList<>();
            for (String key : records(db).keysWithPrefix(exactPrefix)) {
                byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int encodedLen = com.axonbase.core.storage.ColumnarIndex.encodedDataLength(keyBytes, prefixLen);
                String rowKey = com.axonbase.core.storage.ColumnarIndex.extractRowKey(key, prefixLen, encodedLen);
                Document doc = loadDoc(db, new RecordId(table, parseKey(rowKey)));
                if (doc != null) docs.add(doc);
            }
            return docs.isEmpty() ? fallback : docs;
        }

        return fallback;
    }

private String advancedPrefix(Database db, String table, String index, RecordId rid) {
        String suffix = rid.key().keyString();
        return "SP|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|";
    }

    private boolean validVector(AxonValue value, Integer dimension) {
        return value != null && value.isArray() && value.asArray().size() == dimension
            && value.asArray().stream().allMatch(AxonValue::isNumber);
    }

    /**
     * Insere o vetor no grafo HNSW multicamado. O nível do novo nó é sorteado
     * com probabilidade {@code e^-L}; em cada nível ele é ligado aos vizinhos
     * mais próximos (limite {@code M}), com entrada global preservada na busca.
     */
    private void linkHnsw(Database db, RecordId rid, Catalog.IndexDef index, AxonValue vector) {
        String key = rid.key().keyString();
        int nodeTop = randomLevel();
        int topExisting = maxLevel(db, rid.table(), index);
        int top = Math.max(nodeTop, topExisting);
        for (int level = 0; level <= top; level++) {
            records(db).put(vhPresenceKey(db, rid.table(), index.name(), level, key), new byte[0]);
        }
        List<String> neighbors = new ArrayList<>();
        String vxBase = "VX|" + db.ns() + "|" + db.db() + "|" + rid.table() + "|" + index.name() + "|";
        for (String vk : records(db).keysWithPrefix(vxBase)) {
            String otherKey = vk.substring(vxBase.length());
            if (otherKey.equals(key)) {
                continue;
            }
            Document doc = loadDoc(db, new RecordId(rid.table(), parseKey(otherKey)));
            if (doc == null || !doc.current().isObject()) {
                continue;
            }
            AxonValue candidate = doc.current().asObject().get(index.columns().get(0));
            if (validVector(candidate, index.vectorDimension())) {
                neighbors.add(otherKey);
            }
        }
for (int level = 0; level <= top; level++) {
            List<Neighbor> levelNearest = new ArrayList<>();
            for (String otherKey : neighbors) {
                Document otherDoc = loadDoc(db, new RecordId(rid.table(), parseKey(otherKey)));
                if (otherDoc == null || !otherDoc.current().isObject()) {
                    continue;
                }
                AxonValue otherVec = otherDoc.current().asObject().get(index.columns().get(0));
                if (!validVector(otherVec, index.vectorDimension())) {
                    continue;
                }
                levelNearest.add(new Neighbor(otherKey,
                    vectorDistance(index.vectorDistance(), vector, otherVec)));
            }
            levelNearest.sort(java.util.Comparator.comparingDouble(Neighbor::distance));
            int maxLinks = Math.max(4, index.hnswM());
            int linkLimit = level == 0 ? maxLinks : Math.max(2, maxLinks / 2);
            for (Neighbor nb : levelNearest.stream().limit(linkLimit).toList()) {
                records(db).put(vhNodePrefix(db, rid.table(), index.name(), level, key) + nb.key(), new byte[0]);
                records(db).put(vhNodePrefix(db, rid.table(), index.name(), level, nb.key()) + key, new byte[0]);
            }
            String entryKey = vhEntryKey(db, rid.table(), index.name(), level);
            if (records(db).get(entryKey).isEmpty()) {
                records(db).put(entryKey, key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    /** Sorteia o nível topo de um novo nó (probabilidade e^-L por camada). */
    private int randomLevel() {
        int level = 0;
        while (level < 24 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.3678794412) {
            level++;
        }
        return level;
    }

    private record Neighbor(String key, double distance) {
    }

    private double vectorDistance(String distance, AxonValue left, AxonValue right) {
        return switch (distance == null ? "euclidean" : distance.toLowerCase(java.util.Locale.ROOT)) {
            case "cosine" -> 1d - GeoVector.cosine(left, right).asDouble();
            case "manhattan" -> GeoVector.manhattan(left, right).asDouble();
            default -> GeoVector.euclidean(left, right).asDouble();
        };
    }

    private String searchPrefix(Database db, String table, String index, String term) {
        return "FT|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|" + term + "|";
    }

    private List<String> analyze(String text, Catalog.AnalyzerDef analyzer) {
        String normalized = analyzer.lowercase() ? text.toLowerCase(java.util.Locale.ROOT) : text;
        java.util.Set<String> stops = analyzer.stopwords().stream()
            .map(word -> analyzer.lowercase() ? word.toLowerCase(java.util.Locale.ROOT) : word)
            .collect(java.util.stream.Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (String term : normalized.split("[^\\p{L}\\p{N}_]+")) {
            if (term.isBlank() || stops.contains(term)) {
                continue;
            }
            out.add(analyzer.stemming() ? stem(term) : term);
        }
        return out;
    }

    /** Stemmer leve para português/inglês; reduz sufixos frequentes sem dependência externa. */
    private String stem(String term) {
        for (String suffix : List.of("mente", "ções", "ção", "ing", "es", "os", "as", "s")) {
            if (term.length() > suffix.length() + 2 && term.endsWith(suffix)) {
                return term.substring(0, term.length() - suffix.length());
            }
        }
        return term;
    }

    private List<String> cols(List<String> columns) {
        return columns;
    }

    /** Valor de resposta dende un documento tras unha mutación. */
    private AxonValue output(ReturnSpec ret, AxonValue value) {
        if (ret == null) {
            return value;
        }
        return switch (ret.kind()) {
            case NONE -> AxonValue.nul();
            case BEFORE, DIFF, EXPR -> value;
            default -> value;
        };
    }

    // ---- AI AUDIT ----

    private com.axonbase.core.audit.AiAuditCatalog auditCatalog() {
        Database db = db();
        return db.auditCatalog();
    }

    private boolean isAuditCommand(Statement s) {
        return s instanceof Statement.DefineAiAudit
            || s instanceof Statement.DropAiAudit
            || s instanceof Statement.ShowAiAudit
            || s instanceof Statement.SetReasonAudit
            || s instanceof Statement.SetAuditCase;
    }

    private AxonValue runSelectAuditCases(Statement.Select sel) {
        Database db = db();
        List<Document> docs = auditCasesDocs(db);
        // Apply WHERE filter
        if (sel.cond() != null) {
            docs.removeIf(doc -> !truthy(evalCond(sel.cond(), doc)));
        }
        // Apply ORDER BY
        if (!sel.orders().isEmpty()) {
            var cmp = orderComparator(sel.orders());
            docs.sort(cmp);
        }
        // Apply LIMIT/START
        int start = sel.start() != null ? (int) eval(sel.start()).asLong() : 0;
        int limit = sel.limit() != null ? (int) eval(sel.limit()).asLong() : docs.size();
        int stop = Math.min(docs.size(), start + limit);
        List<AxonValue> out = new ArrayList<>();
        for (int i = Math.min(start, docs.size()); i < stop; i++) {
            out.add(project(sel, docs.get(i)));
        }
        if (out.size() == 1 && (sel.only() || (sel.from() != null && isSingleTarget(sel.from())))) {
            return out.get(0);
        }
        return AxonValue.array(out);
    }

    private AxonValue runDefineAiAudit(Statement.DefineAiAudit aa) {
        if (!ds.aiProvider().isConfigured()) {
            throw errorStmt(Messages.get("audit_not_configured"));
        }
        Database db = db();
        String schema = buildSchemaDesc(db);
        String rulesJson;
        try {
            rulesJson = ds.aiProvider().generateRules(aa.warningWhen(), aa.dangerWhen(), db);
        } catch (Exception e) {
            throw errorStmt(Messages.get("audit_rule_gen_error", e.getMessage()));
        }
        // Parse JSON rules
        java.util.List<com.axonbase.core.audit.AiAuditRule> warningRules = new java.util.ArrayList<>();
        java.util.List<com.axonbase.core.audit.AiAuditRule> dangerRules = new java.util.ArrayList<>();
        parseRules(rulesJson, warningRules, dangerRules);

        String userId = sessionUserId();
        var def = new com.axonbase.core.audit.AiAuditDef(
            aa.name(), aa.warningWhen(), aa.dangerWhen(),
            warningRules, dangerRules, System.currentTimeMillis(), userId);
        db.catalog().defineAudit(def);
        control(db, ControlCommand.Kind.AUDIT, aa.name(), com.axonbase.parser.Render.stmt(aa));
        return AxonValue.object(java.util.Map.of(
            "name", AxonValue.str(aa.name()),
            "rules", AxonValue.str(rulesJson),
            "status", AxonValue.str("CREATED")));
    }

    private AxonValue runDropAiAudit(Statement.DropAiAudit da) {
        Database db = db();
        if (!db.catalog().removeAudit(da.name())) {
            throw errorStmt(Messages.get("audit_not_found", da.name()));
        }
        control(db, ControlCommand.Kind.AUDIT, da.name(), com.axonbase.parser.Render.stmt(da));
        return AxonValue.nul();
    }

    private AxonValue runShowAiAudit(Statement.ShowAiAudit sa) {
        Database db = db();
        var def = db.catalog().audit(sa.name());
        if (def == null) {
            throw errorStmt(Messages.get("audit_not_found", sa.name()));
        }
        Map<String, AxonValue> info = new java.util.LinkedHashMap<>();
        info.put("name", AxonValue.str(def.name()));
        info.put("warning_prompt", AxonValue.str(def.warningPrompt()));
        info.put("danger_prompt", AxonValue.str(def.dangerPrompt()));
        info.put("warning_rules", AxonValue.str(def.warningRules().toString()));
        info.put("danger_rules", AxonValue.str(def.dangerRules().toString()));
        info.put("created_at", AxonValue.num(def.createdAt()));
        info.put("created_by", AxonValue.str(def.createdBy()));
        return AxonValue.object(info);
    }

    private AxonValue runSetReasonAudit(Statement.SetReasonAudit sra) {
        var cat = auditCatalog();
        var auditCase = cat.getCase(sra.hash());
        if (auditCase == null) {
            throw errorStmt(Messages.get("audit_case_not_found", sra.hash()));
        }
        if (!"PENDING".equals(auditCase.status())) {
            throw errorStmt(Messages.get("audit_case_resolved", auditCase.status()));
        }
        auditCase.setReason(sra.reason(), sessionUserId());
        return AxonValue.object(java.util.Map.of(
            "hash", AxonValue.str(sra.hash()),
            "status", AxonValue.str("REASON_SET")));
    }

    private AxonValue runSetAuditCase(Statement.SetAuditCase sac) {
        var cat = auditCatalog();
        var auditCase = cat.getCase(sac.hash());
        if (auditCase == null) {
            throw errorStmt(Messages.get("audit_case_not_found", sac.hash()));
        }
        if (!"PENDING".equals(auditCase.status())) {
            throw errorStmt(Messages.get("audit_case_resolved", auditCase.status()));
        }
        String userId = sessionUserId();
        auditCase.resolve(sac.status(), userId, sac.reason());
        cat.addEvent(sac.hash(), new com.axonbase.core.audit.AuditCaseEvent(
            sac.status().equals("AUTHORIZED") ? "AUTHORIZED" : "DENIED", userId, sac.reason(), System.currentTimeMillis()));

        // If DANGER case was authorized, unblock user
        if ("AUTHORIZED".equals(sac.status()) && "DANGER".equals(auditCase.classification())) {
            cat.unblockUser(auditCase.userId());
        }

        // If AUTHORIZED, execute the original SQL
        if ("AUTHORIZED".equals(sac.status())) {
            try {
                String originalSql = auditCase.sql();
                // Execute the original SQL that was blocked - bypass audit to avoid recursion
                var exec = new Executor(ds);
                exec.bypassAudit = true;
                exec.rawSql = originalSql;
                Query q = com.axonbase.parser.AxonQl.parse(originalSql);
                return exec.execute(q, session, null);
            } catch (Exception e) {
                throw errorStmt(Messages.get("audit_exec_error", e.getMessage()));
            }
        }

        return AxonValue.object(java.util.Map.of(
            "hash", AxonValue.str(sac.hash()),
            "status", AxonValue.str(sac.status())));
    }

    // ---- AUDIT_CASES virtual table ----

    private boolean isAuditCasesTable(String table) {
        return "AUDIT_CASES".equalsIgnoreCase(table);
    }

    private List<Document> auditCasesDocs(Database db) {
        var cat = auditCatalog();
        List<Document> docs = new java.util.ArrayList<>();
        // Pending cases
        for (var auditCase : cat.listCases()) {
            docs.add(auditCaseToDocument(auditCase));
        }
        // Resolved cases from history
        for (var auditCase : cat.listHistory()) {
            docs.add(auditCaseToDocument(auditCase));
        }
        return docs;
    }

    private Document auditCaseToDocument(com.axonbase.core.audit.AuditCase auditCase) {
        Map<String, AxonValue> obj = new java.util.LinkedHashMap<>();
        obj.put("hash", AxonValue.str(auditCase.hash()));
        obj.put("user", AxonValue.str(auditCase.userId()));
        obj.put("sql", AxonValue.str(auditCase.sql()));
        obj.put("audit_name", AxonValue.str(auditCase.auditName()));
        obj.put("classification", AxonValue.str(auditCase.classification()));
        obj.put("status", AxonValue.str(auditCase.status()));
        obj.put("reason", AxonValue.str(auditCase.reason()));
        obj.put("created_at", AxonValue.num(auditCase.createdAt()));
        obj.put("resolved_by", AxonValue.str(auditCase.resolvedBy()));
        obj.put("resolution_note", AxonValue.str(auditCase.resolutionNote()));
        obj.put("resolved_at", AxonValue.num(auditCase.resolvedAt()));
        // Events as JSON array
        var eventsArr = new java.util.ArrayList<AxonValue>();
        for (var evt : auditCase.events()) {
            eventsArr.add(AxonValue.object(java.util.Map.of(
                "type", AxonValue.str(evt.type()),
                "user", AxonValue.str(evt.userId()),
                "note", AxonValue.str(evt.note()),
                "timestamp", AxonValue.num(evt.timestamp()))));
        }
        obj.put("events", AxonValue.array(eventsArr));
        return new Document(new RecordId(VIRTUAL_TABLE, AxonValue.str(auditCase.hash())), AxonValue.object(obj));
    }

    // ---- AUDIT_LOG virtual table ----

    private boolean isAuditLogTable(String table) {
        return "AUDIT_LOG".equalsIgnoreCase(table);
    }

    private AxonValue runSelectAuditLog(Statement.Select sel) {
        Database db = db();
        List<AxonValue> entries = ds.readAuditEntries(db.ns(), db.db());
        java.util.Collections.reverse(entries);
        List<Document> docs = new java.util.ArrayList<>();
        for (AxonValue entry : entries) {
            AxonValue id = entry.asObject().get("id");
            String idStr = id != null ? id.asString() : "0";
            RecordId recordId = new RecordId("audit_log", AxonValue.str(idStr));
            docs.add(new Document(recordId, entry));
        }
        if (sel.cond() != null) {
            docs.removeIf(doc -> !truthy(evalCond(sel.cond(), doc)));
        }
        if (!sel.orders().isEmpty()) {
            var cmp = orderComparator(sel.orders());
            docs.sort(cmp);
        }
        int start = sel.start() != null ? (int) eval(sel.start()).asLong() : 0;
        int limit = sel.limit() != null ? (int) eval(sel.limit()).asLong() : docs.size();
        int stop = Math.min(docs.size(), start + limit);
        List<AxonValue> out = new ArrayList<>();
        for (int i = Math.min(start, docs.size()); i < stop; i++) {
            out.add(sel.only() ? docs.get(i).current() : project(sel, docs.get(i)));
        }
        if (!sel.only()) {
            out.replaceAll(this::applyDataRuleMask);
        }
        if (out.size() == 1 && (sel.only() || isSingleTarget(sel.from()))) {
            return out.get(0);
        }
        return AxonValue.array(out);
    }

    // ---- AI Audit SQL Interception ----

    /**
     * Intercepta SQL classificado como WARNING ou DANGER por AI Audit.
     * Chamado antes de executar a query.
     */
    private AxonValue interceptWithAiAudit(String sql, Statement s) {
        if (isAuditCommand(s)) return null; // audit commands pass through
        Database db = db();
        String userId = sessionUserId();
        if (userId == null || userId.isBlank()) return null; // no authenticated user

        // Check if user is blocked
        var cat = auditCatalog();
        if (cat.isBlocked(userId)) {
            String reason = cat.getBlockedReason(userId);
            throw new com.axonbase.common.AxonError(-32002, Messages.get("audit_blocked", reason));
        }

        // Find which audit rules apply to this user
        // Check user's auditName
        String auditName = null;
        var auth = session.auth();
        if (auth != null && auth.isObject()) {
            AxonValue auditVal = auth.asObject().get("audit");
            if (auditVal != null && auditVal.isString()) {
                auditName = auditVal.asString();
            }
        }

        if (auditName == null || auditName.isBlank()) return null;

        var def = db.catalog().audit(auditName);
        if (def == null) return null;

        String classification = def.classify(sql);
        if ("SAFE".equals(classification)) return null;

        // Generate hash
        String hash = sha256Hex(sql + System.currentTimeMillis() + userId);
        var auditCase = new com.axonbase.core.audit.AuditCase(hash, userId, sql, auditName, classification, System.currentTimeMillis());

        if ("DANGER".equals(classification)) {
            // Block the user
            cat.blockUser(userId, auditName, sql, "DANGER: " + sql, sessionUserId());
            cat.addCase(hash, auditCase);
            throw new com.axonbase.common.AxonError(-32002, Messages.get("audit_danger", auditName));
        }

        // WARNING - create pending case
        cat.addCase(hash, auditCase);
        throw new com.axonbase.common.AxonError(-32003,
            "{\"status\":\"WARNING\",\"hash\":\"" + hash + "\",\"message\":\""
                + Messages.get("audit_warning") + "\"}");
    }

    // ---- helpers ----

    private String buildSchemaDesc(Database db) {
        var catalog = db.catalog();
        StringBuilder sb = new StringBuilder();
        for (var table : catalog.tables()) {
            sb.append("Table ").append(table.name()).append(": ");
            sb.append("columns [");
            boolean first = true;
            for (var field : table.fields().values()) {
                if (!first) sb.append(", ");
                sb.append(field.name()).append("(").append(field.type()).append(")");
                first = false;
            }
            sb.append("]; ");
        }
        if (sb.isEmpty()) sb.append("(empty)");
        return sb.toString();
    }

    private String sessionUserId() {
        AxonValue auth = session.auth();
        if (auth != null && auth.isObject()) {
            AxonValue user = auth.asObject().get("user");
            if (user != null && user.isString()) return user.asString();
            AxonValue id = auth.asObject().get("id");
            if (id != null && id.isString()) return id.asString();
        }
        return "anonymous";
    }

    private String sha256Hex(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.currentTimeMillis());
        }
    }
private void parseRules(String json, java.util.List<com.axonbase.core.audit.AiAuditRule> warningRules,
                            java.util.List<com.axonbase.core.audit.AiAuditRule> dangerRules) {
        try {
            AxonValue parsed = com.axonbase.value.AxonJson.parseDocument(json);
            if (parsed.isObject()) {
                AxonValue warning = parsed.asObject().get("warning");
                if (warning != null && warning.isArray()) {
                    for (AxonValue rule : warning.asArray()) {
                        warningRules.add(parseRule(rule));
                    }
                }
                AxonValue danger = parsed.asObject().get("danger");
                if (danger != null && danger.isArray()) {
                    for (AxonValue rule : danger.asArray()) {
                        dangerRules.add(parseRule(rule));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("audit_parse_error", e.getMessage()) + " JSON: " + json);
        }
    }

    private com.axonbase.core.audit.AiAuditRule parseRule(AxonValue rule) {
        if (!rule.isObject()) throw new RuntimeException(Messages.get("audit_invalid_rule", rule));
        var obj = rule.asObject();
        String type = obj.getOrDefault("type", AxonValue.str("")).asString();
        String pattern = obj.containsKey("pattern") ? obj.get("pattern").asString() : null;
        java.util.List<String> commands = null;
        if (obj.containsKey("commands") && obj.get("commands").isArray()) {
            commands = new java.util.ArrayList<>();
            for (AxonValue cmd : obj.get("commands").asArray()) {
                commands.add(cmd.asString());
            }
        }
        java.util.List<String> words = null;
        if (obj.containsKey("words") && obj.get("words").isArray()) {
            words = new java.util.ArrayList<>();
            for (AxonValue w : obj.get("words").asArray()) {
                words.add(w.asString());
            }
        }
        Boolean requireWhere = obj.containsKey("require_where") ? obj.get("require_where").asBool() : null;
        return new com.axonbase.core.audit.AiAuditRule(type, pattern, commands, words, requireWhere);
    }
}
