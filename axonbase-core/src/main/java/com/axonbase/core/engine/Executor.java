package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.catalog.Document;
import com.axonbase.core.catalog.RecordId;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.Transaction;
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
import com.axonbase.parser.ast.Statement.Info;
import com.axonbase.parser.ast.Statement.Kill;
import com.axonbase.parser.ast.Statement.OrderTerm;
import com.axonbase.parser.ast.Statement.ReturnKind;
import com.axonbase.parser.ast.Statement.ReturnSpec;
import com.axonbase.parser.ast.Statement.UpdateMode;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public Executor(Datastore ds) {
        this.ds = ds;
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
            result = runStatement(s);
        }
        return result;
    }

    private Database db() {
        return ds.requireDatabase(session);
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
        if (from instanceof com.axonbase.parser.ast.Expr.RecordId rid
            && rid.table() instanceof com.axonbase.parser.ast.Expr.Ident tid) {
            return tid.name();
        }
        return "";
    }

    private Catalog.TableDef ensureTable(Database db, String name) {
        Catalog.TableDef def = db.catalog().table(name);
        if (def == null) {
            def = new Catalog.TableDef(name, false, false);
            db.catalog().defineTable(def);
        }
        return def;
    }

    private AxonValue runStatement(Statement s) {
        return switch (s) {
            case Statement.Use u -> runUse(u);
            case Statement.Let l -> runLet(l);
            case Statement.Create c -> runCreate(c);
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
            case Kill k -> runKill(k);
            case Statement.ErrorStmt ee -> throw errorStmt(eval(ee.message()).asString());
            case Statement.Begin ignored -> runBegin();
            case Statement.Commit ignored -> runCommit();
            case Statement.Cancel ignored -> runCancel();
        };
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
            throw errorStmt("LIVE SELECT precisa de uma tabela como origem");
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
            default -> throw errorStmt("escopo INFO desconhecido: " + inf.kind());
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
            throw errorStmt("INFO FOR NAMESPACE exige namespace selecionado");
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
            throw errorStmt("INFO FOR TABLE exige o nome da tabela");
        }
        Catalog.TableDef def = db().catalog().table(table);
        if (def == null) {
            throw errorStmt("tabela não definida: " + table);
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
        String table = tableOf(c.target());
        ensureTable(db, table);
        // CREATE person:ana ... fixa a chave do registro; sem chave, gera-se um UUID
        AxonValue explicitKey = c.target() instanceof Expr.RecordId rid
            ? recordKey(rid.key()) : null;
        AxonValue record = createRecord(db, table, evalData(c.data()), explicitKey);
        return c.only() ? record : AxonValue.array(List.of(record));
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
        AxonValue record = AxonValue.object(obj);
        enforceUnique(db, rid, null, record);
        records(db).put(rid.storageKey(db.ns(), db.db()), encode(record));
        indexSearch(db, rid, null, record);
        indexAdvanced(db, rid, null, record);
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
                    throw AxonError.internal("tipo inválido para o campo '"
                        + fieldName + "': esperado " + fd.type() + ", recebido " + value.toString());
                }
                obj.put(fieldName, coerced);
                value = coerced;
            }
            if (present && value != null && fd.assertExpr() != null) {
                AxonValue ok = evalWithValue(fd.assertExpr(), value);
                if (!truthy(ok)) {
                    throw AxonError.internal("assert falhou no campo '" + fieldName + "' com " + value);
                }
            }
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
            case "datetime" -> v.isDatetime() ? v : null;
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
            return AxonValue.num(Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
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
            if (!allowed(doc, tableName(u.target()), "update")) {
                continue;
            }
            Map<String, AxonValue> current = new LinkedHashMap<>(doc.current().asObject());
            if (u.data() != null) {
                applyMutate(current, u.data(), doc);
            }
            applyFieldSchema(db, tableName(u.target()), current, true);
            AxonValue next = AxonValue.object(current);
            doc.setCurrent(next);
            enforceUnique(db, rid, doc.initial(), next);
            records(db).put(rid.storageKey(db.ns(), db.db()), encode(next));
            indexSearch(db, rid, doc.initial(), next);
            indexAdvanced(db, rid, doc.initial(), next);
            notifyLive(db, rid, LiveBus.UPDATE, doc.initial(), next);
            fireEvents(db, rid, LiveBus.UPDATE, doc.initial(), next);
            if (u.ret() != null && u.ret().kind() == ReturnKind.BEFORE) {
                results.add(doc.initial());
            } else {
                results.add(next);
            }
        }
        if (u.mode() == UpdateMode.UPSERT && results.isEmpty()
            && u.data() != null && u.target() instanceof Expr.Ident idTarget) {
            String table = idTarget.name();
            ensureTable(db, table);
            results.add(createRecord(db, table, evalData(u.data())));
        }
        return retNone ? AxonValue.none() : AxonValue.array(results);
    }

    private AxonValue runDelete(Statement.Delete d) {
        Database db = db();
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
            if (!allowed(doc, tableName(d.target()), "delete")) {
                continue;
            }
            records(db).delete(rid.storageKey(db.ns(), db.db()));
            indexSearch(db, rid, doc.current(), null);
            indexAdvanced(db, rid, doc.current(), null);
            notifyLive(db, rid, LiveBus.DELETE, doc.current(), null);
            fireEvents(db, rid, LiveBus.DELETE, doc.current(), null);
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
                    if (a.path() instanceof Expr.Ident id) {
                        current.put(id.name(), evalExpr(a.value()));
                    } else if (a.path() instanceof Expr.Idiom idiom) {
                        current.put(idiomToString(idiom), evalExpr(a.value()));
                    }
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
        Database db = db();
        List<Document> docs = vectorSourceDocs(db, sel,
            geoSourceDocs(db, sel, searchSourceDocs(db, sel)));
        if (sel.cond() != null) {
            docs.removeIf(doc -> !truthy(evalCond(sel.cond(), doc)));
        }
        applySearchScores(sel, docs);
        docs.removeIf(doc -> !allowed(doc, tableName(sel.from()), "select"));
        List<OrderTerm> orders = sel.orders();
        if (!orders.isEmpty()) {
            Comparator<Document> cmp = orderComparator(orders);
            docs.sort(cmp);
        }
        int start = sel.start() != null ? (int) eval(sel.start()).asLong() : 0;
        int limit = sel.limit() != null ? (int) eval(sel.limit()).asLong() : docs.size();
        int stop = Math.min(docs.size(), start + limit);
        List<AxonValue> out = new ArrayList<>();
        if (!sel.fetch().isEmpty()) {
            docs.forEach(d -> fetchDocs(d, sel.fetch()));
        }
        boolean hasAgg = sel.fields().stream().anyMatch(this::isAggregateCall);
        if (hasAgg) {
            out.addAll(aggregate(sel, docs));
        } else {
            for (int i = Math.min(start, docs.size()); i < stop; i++) {
                out.add(project(sel, docs.get(i)));
            }
        }
        if (out.size() == 1 && (sel.only() || isSingleTarget(sel.from()))) {
            return out.get(0);
        }
        return AxonValue.array(out);
    }

    /**
     * Para ORDER BY vector::distance::* usa somente as chaves do índice
     * vetorial. Nesta etapa a ordenação é exata; a camada de chaves permite
     * trocar a seleção por HNSW sem alterar a linguagem nem os registros.
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
        String prefix = "VX|" + db.ns() + "|" + db.db() + "|" + table.name() + "|" + index.name() + "|";
        AxonValue query = call.args().size() > 1 ? eval(call.args().get(1)) : AxonValue.nul();
        if (!validVector(query, index.vectorDimension())) {
            return fallback;
        }
        List<String> all = records(db).keysWithPrefix(prefix);
        if (all.isEmpty()) {
            return new ArrayList<>();
        }
        int wanted = sel.limit() == null ? 10 : Math.max(1, (int) eval(sel.limit()).asLong());
        int budget = Math.max(32, wanted * 8);
        java.util.Set<String> candidateIds = hnswCandidates(db, table.name(), index, all.get(0).substring(prefix.length()),
            query, budget);
        List<Document> docs = new ArrayList<>();
        for (String id : candidateIds) {
            Document doc = loadDoc(db, new RecordId(table.name(), parseKey(id)));
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    /** Busca best-first na camada HNSW, limitada a um orçamento de candidatos. */
    private java.util.Set<String> hnswCandidates(Database db, String table, Catalog.IndexDef index,
                                                  String entry, AxonValue query, int budget) {
        java.util.PriorityQueue<Neighbor> frontier = new java.util.PriorityQueue<>(Comparator.comparingDouble(Neighbor::distance));
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        frontier.add(new Neighbor(entry, vectorNodeDistance(db, table, index, entry, query)));
        while (!frontier.isEmpty() && seen.size() < budget) {
            Neighbor current = frontier.poll();
            if (!seen.add(current.key())) {
                continue;
            }
            for (String edge : records(db).keysWithPrefix(hnswPrefix(db, table, index.name(), current.key()))) {
                String next = edge.substring(edge.lastIndexOf('|') + 1);
                if (!seen.contains(next)) {
                    frontier.add(new Neighbor(next, vectorNodeDistance(db, table, index, next, query)));
                }
            }
        }
        return seen;
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
        int minX = (int) Math.floor((point[0] - lonRange + 180) * 10);
        int maxX = (int) Math.floor((point[0] + lonRange + 180) * 10);
        int minY = (int) Math.floor((point[1] - latRange + 90) * 10);
        int maxY = (int) Math.floor((point[1] + latRange + 90) * 10);
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        String base = "SP|" + db.ns() + "|" + db.db() + "|" + table.name() + "|" + index.name() + "|";
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (String key : records(db).keysWithPrefix(base + x + ":" + y + "|")) {
                    ids.add(key.substring(key.lastIndexOf('|') + 1));
                }
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

    /** Calcula uma relevância simples (ocorrências dos termos) para search::score/highlight. */
    private void applySearchScores(Statement.Select sel, List<Document> docs) {
        if (!(sel.cond() instanceof Expr.Binary match) || match.op() != Expr.BinaryOp.MATCH
            || !(match.left() instanceof Expr.Ident field)) {
            return;
        }
        AxonValue query = eval(match.right());
        if (!query.isString()) {
            return;
        }
        List<String> terms = java.util.Arrays.stream(query.asString().toLowerCase(java.util.Locale.ROOT)
            .split("[^\\p{L}\\p{N}_]+"))
            .filter(term -> !term.isBlank()).toList();
        for (Document doc : docs) {
            AxonValue value = fieldValue(doc.current(), field.name());
            String text = value.isString() ? value.asString().toLowerCase(java.util.Locale.ROOT) : "";
            double score = 0;
            for (String term : terms) {
                int from = 0;
                while ((from = text.indexOf(term, from)) >= 0) {
                    score++;
                    from += term.length();
                }
            }
            doc.searchScore(score);
            doc.searchTerms(terms);
        }
    }

    /** Usa o índice invertido quando o WHERE é {@code campo @@ "termos"}. */
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
        List<Document> docs = new ArrayList<>();
        for (RecordId rid : resolveTargets(from)) {
            Document doc = loadDoc(db, rid);
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
                        Expr.Call c = (Expr.Call) f;
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
                Expr.Call c = (Expr.Call) f;
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
        return AxonValue.nul();
    }

    private AxonValue runDefineField(DefineField df) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, df.table());
        def.fields().put(df.name(),
            new Catalog.FieldDef(df.name(), df.type(), df.readonly(), df.assertExpr(), df.defaultExpr()));
        return AxonValue.nul();
    }

    private AxonValue runDefineIndex(DefineIndex di) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, di.table());
        if (di.searchAnalyzer() != null && db.catalog().analyzer(di.searchAnalyzer()) == null) {
            throw errorStmt("analyzer não definido: " + di.searchAnalyzer());
        }
        def.indexes().put(di.name(), new Catalog.IndexDef(di.name(), di.columns(), di.unique(), di.count(),
            di.searchAnalyzer(), di.geo(), di.vectorDimension(), di.vectorDistance()));
        // índices podem ser criados depois dos registros: indexa o estado atual
        if (di.searchAnalyzer() != null || di.geo() || di.vectorDimension() != null) {
            for (RecordId rid : resolveTargets(new Expr.Ident(di.table()))) {
                Document doc = loadDoc(db, rid);
                if (doc != null) {
                    indexSearch(db, rid, null, doc.current());
                    indexAdvanced(db, rid, null, doc.current());
                }
            }
        }
        return AxonValue.nul();
    }

    private AxonValue runDefineAnalyzer(Statement.DefineAnalyzer da) {
        db().catalog().defineAnalyzer(new Catalog.AnalyzerDef(da.name(), da.lowercase(),
            da.stopwords(), da.stemming()));
        return AxonValue.nul();
    }

    private AxonValue runDefineEvent(DefineEvent de) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, de.table());
        String when = de.when() == null ? "" : com.axonbase.parser.Render.expr(de.when());
        def.events().put(de.name(), new Catalog.EventDef(de.name(), when, de.when(), de.then()));
        return AxonValue.nul();
    }

    /** Define uma identidade que o servidor pode autenticar por signin. */
    private AxonValue runDefineUser(Statement.DefineUser du) {
        String ns = du.scope() == Statement.AuthScope.ROOT ? null
            : du.namespace() != null ? du.namespace() : session.namespace();
        String database = du.scope() == Statement.AuthScope.DATABASE
            ? du.database() != null ? du.database() : session.database() : null;
        if (du.scope() != Statement.AuthScope.ROOT && (ns == null || ns.isBlank())) {
            throw errorStmt("DEFINE USER no escopo NAMESPACE/DATABASE exige namespace selecionado");
        }
        if (du.scope() == Statement.AuthScope.DATABASE && (database == null || database.isBlank())) {
            throw errorStmt("DEFINE USER ON DATABASE exige banco selecionado");
        }
        AxonValue password = eval(du.password());
        ds.authCatalog().defineUser(du.name(), authScope(du.scope()), ns, database,
            password.isString() ? password.asString() : password.toString(), du.roles());
        return AxonValue.nul();
    }

    /** Define um access method nomeado, limitado a um escopo. */
    private AxonValue runDefineAccess(Statement.DefineAccess da) {
        String ns = da.scope() == Statement.AuthScope.ROOT ? null
            : da.namespace() != null ? da.namespace() : session.namespace();
        String database = da.scope() == Statement.AuthScope.DATABASE
            ? da.database() != null ? da.database() : session.database() : null;
        if (da.scope() != Statement.AuthScope.ROOT && (ns == null || ns.isBlank())) {
            throw errorStmt("DEFINE ACCESS no escopo NAMESPACE/DATABASE exige namespace selecionado");
        }
        if (da.scope() == Statement.AuthScope.DATABASE && (database == null || database.isBlank())) {
            throw errorStmt("DEFINE ACCESS ON DATABASE exige banco selecionado");
        }
        ds.authCatalog().defineAccess(da.name(), authScope(da.scope()), ns, database);
        return AxonValue.nul();
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
        String table = literalIdent(rid.table());
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
                Database db = db();
                Catalog.TableDef def = db.catalog().table(id.name());
                yield AxonValue.table(def != null ? def.name() : id.name());
            }
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
                // o id do documento em memória pode ter sido serializado como texto,
                // por isso preferimos o RecordId tipado que o Document já carrega
                return ridAsValue(doc.id());
            }
            return fieldValue(doc.current(), id.name());
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
            case SUB -> AxonValue.num(num(l, r, 0).subtract(num(r, l, 0)));
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
        // número 3 == float 3.0
        if (a.isNumber() && b.isNumber()) {
            return a.compareTo(b) == 0;
        }
        return a.equals(b);
    }

    private AxonValue add(AxonValue l, AxonValue r) {
        if (l.isString() || r.isString()) {
            return AxonValue.str(l.toString() + r.toString());
        }
        return AxonValue.num(num(l, r, 0).add(num(r, l, 0)));
    }

    private AxonValue divide(AxonValue l, AxonValue r) {
        if (num(r, l, 1).signum() == 0) {
            throw AxonError.internal("división por cero");
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
        AxonValue result = Functions.call(name, args);
        if (result != null) {
            return result;
        }
        throw AxonError.internal("função desconhecida: " + name);
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
        if (e instanceof Expr.Literal l && l.value().isString()) {
            return l.value().asString();
        }
        throw AxonError.internal("esperábase un identificador, atopouse " + (e == null ? "null" : e.getClass().getSimpleName()));
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
            List<String> columns = ix.columns();
            for (String col : columns) {
                AxonValue v = after.isObject() ? after.asObject().getOrDefault(col, AxonValue.nul()) : AxonValue.nul();
                if (!v.isNull() && !v.isNone()) {
                    String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + rid.table() + "\u0000!uidx\u0000" + col + "\u0000" + v + "\u0000";
                    var existing = records(db).keysWithPrefix(prefix);
                    if (!existing.isEmpty()) {
                        String holderKey = existing.get(0);
                        if (!holderKey.endsWith(rid.key().toString())) {
                            throw AxonError.internal("violación de índice único na columna '" + col + "'");
                        }
                    }
                    records(db).put(prefix + rid.key(), new byte[0]);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Índice full-text invertido
    // ------------------------------------------------------------------

    /** Atualiza as entradas full-text de um registro depois de uma mutação. */
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
                for (String term : searchTerms(before, index.columns(), analyzer)) {
                    records(db).delete(searchKey(db, rid.table(), index.name(), term, rid));
                }
            }
            if (after != null) {
                for (String term : searchTerms(after, index.columns(), analyzer)) {
                    records(db).put(searchKey(db, rid.table(), index.name(), term, rid), new byte[0]);
                }
            }
        }
    }

    private String searchKey(Database db, String table, String index, String term, RecordId rid) {
        return "FT|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|" + term
            + "|" + rid.key().keyString();
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
                String cell = geoCell(value);
                if (cell != null) {
                    records(db).put("SP|" + db.ns() + "|" + db.db() + "|" + rid.table() + "|"
                        + index.name() + "|" + cell + "|" + rid.key().keyString(), new byte[0]);
                }
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

    private String advancedPrefix(Database db, String table, String index, RecordId rid) {
        String suffix = rid.key().keyString();
        return "SP|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|";
    }

    private boolean validVector(AxonValue value, Integer dimension) {
        return value != null && value.isArray() && value.asArray().size() == dimension
            && value.asArray().stream().allMatch(AxonValue::isNumber);
    }

    /**
     * Camada zero do HNSW: cada novo vetor é ligado aos oito vizinhos mais
     * próximos. A busca usa esta malha como entrada de candidatos; a ordenação
     * posterior continua exata para os candidatos encontrados.
     */
    private void linkHnsw(Database db, RecordId rid, Catalog.IndexDef index, AxonValue vector) {
        String base = "VX|" + db.ns() + "|" + db.db() + "|" + rid.table() + "|" + index.name() + "|";
        List<Neighbor> nearest = new ArrayList<>();
        for (String key : records(db).keysWithPrefix(base)) {
            String otherKey = key.substring(base.length());
            if (otherKey.equals(rid.key().keyString())) {
                continue;
            }
            RecordId other = new RecordId(rid.table(), parseKey(otherKey));
            Document doc = loadDoc(db, other);
            if (doc == null || !doc.current().isObject()) {
                continue;
            }
            AxonValue candidate = doc.current().asObject().get(index.columns().get(0));
            if (!validVector(candidate, index.vectorDimension())) {
                continue;
            }
            double distance = vectorDistance(index.vectorDistance(), vector, candidate);
            nearest.add(new Neighbor(otherKey, distance));
        }
        nearest.sort(Comparator.comparingDouble(Neighbor::distance));
        String nodePrefix = hnswPrefix(db, rid.table(), index.name(), rid.key().keyString());
        for (String key : records(db).keysWithPrefix(nodePrefix)) {
            records(db).delete(key);
        }
        for (Neighbor neighbor : nearest.stream().limit(8).toList()) {
            records(db).put(nodePrefix + neighbor.key(), new byte[0]);
            records(db).put(hnswPrefix(db, rid.table(), index.name(), neighbor.key())
                + rid.key().keyString(), new byte[0]);
        }
    }

    private record Neighbor(String key, double distance) {
    }

    private String hnswPrefix(Database db, String table, String index, String key) {
        return "VH|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|" + key + "|";
    }

    private double vectorDistance(String distance, AxonValue left, AxonValue right) {
        return switch (distance == null ? "euclidean" : distance.toLowerCase(java.util.Locale.ROOT)) {
            case "cosine" -> 1d - GeoVector.cosine(left, right).asDouble();
            case "manhattan" -> GeoVector.manhattan(left, right).asDouble();
            default -> GeoVector.euclidean(left, right).asDouble();
        };
    }

    /** Grade de 0,1 grau: suficiente para pré-filtrar as consultas de raio. */
    private String geoCell(AxonValue geometry) {
        if (geometry == null || !geometry.isObject()) {
            return null;
        }
        AxonValue coordinates = geometry.asObject().get("coordinates");
        if (coordinates == null || !coordinates.isArray() || coordinates.asArray().size() < 2
            || !coordinates.asArray().get(0).isNumber() || !coordinates.asArray().get(1).isNumber()) {
            return null;
        }
        return (int) Math.floor((coordinates.asArray().get(0).asDouble() + 180) * 10) + ":"
            + (int) Math.floor((coordinates.asArray().get(1).asDouble() + 90) * 10);
    }

    private String searchPrefix(Database db, String table, String index, String term) {
        return "FT|" + db.ns() + "|" + db.db() + "|" + table + "|" + index + "|" + term + "|";
    }

    private java.util.Set<String> searchTerms(AxonValue record, List<String> columns,
                                               Catalog.AnalyzerDef analyzer) {
        java.util.Set<String> terms = new java.util.LinkedHashSet<>();
        if (!record.isObject()) {
            return terms;
        }
        for (String column : columns) {
            AxonValue value = record.asObject().get(column);
            if (value != null && value.isString()) {
                terms.addAll(analyze(value.asString(), analyzer));
            }
        }
        return terms;
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
}
