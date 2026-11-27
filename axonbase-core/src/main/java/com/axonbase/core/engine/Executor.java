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
    /** Sessão que responde por {@code $auth} e {@code $token} quando não é a corrente (assinante de uma live query). */
    private Session identitySession;
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
            webVars.keySet().forEach(session::requireDefinable);
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

    /** O próximo SELECT executado é a parte de um shard numa consulta espalhada (veja {@link ShardPartial}). */
    private boolean partialRequested;

    /** Executa um SELECT em modo parcial, com as mesmas permissões e Data Rules de um SELECT comum. */
    public AxonValue executePartial(Statement.Select select, Session scope, Map<String, AxonValue> webVars) {
        this.session = scope;
        if (webVars != null) {
            webVars.keySet().forEach(scope::requireDefinable);
            webVars.forEach(scope.vars()::set);
        }
        if (!bypassAudit && rawSql != null) {
            interceptWithAiAudit(rawSql, select);
        }
        partialRequested = true;
        try {
            return runStatement(select);
        } finally {
            partialRequested = false;
        }
    }

    /** Uma agregação vazia deste SELECT, onde o gateway funde os grupos exportados pelos shards. */
    GroupAggregation partialAggregation(Statement.Select select, Session scope) {
        this.session = scope;
        return new GroupAggregation(select, groupRowEval(), this::fieldName);
    }

    /** Avalia uma expressão fora de um statement: o gateway de shards calcula assim chaves e dados de escrita. */
    public AxonValue evaluate(com.axonbase.parser.ast.Expr expr, Session scope) {
        this.session = scope;
        return eval(expr);
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
     * O valor das duas chaves é o objeto de propriedades da aresta, ou vazio quando não há nenhuma. As novas se
     * somam às já gravadas, então repetir o RELATE não apaga propriedade.
     */
    private void persistEdge(com.axonbase.core.catalog.Database db, AxonValue.RecordId from,
                             String kind, AxonValue.RecordId to, Map<String, AxonValue> properties) {
        String outKey = edgeKey(db, "out", from, kind, to);
        Map<String, AxonValue> merged = new LinkedHashMap<>(edgeProperties(db, outKey));
        merged.putAll(properties);
        byte[] value = merged.isEmpty() ? new byte[0] : encode(AxonValue.object(merged));
        records(db).put(outKey, value);
        records(db).put(edgeKey(db, "in", to, kind, from), value);
    }

    private Map<String, AxonValue> edgeProperties(com.axonbase.core.catalog.Database db, String key) {
        Optional<byte[]> raw = records(db).get(key);
        if (raw.isEmpty() || raw.get().length == 0) {
            return Map.of();
        }
        AxonValue stored = decode(raw.get());
        return stored.isObject() ? stored.asObject() : Map.of();
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
        return com.axonbase.core.partition.PartitionedKv.wrap(db, rawRecords(db), partitions, ds.coldTier().records());
    }

    /** O que o executor sabe das partições durante esta execução. */
    private final com.axonbase.core.partition.PartitionContext partitions =
        new com.axonbase.core.partition.PartitionContext();

    private KvBackend rawRecords(Database db) {
        // Ponto único de acesso ao armazenamento: aqui a consulta cancelada (ou que estourou o timeout) para.
        QueryContext.checkCancelled();
        Transaction tx = session.tx();
        if (tx != null && tx.isOpen()) {
            return tx;
        }
        KvBackend direct = db.records();
        com.axonbase.core.storage.StatementBatch current = batch;
        return current != null && current.base() == direct ? current : direct;
    }

    /** Escritas do statement em curso, gravadas de uma vez (um lote atômico) quando não há transação explícita. */
    private com.axonbase.core.storage.StatementBatch batch;

    private static boolean batchable(Statement s) {
        return s instanceof Statement.Create || s instanceof Statement.Insert || s instanceof Statement.Update
            || s instanceof Statement.Delete || s instanceof Statement.Relate || s instanceof Statement.Unrelate;
    }

    /**
     * Roda um statement de escrita com todas as gravações (registro, índices, arestas) num único lote atômico.
     * Dentro de transação explícita, sem banco selecionado ou em backend sem lote, segue o caminho direto.
     */
    private AxonValue runBatched(Statement s) {
        Transaction tx = session.tx();
        if (batch != null || (tx != null && tx.isOpen()) || session.namespace() == null || session.database() == null) {
            return runStatementUnbatched(s);
        }
        KvBackend direct;
        try {
            direct = db().records();
        } catch (RuntimeException noDatabase) {
            return runStatementUnbatched(s);
        }
        if (!(direct instanceof com.axonbase.core.storage.RocksDbBackend rocks)) {
            return runStatementUnbatched(s);
        }
        for (int attempt = 1; ; attempt++) {
            com.axonbase.core.storage.StatementBatch mine = new com.axonbase.core.storage.StatementBatch(rocks);
            batch = mine;
            long outbound = ExternalCalls.count();
            try {
                AxonValue result = runStatementUnbatched(s);
                batch = null;
                mine.commit();
                return result;
            } catch (com.axonbase.core.storage.VersionConflictException conflict) {
                // só um lote com condições (tabela particionada) conflita. Nada foi gravado, então refaz sobre o
                // estado novo, a menos que o statement já tenha chamado para fora
                if (!mine.conditional() || attempt >= CONDITIONAL_ATTEMPTS || ExternalCalls.count() != outbound) {
                    throw conflict;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.ThreadLocalRandom.current()
                    .nextLong(1_000_000L * Math.min(attempt, 10)));
            } finally {
                batch = null;
                mine.discard();
            }
        }
    }

    /** Tentativas de um statement avulso quando outro confirma antes sobre as mesmas linhas particionadas. */
    private static final int CONDITIONAL_ATTEMPTS = 100;

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
        // como na Data Rule: sem o dado do token, NONE = NONE casaria e liberaria o acesso
        if (lacksParameterValue(cond, TOKEN_PARAMETERS)) {
            return false;
        }
        Document target = doc;
        if (target == null) {
            target = emptyDoc();
        }
        Document row = target;
        return truthy(asPolicy(() -> evalExprInDoc(cond, row)));
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

    /**
     * Aplica papéis e escopo (E0). Só restringe sessões autenticadas: sessão sem auth é o
     * modo embutido ou servidor sem autenticação.
     */
    private void authorize(Statement s) {
        AxonValue auth = session.auth();
        if (!com.axonbase.core.security.Authz.authenticated(auth)) {
            return;
        }
        var min = com.axonbase.core.security.Authz.Role.ADMIN;
        boolean needs = s instanceof Statement.DefineUser || s instanceof Statement.CreateUser
            || s instanceof Statement.DropUser || s instanceof Statement.DefineAccess
            || s instanceof Statement.GrantAccess || s instanceof Statement.RevokeAccess
            || s instanceof Statement.CreateJks || s instanceof Statement.CreateDataRule
            || s instanceof Statement.DropDataRule || s instanceof Statement.DefineAiAudit
            || s instanceof Statement.DropAiAudit || s instanceof DefineDatabaseLink
            || s instanceof DropDatabaseLink || s instanceof Statement.CreateDatabaseLink
            || s instanceof Statement.AlterDatabaseLink || s instanceof CreateSaga
            || s instanceof Statement.DropSaga
            // o corpo do evento roda na sessão de quem dispara a escrita, inclusive a do root
            || s instanceof DefineEvent || s instanceof Statement.CreateEvent
            || s instanceof Statement.DropEvent;
        if (s instanceof Statement.SetAuditCase) {
            min = com.axonbase.core.security.Authz.Role.OPERATOR;
            needs = true;
        }
        // CANCEL SAGA: papel explícito somente leitura (viewer) não pode cancelar. Tokens sem papel
        // reconhecido (os dos SDKs, escopo database) continuam cancelando as próprias sagas.
        if (s instanceof Statement.CancelSaga
            && com.axonbase.core.security.Authz.role(auth) == com.axonbase.core.security.Authz.Role.VIEWER) {
            throw new com.axonbase.common.AxonError(-32002,
                Messages.get("auth_role_required", "operator"));
        }
        if (needs && !com.axonbase.core.security.Authz.atLeast(auth, min)) {
            throw new com.axonbase.common.AxonError(-32002,
                Messages.get("auth_role_required", min.name().toLowerCase(java.util.Locale.ROOT)));
        }
        // definir ou remover usuário muda a atribuição de auditoria, mesmo quando o texto não a cita
        if (com.axonbase.core.security.Authz.audited(auth)
            && (s instanceof Statement.DefineAiAudit || s instanceof Statement.DropAiAudit
                || s instanceof Statement.DefineUser || s instanceof Statement.CreateUser
                || s instanceof Statement.DropUser)) {
            throw refusedToAudited();
        }
    }

    private static com.axonbase.common.AxonError refusedToAudited() {
        return new com.axonbase.common.AxonError(-32002, Messages.get("audit_user_restricted"));
    }

    /** Um usuário nunca cria identidade de escopo maior que o seu, nem fora do seu ns/db. */
    private void authorizeUserScope(Statement.AuthScope target, String ns, String database) {
        authorizeScope(target, ns, database, "auth_scope_escalation");
    }

    /** Nenhuma sessão autenticada age sobre algo de escopo maior que o seu, nem fora do seu ns/db. */
    private void authorizeScope(Statement.AuthScope target, String ns, String database, String messageKey) {
        AxonValue auth = session.auth();
        if (!com.axonbase.core.security.Authz.authenticated(auth)) {
            return;
        }
        var caller = com.axonbase.core.security.Authz.scope(auth);
        if (caller == com.axonbase.core.security.AuthCatalog.Scope.ROOT) {
            return;
        }
        boolean broader = target.ordinal() < caller.ordinal();
        boolean outside = !java.util.Objects.equals(ns, com.axonbase.core.security.Authz.ns(auth))
            || (caller == com.axonbase.core.security.AuthCatalog.Scope.DATABASE
                && !java.util.Objects.equals(database, com.axonbase.core.security.Authz.db(auth)));
        if (broader || outside) {
            throw new com.axonbase.common.AxonError(-32002, Messages.get(messageKey));
        }
    }

    private AxonValue runStatement(Statement s) {
        if (!batchable(s)) {
            return runStatementUnbatched(s);
        }
        // a escrita segura o lado de leitura: um bloco da construção de índice nunca vê um statement pela metade
        Database locked = session.namespace() == null || session.database() == null ? null
            : ds.findDatabase(session.namespace(), session.database());
        if (locked == null) {
            return runBatched(s);
        }
        java.util.concurrent.locks.Lock read = locked.indexLock().readLock();
        read.lock();
        try {
            return runBatched(s);
        } finally {
            read.unlock();
        }
    }

    private AxonValue runStatementUnbatched(Statement s) {
        // um statement novo pode ver dados que o anterior mudou: o cache de subqueries não atravessa statements
        subqueryCache.clear();
        authorize(s);
        requireWritableIfNeeded(s);
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
            case Statement.Unrelate u -> runUnrelate(u);
            case DefineTable dt -> runDefineTable(dt);
            case DefineField df -> runDefineField(df);
            case DefineIndex di -> runDefineIndex(di);
            case Statement.DefineAnalyzer da -> runDefineAnalyzer(da);
            case Statement.DefineEmbedding de -> runDefineEmbedding(de);
            case Statement.DropEmbedding de -> runDropEmbedding(de);
            case Statement.Reembed re -> runReembed(re);
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
            case Statement.Partition p -> runPartition(p.command());
            case Statement.CreateTable ct -> runCreateTable(ct);
            case Statement.ShowAiAudit sa -> runShowAiAudit(sa);
            case Statement.SetReasonAudit sra -> runSetReasonAudit(sra);
            case Statement.SetAuditCase sac -> runSetAuditCase(sac);
            // New SQL-standard records -> delegate to existing run methods
            case Statement.DropTable dt -> runRemoveTable(
                new Statement.RemoveTable(dt.name()));
            case Statement.AlterTable at -> runAlterTable(at);
            case Statement.CreateIndex ci -> runDefineIndex(
                new Statement.DefineIndex(ci.name(), ci.table(), ci.columns(), ci.unique(), ci.count(),
                    ci.searchAnalyzer(), ci.geo(), ci.columnar(), ci.vectorDimension(), ci.vectorDistance(),
                    ci.m(), ci.efConstruction(), ci.efSearch()));
            case Statement.DropIndex di -> runDropIndex(di);
            case Statement.CreateEvent ce -> runDefineEvent(
                new Statement.DefineEvent(ce.name(), ce.table(), ce.when(), ce.then()));
            case Statement.DropEvent de -> runDropEvent(de);
            case Statement.CreateAnalyzer ca -> runDefineAnalyzer(
                new Statement.DefineAnalyzer(ca.name(), ca.lowercase(), ca.stopwords(), ca.stemming()));
            case Statement.DropAnalyzer da -> runDropAnalyzer(da);
            case Statement.CreateUser cu -> runDefineUser(
                new Statement.DefineUser(cu.name(), cu.scope(), cu.namespace(), cu.database(),
                    cu.password(), cu.passhash(), cu.certificate(), cu.fingerprint(),
                    cu.roles(), cu.dataRules(), cu.auditName()));
            case Statement.DropUser du -> runDropUser(du);
            case Statement.GrantAccess ga -> runDefineAccess(
                new Statement.DefineAccess(ga.name(), ga.scope(), ga.namespace(), ga.database()));
            case Statement.RevokeAccess ra -> runRevokeAccess(ra);
            case Statement.CreateDatabaseLink cdl -> runDefineDatabaseLink(
                new Statement.DefineDatabaseLink(cdl.name(), cdl.url(), cdl.ns(), cdl.db(), cdl.user(), cdl.password()));
            case Statement.AlterDatabaseLink adl -> runDefineDatabaseLink(
                new Statement.DefineDatabaseLink(adl.name(), adl.url(), adl.ns(), adl.db(), adl.user(), adl.password()));
            case Statement.DropSaga ds -> runDropSaga(ds);
            case Statement.CreateFunction p -> runProgram(p);
            case Statement.CreateProcedure p -> runProgram(p);
            case Statement.CreatePackage p -> runProgram(p);
            case Statement.DropProgram p -> runProgram(p);
            case Statement.CallProgram p -> runProgram(p);
        };
    }

    /** AxonPL: CREATE/DROP FUNCTION|PROCEDURE|PACKAGE e CALL (a lógica fica em {@code core.pl}). */
    private AxonValue runProgram(Statement s) {
        return ds.programs().run(s, session, this::eval);
    }

    /** Sessão somente leitura: só passam instruções de leitura (subconsultas e blocos também passam por aqui). */
    private void requireWritableIfNeeded(Statement s) {
        if (!session.readOnly() || Datastore.isReadOnly(s)
            || s instanceof Statement.Let || s instanceof Statement.IfElse
            || s instanceof Statement.ErrorStmt || s instanceof Statement.Savepoint
            || s instanceof Statement.Release || s instanceof Statement.RollbackTo) {
            return;
        }
        throw new com.axonbase.common.AxonError(-32002, Messages.get("session_read_only"));
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
        if (com.axonbase.core.security.Authz.hasDataRules(session.auth())) {
            // a notificação não passa pelo filtro nem pela máscara
            throw new com.axonbase.common.AxonError(-32002, Messages.get("data_rule_live_refused"));
        }
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
            // a sessão pode ter trocado de usuário depois do LIVE SELECT
            if (sub.owner() != null && com.axonbase.core.security.Authz.hasDataRules(sub.owner().auth())) {
                continue;
            }
            Document doc = new Document(rid, candidate);
            AxonValue payload;
            // $auth e $token da consulta são os do assinante, não os de quem escreveu
            Session previousIdentity = identitySession;
            identitySession = sub.owner();
            try {
                if (sub.select().cond() != null && !truthy(evalExprInDoc(sub.select().cond(), doc))) {
                    continue;
                }
                if (sub.diff() && !LiveBus.DELETE.equals(action)) {
                    payload = diffOf(before, after);
                } else {
                    payload = project(sub.select(), doc);
                }
            } finally {
                identitySession = previousIdentity;
            }
            LiveBus.Notification note = new LiveBus.Notification(sub.id(), action, payload);
            if (session.inTransaction()) {
                session.queueLive(() -> bus.deliver(sub, note));
            } else if (batch != null) {
                batch.afterCommit(() -> bus.deliver(sub, note));
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
        // o que um evento escreve é escrita nova, mesmo quando quem o disparou está repondo dados
        boolean restoring = session.restoring();
        session.restoring(false);
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
            session.restoring(restoring);
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
        // O escopo do token vale também para o USE: calcula o destino antes de aplicar.
        String ns = u.ns() != null ? literalIdent(u.ns()) : session.namespace();
        String db = u.db() != null ? literalIdent(u.db()) : session.database();
        if (!com.axonbase.core.security.Authz.allows(session.auth(), ns, db)) {
            throw new com.axonbase.common.AxonError(-32002, Messages.get("rpc_scope_forbidden"));
        }
        if (u.ns() != null) {
            session.namespace(ns);
        }
        if (u.db() != null) {
            session.database(db);
        }
        return AxonValue.nul();
    }

    private AxonValue runLet(Statement.Let l) {
        session.requireDefinable(l.name());
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
            case "function", "procedure", "package" -> ds.programs().info(session, inf.kind(), inf.table());
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

    /** Tipo principal do índice para o INFO: UNIQUE, SEARCH, HNSW, GEO, COLUMNAR, COUNT ou INDEX. */
    private static String indexKind(Catalog.IndexDef i) {
        if (i.unique()) {
            return "UNIQUE";
        }
        if (i.search()) {
            return "SEARCH";
        }
        if (i.vector()) {
            return "HNSW";
        }
        if (i.geo()) {
            return "GEO";
        }
        if (i.columnar()) {
            return "COLUMNAR";
        }
        return i.count() ? "COUNT" : "INDEX";
    }

    private AxonValue infoDatabase() {
        Database db = db();
        List<String> tables = db.catalog().tables().stream().map(Catalog.TableDef::name).sorted().toList();
        Map<String, AxonValue> detail = new LinkedHashMap<>();
        for (String table : tables) {
            detail.put(table, tableInfo(db.catalog().table(table)));
        }
        Map<String, AxonValue> analyzers = new LinkedHashMap<>();
        db.catalog().analyzers().stream().sorted(Comparator.comparing(Catalog.AnalyzerDef::name)).forEach(a ->
            analyzers.put(a.name(), AxonValue.object(Map.of(
                "lowercase", AxonValue.bool(a.lowercase()),
                "stemming", AxonValue.bool(a.stemming()),
                "stopwords", AxonValue.array(strings(a.stopwords()))))));
        Map<String, AxonValue> embeddings = new LinkedHashMap<>();
        db.catalog().embeddings().stream().sorted(Comparator.comparing(Catalog.EmbeddingDef::name)).forEach(e -> {
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("provider", AxonValue.str("http"));
            m.put("url", AxonValue.str(e.url()));
            m.put("model", AxonValue.str(e.model()));
            m.put("dimension", AxonValue.num(e.dimension()));
            // só o NOME da variável de ambiente; a chave nunca sai do servidor
            m.put("key_env", e.keyEnv() == null ? AxonValue.nul() : AxonValue.str(e.keyEnv()));
            m.put("timeout", AxonValue.num(e.timeoutSeconds()));
            m.put("batch", AxonValue.num(e.batch()));
            m.put("send_dimension", AxonValue.bool(e.sendDimension()));
            m.put("document_prefix", e.documentPrefix() == null ? AxonValue.nul() : AxonValue.str(e.documentPrefix()));
            m.put("query_prefix", e.queryPrefix() == null ? AxonValue.nul() : AxonValue.str(e.queryPrefix()));
            m.put("auth_header", e.authHeader() == null ? AxonValue.nul() : AxonValue.str(e.authHeader()));
            embeddings.put(e.name(), AxonValue.object(m));
        });
        Map<String, AxonValue> info = new LinkedHashMap<>();
        info.put("namespace", AxonValue.str(db.ns()));
        info.put("database", AxonValue.str(db.db()));
        info.put("tables", AxonValue.array(strings(tables)));
        info.put("details", AxonValue.object(detail));
        info.put("analyzers", AxonValue.object(analyzers));
        info.put("embeddings", AxonValue.object(embeddings));
        info.putAll(ds.programs().summary(db));
        return AxonValue.object(info);
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
            if (f.embeddingName() != null) {
                info.put("embedding", AxonValue.object(Map.of(
                    "name", AxonValue.str(f.embeddingName()),
                    "from", AxonValue.str(f.embeddingFrom()))));
            }
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
            info.put("columnar", AxonValue.bool(i.columnar()));
            info.put("kind", AxonValue.str(indexKind(i)));
            info.put("state", AxonValue.str(i.ready() ? "ready" : "building"));
            info.put("processed", AxonValue.num(i.built()));
            if (i.vector()) {
                info.put("hnsw", AxonValue.object(Map.of(
                    "m", AxonValue.num(i.hnswM()),
                    "efc", AxonValue.num(i.hnswEfc()),
                    "efs", AxonValue.num(i.hnswEfs()))));
            }
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
        if (def.shardBy() != null) {
            out.put("shard_by", AxonValue.str(def.shardBy()));
        }
        out.put("fields", AxonValue.object(fields));
        out.put("indexes", AxonValue.object(indexes));
        if (!def.indexFailures().isEmpty()) {
            Map<String, AxonValue> failed = new LinkedHashMap<>();
            new java.util.TreeMap<>(def.indexFailures()).forEach((k, v) -> failed.put(k, AxonValue.str(v)));
            out.put("index_failures", AxonValue.object(failed));
        }
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
        AxonValue record = createRecord(db, table, evalData(c.data(), table), explicitKey, onExisting(false));
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
        AxonValue shown = visible(record);
        return c.only() ? shown : AxonValue.array(List.of(shown));
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
        List<AxonValue> rows = data.isArray() ? data.asArray() : List.of(data);
        for (AxonValue row : rows) {
            if (row.isObject()) {
                AxonValue record = createRecord(db, i.table(), row, null, onExisting(i.ignore()));
                if (record != null) {
                    records.add(record);
                }
            }
        }
        records.replaceAll(this::visible);
        return output(i.ret(), AxonValue.array(records));
    }

    /** O que uma criação faz quando o id já existe. */
    private enum Existing { REFUSE, SKIP, REPLACE }

    private Existing onExisting(boolean ignore) {
        if (ignore) {
            return Existing.SKIP;
        }
        return session.restoring() ? Existing.REPLACE : Existing.REFUSE;
    }

    /** O registro que já está sob este id, ou {@code null}. Num lote, o commit confere que ninguém o criou no meio. */
    private AxonValue existingRecord(Database db, RecordId rid) {
        if (rawRecords(db) instanceof com.axonbase.core.storage.StatementBatch pending) {
            pending.expect(rid.storageKey(db.ns(), db.db()));
        }
        Document found = loadDoc(db, rid);
        return found == null ? null : found.current();
    }

    private AxonValue evalData(Data data, String table) {
        return switch (data) {
            case Data.Content c -> eval(c.content());
            case Data.SetClause sc -> {
                Map<String, AxonValue> m = new LinkedHashMap<>();
                for (Assignment a : sc.assignments()) {
                    write(m, assignmentPath(a.path(), table, m), eval(a.value()));
                }
                yield AxonValue.object(m);
            }
            case Data.Merge m -> eval(m.merge());
            case Data.Patch p -> {
                // numa criação a lista de operações parte de um documento vazio
                AxonValue patch = eval(p.patch());
                yield patch.isArray() ? RecordChanges.patch(AxonValue.object(Map.of()), patch.asArray()) : patch;
            }
            case Data.Replace r -> eval(r.replace());
            case null -> AxonValue.object(Map.of());
        };
    }

    /**
     * Os passos do caminho de um SET: nomes de campo e posições de lista. {@code tabela.campo} é a coluna, a menos que
     * o registro tenha um campo com o nome da tabela.
     */
    private List<Object> assignmentPath(Expr path, String table, Map<String, AxonValue> record) {
        List<Object> steps = new ArrayList<>();
        if (path instanceof Expr.Ident id) {
            steps.add(id.name());
        } else if (path instanceof Expr.Idiom idiom && idiom.base() instanceof Expr.Ident base) {
            steps.add(base.name());
            for (Expr.Part part : idiom.parts()) {
                if (part instanceof Expr.Part.Field field) {
                    steps.add(field.name());
                } else if (part instanceof Expr.Part.Index index) {
                    steps.add(listPosition(eval(index.index())));
                } else {
                    throw errorStmt(Messages.get("stmt_path_unsupported", com.axonbase.parser.Render.expr(path)));
                }
            }
        } else {
            throw errorStmt(Messages.get("stmt_path_unsupported", com.axonbase.parser.Render.expr(path)));
        }
        if (steps.size() > 1 && steps.get(0).equals(table) && !record.containsKey(table)
            && steps.get(1) instanceof String) {
            steps.remove(0);
        }
        return steps;
    }

    private Integer listPosition(AxonValue value) {
        if (!value.isNumber() || value.asLong() < 0 || value.asLong() > Integer.MAX_VALUE) {
            throw errorStmt(Messages.get("stmt_path_index", value));
        }
        return (int) value.asLong();
    }

    private static void write(Map<String, AxonValue> record, List<Object> path, AxonValue value) {
        Map<String, AxonValue> changed = RecordChanges.set(AxonValue.object(record), path, value).asObject();
        record.clear();
        record.putAll(changed);
    }

    /**
     * Grava um registro novo, ou substitui o existente quando {@code existing} é {@code REPLACE}. Devolve
     * {@code null} quando o id já existe e a criação o pula.
     */
    private AxonValue createRecord(Database db, String table, AxonValue input, AxonValue explicitKey,
                                   Existing existing) {
        Map<String, AxonValue> obj = new LinkedHashMap<>();
        if (input.isObject()) {
            obj.putAll(input.asObject());
        }
        Object idKey = obj.containsKey("id") ? unwrapId(obj.remove("id")) : null;
        AxonValue key = explicitKey != null ? explicitKey : resolveKey(idKey);
        RecordId rid = new RecordId(table, key);
        // um id recém-gerado não existe em lugar algum: não precisa ser procurado
        boolean generated = explicitKey == null && idKey == null;
        AxonValue previous = generated ? null : existingRecord(db, rid);
        if (previous != null) {
            // antes de pular ou de dizer que existe: sobre um registro oculto pela regra a recusa é sempre a mesma
            refuseHiddenRecord(db, rid);
        }
        if (previous != null && existing == Existing.SKIP) {
            return null;
        }
        obj.put("id", ridAsValue(rid));
        applyFieldSchema(db, table, obj, false);
        injectDataRuleFields(obj);
        AxonValue record = AxonValue.object(obj);
        Document document = new Document(rid, record);
        ds.auditLog(session, "CREATE", table, rid, null, record);
        if (!dataRuleFilter(document) || !allowed(document, table, "create")) {
            throw errorStmt("permission denied");
        }
        // depois da permissão: quem não pode criar não fica sabendo se o id existe
        if (previous != null && existing == Existing.REFUSE) {
            throw errorStmt(Messages.get("stmt_record_exists", ridAsValue(rid).asRecordId()));
        }
        checkConstraints(db, rid, obj);
        bindPartition(db, rid, record, generated);
        if (previous == null && !generated && db.catalog().partitioning(table) != null) {
            // numa tabela particionada outra criação pode ter confirmado entre a procura e a escolha da partição
            previous = existingRecord(db, rid);
            if (previous != null) {
                refuseHiddenRecord(db, rid);
            }
            if (previous != null && existing != Existing.REPLACE) {
                if (existing == Existing.SKIP) {
                    return null;
                }
                throw errorStmt(Messages.get("stmt_record_exists", ridAsValue(rid).asRecordId()));
            }
        }
        enforcePrimaryKey(db, rid, previous, record);
        enforceUnique(db, rid, previous, record);
        records(db).put(rid.storageKey(db.ns(), db.db()), encode(record));
        indexSearch(db, rid, previous, record);
        indexAdvanced(db, rid, previous, record);
        indexColumnar(db, rid, previous, record);
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
        applyFieldSchema(db, table, obj, update, null);
    }

    private void applyFieldSchema(Database db, String table, Map<String, AxonValue> obj, boolean update,
                                  AxonValue before) {
        Catalog.TableDef def = db.catalog().table(table);
        if (def == null) {
            return;
        }
        autoEmbed(db, def, obj, before);
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
            case "int" -> v.isNumber() ? wholeNumber(v)
                : v.isString() ? tryLong(v.asString()) : null;
            case "float", "number" -> v.isNumber() ? AxonValue.num(v.asDouble())
                : v.isString() ? tryDouble(v.asString()) : null;
            case "string" -> v.isString() ? v : AxonValue.str(Functions.text(v));
            case "bool" -> v.isBool() ? v : null;
            case "datetime" -> v.isDatetime() ? v
                : v.isString() ? tryInstant(v.asString()) : null;
            case "array" -> v.isArray() ? v : null;
            case "object" -> v.isObject() ? v : null;
            case "geometry" -> isGeometry(v) ? v : null;
            case "vector" -> v.isArray() && v.asArray().stream().allMatch(AxonValue::isNumber) ? v : null;
            default -> vectorTypeSize(t) > 0
                ? (v.isArray() && v.asArray().size() == vectorTypeSize(t)
                    && v.asArray().stream().allMatch(AxonValue::isNumber) ? v : null)
                : v;
        };
    }

    private boolean isGeometry(AxonValue value) {
        return value.isObject() && value.asObject().get("type") != null
            && value.asObject().get("type").isString() && value.asObject().get("coordinates") != null;
    }

    /** O número como inteiro, ou {@code null} quando há fração: um campo int não trunca em silêncio. */
    private AxonValue wholeNumber(AxonValue v) {
        try {
            return AxonValue.num(v.asDecimal().longValueExact());
        } catch (ArithmeticException | NumberFormatException notWhole) {
            return null;
        }
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
                    // Sem o passo registrado não há como compensar: a falha aborta a escrita.
                    Datastore.DatabaseLinkDef linkDef = ds.databaseLink(session.namespace(), q.link());
                    if (linkDef != null) {
                        String selectSql = "SELECT * FROM " + q.table() + ":"
                            + com.axonbase.parser.Render.key(qRid.key());
                        List<AxonValue> rows = linkClient.query(linkDef, selectSql);
                        AxonValue before = rows.isEmpty() ? AxonValue.nul() : rows.get(0);
                        sagaLedger.recordStep(sagaCorr, linkDef.ns(), linkDef.db(), session.namespace(), q.link(),
                            q.table(), new RecordId(q.table(), recordKey(qRid.key())), before, "UPDATE");
                    }
                }
                return runWriteViaLink(q, com.axonbase.parser.Render.stmt(u));
            }
        }
        boolean retNone = u.ret() != null && u.ret().kind() == ReturnKind.NONE;
        if (u.target() instanceof Expr.Ident table) {
            ensureTable(db, table.name());
        }
        List<RecordId> targets;
        try (var scope = prunePartitions(u.target(), u.cond())) {
            targets = resolveMutationTargets(db, u.target(), u.cond());
        }
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
                // Sem o passo registrado não há como compensar: a falha aborta a escrita.
                sagaLedger.recordStep(sagaCorr, db.ns(), db.db(), "", "local", rid.table(), rid, doc.current(), "UPDATE");
            }
            Map<String, AxonValue> current = new LinkedHashMap<>(doc.current().asObject());
            if (u.data() != null) {
                applyMutate(current, u.data(), doc);
            }
            keepRecordId(current, rid, doc.initial());
            applyFieldSchema(db, tableName(u.target()), current, true, doc.initial());
            injectDataRuleFields(current);
            checkConstraints(db, rid, current);
            AxonValue next = AxonValue.object(current);
            doc.setCurrent(next);
            bindPartition(db, rid, next, false);
            enforcePrimaryKey(db, rid, doc.initial(), next);
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
                results.add(createRecord(db, table, evalData(u.data(), table), null, Existing.REPLACE));
            } else if (u.target() instanceof Expr.RecordId rid
                && rid.table() instanceof Expr.Ident ridTable) {
                String table = ridTable.name();
                ensureTable(db, table);
                results.add(createRecord(db, table, evalData(u.data(), table), recordKey(rid.key()), Existing.REPLACE));
            }
        }
        results.replaceAll(this::visible);
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
                    // Sem o passo registrado não há como compensar: a falha aborta a escrita.
                    Datastore.DatabaseLinkDef linkDef = ds.databaseLink(session.namespace(), q.link());
                    if (linkDef != null) {
                        String selectSql = "SELECT * FROM " + q.table() + ":"
                            + com.axonbase.parser.Render.key(dqRid.key());
                        List<AxonValue> rows = linkClient.query(linkDef, selectSql);
                        AxonValue before = rows.isEmpty() ? AxonValue.nul() : rows.get(0);
                        sagaLedger.recordStep(sagaCorr, linkDef.ns(), linkDef.db(), session.namespace(), q.link(),
                            q.table(), new RecordId(q.table(), recordKey(dqRid.key())), before, "DELETE");
                    }
                }
                return runWriteViaLink(q, com.axonbase.parser.Render.stmt(d));
            }
        }
        List<RecordId> targets;
        try (var scope = prunePartitions(d.target(), d.cond())) {
            targets = resolveMutationTargets(db, d.target(), d.cond());
        }
        List<AxonValue> deleted = new ArrayList<>();
        boolean edges = !targets.isEmpty() && hasAnyEdge(db);
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
            enforcePrimaryKey(db, rid, doc.current(), null);
            enforceUnique(db, rid, doc.current(), null);
            if (edges) {
                removeEdgesOf(db, rid);
            }
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
        deleted.replaceAll(this::visible);
        return deleted.isEmpty() ? AxonValue.array(List.of()) : AxonValue.array(deleted);
    }

    /**
     * O id é a identidade do registro: CONTENT e REPLACE não o tiram, e nenhuma escrita o troca. O guardado fica (um
     * registro que o perdeu o recebe de volta, pela chave).
     */
    private void keepRecordId(Map<String, AxonValue> current, RecordId rid, AxonValue before) {
        AxonValue stored = before.asObject().get("id");
        if (stored != null && (stored.isNull() || stored.isNone())) {
            stored = null;
        }
        AxonValue given = current.get("id");
        if (given != null && !given.isNull() && !given.isNone() && !given.equals(stored) && !refersTo(given, rid)) {
            throw errorStmt(Messages.get("stmt_record_id_immutable", ridAsValue(rid).asRecordId(), given));
        }
        current.put("id", stored != null ? stored : ridAsValue(rid));
    }

    /** O valor nomeia este registro: o record id, o texto {@code tabela:chave} ou a própria chave, no mesmo tipo. */
    private boolean refersTo(AxonValue value, RecordId rid) {
        AxonValue.RecordId self = ridAsValue(rid).asRecordId();
        if (value.isRecordId()) {
            return value.asRecordId().equals(self);
        }
        if (value.isString()) {
            return value.asString().equals(self.toString())
                || (rid.key().isString() && value.asString().equals(rid.key().asString()));
        }
        return value.isNumber() && rid.key().isNumber() && value.keyString().equals(rid.key().keyString());
    }

    private void applyMutate(Map<String, AxonValue> current, Data data, Document doc) {
        switch (data) {
            case Data.SetClause sc -> {
                for (Assignment a : sc.assignments()) {
                    List<Object> path = assignmentPath(a.path(), doc.id().table(), current);
                    AxonValue newValue = evalOnDocument(a.value(), doc);
                    AxonValue existing = path.size() == 1 ? current.get((String) path.get(0)) : null;
                    if (existing != null && existing.isNumber() && newValue.isString()) {
                        try {
                            String s = newValue.asString().trim();
                            newValue = s.contains(".") || s.contains("e") || s.contains("E")
                                ? AxonValue.num(Double.parseDouble(s))
                                : AxonValue.num(Long.parseLong(s));
                        } catch (NumberFormatException ignored) {}
                    }
                    write(current, path, newValue);
                }
            }
            case Data.Merge m -> {
                AxonValue mv = evalExpr(m.merge());
                if (mv.isObject()) {
                    Map<String, AxonValue> merged = RecordChanges.merge(current, mv.asObject());
                    current.clear();
                    current.putAll(merged);
                }
            }
            case Data.Patch p -> {
                AxonValue pv = evalExpr(p.patch());
                if (pv.isObject()) {
                    current.putAll(pv.asObject());
                } else if (pv.isArray()) {
                    refuseMaskedPatch(pv.asArray());
                    Map<String, AxonValue> patched =
                        RecordChanges.patch(AxonValue.object(current), pv.asArray()).asObject();
                    current.clear();
                    current.putAll(patched);
                } else {
                    throw errorStmt(Messages.get("stmt_patch_invalid", pv));
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

    /**
     * Num PATCH, {@code test}, {@code copy} e {@code move} leem o registro. Com uma coluna mascarada, o resultado ou o
     * erro entregaria o valor: ficam recusados sobre ela e sobre o registro inteiro, e nenhum caminho entra nela.
     */
    private void refuseMaskedPatch(List<AxonValue> operations) {
        if (!masksColumns()) {
            return;
        }
        for (AxonValue operation : operations) {
            if (!operation.isObject()) {
                continue; // o próprio PATCH recusa
            }
            Map<String, AxonValue> fields = operation.asObject();
            AxonValue op = fields.get("op");
            String name = op != null && op.isString() ? op.asString() : "";
            boolean copies = "copy".equals(name) || "move".equals(name);
            if (reachesMasked(fields.get("path"), "test".equals(name))
                || copies && reachesMasked(fields.get("from"), true)) {
                throw errorStmt("permission denied");
            }
        }
    }

    /** O ponteiro lê ({@code reads}) ou percorre uma coluna mascarada. O ponteiro vazio é o registro inteiro. */
    private boolean reachesMasked(AxonValue pointer, boolean reads) {
        if (pointer == null || !pointer.isString()) {
            return false;
        }
        String text = pointer.asString();
        if (text.isEmpty()) {
            return reads;
        }
        if (!text.startsWith("/")) {
            return false;
        }
        String[] steps = text.substring(1).split("/", -1);
        String column = steps[0].replace("~1", "/").replace("~0", "~");
        return (reads || steps.length > 1) && maskedColumn(column);
    }

    // ------------------------------------------------------------------
    // DQL: Select
    // ------------------------------------------------------------------

    private AxonValue runSelect(Statement.Select sel) {
        // lido e apagado aqui para as subqueries desta consulta rodarem inteiras
        boolean partial = partialRequested;
        partialRequested = false;
        Statement.Select resolved = rewriteEmbeddingMatches(sel);
        // com junção o WHERE fala de mais de uma tabela: lê todas as partições
        try (var scope = prunePartitions(resolved.from(), resolved.joins().isEmpty() ? resolved.cond() : null)) {
            return runSelectResolved(resolved, partial);
        }
    }

    // ------------------------------------------------------------------
    // Partições por tempo e TTL (a lógica fica em core.partition)
    // ------------------------------------------------------------------

    /** Numa tabela particionada, restringe as varreduras dela à faixa de tempo que o WHERE permite. */
    private com.axonbase.core.partition.PartitionContext.Scope prunePartitions(Expr from, Expr cond) {
        if (!(from instanceof Expr.Ident id) || session.namespace() == null || session.database() == null) {
            return () -> { };
        }
        Database db = ds.findDatabase(session.namespace(), session.database());
        var spec = db == null ? null : db.catalog().partitioning(id.name());
        if (spec == null) {
            return () -> { };
        }
        return partitions.prune(com.axonbase.core.partition.PartitionedKv.rangeKey(db, id.name()),
            com.axonbase.core.partition.TimeRange.of(cond, spec.field(), this::eval));
    }

    /** Numa tabela particionada, define a partição de destino antes de os índices serem gravados. */
    private void bindPartition(Database db, RecordId rid, AxonValue record, boolean generated) {
        if (records(db) instanceof com.axonbase.core.partition.PartitionedKv view) {
            view.bind(rid, record, generated);
        }
    }

    private com.axonbase.core.partition.PartitionRunner partitionRunner(Database db) {
        return new com.axonbase.core.partition.PartitionRunner(ds, session, db, rawRecords(db),
            batch != null || db.indexLock().getReadHoldCount() > 0, this::eval);
    }

    private AxonValue runPartition(com.axonbase.parser.ast.PartitionCommand command) {
        Database db = db();
        if (command instanceof com.axonbase.parser.ast.PartitionCommand.Define
            || command instanceof com.axonbase.parser.ast.PartitionCommand.DefineTtl) {
            ensureTable(db, command.table());
        }
        return partitionRunner(db).run(command);
    }

    /**
     * {@code WHERE campo @@ 'texto'} num campo que segue um embedding: o texto vira o vetor da consulta (com o
     * prefixo QUERY do embedding do próprio campo, então o modelo é sempre o mesmo da gravação). Sem ORDER BY, a
     * consulta passa a ordenar pela distância ao vetor e, sem LIMIT, devolve os 10 mais próximos. Com ORDER BY
     * explícito o vetor entra só no filtro; o resto da consulta segue as regras de sempre.
     */
    private Statement.Select rewriteEmbeddingMatches(Statement.Select sel) {
        if (sel.cond() == null || !(sel.from() instanceof Expr.Ident from) || !containsMatch(sel.cond())) {
            return sel;
        }
        Database db = db();
        Catalog.TableDef def = db.catalog().table(from.name());
        if (def == null || def.fields().values().stream().noneMatch(f -> f.embeddingName() != null)) {
            return sel;
        }
        String[] firstField = new String[1];
        AxonValue[] firstVector = new AxonValue[1];
        Expr cond = rewriteMatches(db, def, sel.cond(), firstField, firstVector);
        if (cond == sel.cond()) {
            return sel;
        }
        List<OrderTerm> orders = sel.orders();
        Expr limit = sel.limit();
        if (orders.isEmpty() && firstField[0] != null && !sel.grouped()
            && sel.fields().stream().noneMatch(this::isAggregateCall)) {
            String metric = def.indexes().values().stream().filter(Catalog.IndexDef::ready)
                .filter(i -> i.vector() && i.columns().contains(firstField[0])).map(Catalog.IndexDef::vectorDistance)
                .findFirst().orElse("cosine");
            String function = "vector::distance::" + ("manhattan".equals(metric) ? "manhattan"
                : "euclidean".equals(metric) ? "euclidean" : "cosine");
            orders = List.of(new OrderTerm(new Expr.Call(function,
                List.of(new Expr.Ident(firstField[0]), new Expr.Literal(firstVector[0]))), false));
            if (limit == null) {
                limit = new Expr.Literal(AxonValue.num(10));
            }
        }
        return new Statement.Select(sel.fields(), sel.valueOnly(), sel.only(), sel.from(), cond, orders, sel.group(),
            limit, sel.start(), sel.fetch(), sel.joins(), sel.timeTravel(), sel.having(), sel.groupAll());
    }

    private static boolean containsMatch(Expr e) {
        if (e instanceof Expr.Binary b) {
            return b.op() == Expr.BinaryOp.MATCH || ((b.op() == Expr.BinaryOp.AND || b.op() == Expr.BinaryOp.OR)
                && (containsMatch(b.left()) || containsMatch(b.right())));
        }
        return false;
    }

    private Expr rewriteMatches(Database db, Catalog.TableDef def, Expr e, String[] firstField, AxonValue[] firstVector) {
        if (!(e instanceof Expr.Binary b)) {
            return e;
        }
        if (b.op() == Expr.BinaryOp.AND || b.op() == Expr.BinaryOp.OR) {
            Expr left = rewriteMatches(db, def, b.left(), firstField, firstVector);
            Expr right = rewriteMatches(db, def, b.right(), firstField, firstVector);
            return left == b.left() && right == b.right() ? e : new Expr.Binary(b.op(), left, right);
        }
        if (b.op() != Expr.BinaryOp.MATCH || !(b.left() instanceof Expr.Ident field)) {
            return e;
        }
        Catalog.FieldDef fd = def.fields().get(field.name());
        if (fd == null || fd.embeddingName() == null) {
            return e;
        }
        AxonValue rhs = eval(b.right());
        if (!rhs.isString()) {
            return e;
        }
        Catalog.EmbeddingDef embedding = requireEmbedding(fd.embeddingName());
        AxonValue vector = com.axonbase.core.embedding.EmbeddingClient.toValue(ds.embeddings().embedOne(embedding,
            rhs.asString(), com.axonbase.core.embedding.EmbeddingClient.Role.QUERY));
        if (firstField[0] == null) {
            firstField[0] = field.name();
            firstVector[0] = vector;
        }
        return new Expr.Binary(Expr.BinaryOp.MATCH, b.left(), new Expr.Literal(vector));
    }

    private AxonValue runSelectResolved(Statement.Select sel, boolean partial) {
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
        GroupAggregation streamedAggregation = null;
        if (sel.from() == null) {
            // SELECT VALUE expr() — sem FROM, cria documento virtual vazio
            docs = virtualDocs(AxonValue.object(Map.of()));
        } else if (timeTravelTs >= 0) {
            docs = historicalDocs(db, sel, timeTravelTs);
        } else if (hy != null) {
            docs = hybridSourceDocs(db, sel, hy);
        } else {
            // Cada nível só calcula o fallback se não puder usar o próprio índice. Antes o fallback
            // (varredura completa) era calculado antes, e todo SELECT lia a tabela inteira.
            boolean streamable = isStreamableWindow(sel);
            boolean streamableAggregate = isStreamableAggregate(sel);
            boolean topK = isTopKWindow(sel);
            // SELECT ... LIMIT n: as fontes indexadas param ao completar START+LIMIT linhas que passam no filtro
            Window window = streamable ? windowFor(db, sel) : null;
            // WHERE a = 1 AND b > 2: um índice que atende só uma das partes já reduz a leitura; o WHERE inteiro
            // é reavaliado abaixo sobre os candidatos.
            docs = conjunctIndexDocs(db, sel, window);
            if (docs == null) {
                docs = uniqueLookupSourceDocs(db, sel,
                    () -> vectorSourceDocs(db, sel, () -> geoSourceDocs(db, sel, window,
                        () -> columnarSourceDocs(db, sel, window,
                            () -> streamable || streamableAggregate || topK ? null : searchSourceDocs(db, sel)))));
            }
            if (docs == null) {
                GroupAggregation covered = streamableAggregate ? columnarCoveredAggregate(db, sel) : null;
                if (covered != null) {
                    // todas as colunas da consulta estão em índices COLUMNAR: agrega só com as entradas do índice
                    streamedAggregation = covered;
                    docs = new ArrayList<>();
                } else if (streamableAggregate) {
                    // sem índice: agrega lendo a tabela em fluxo, sem montar a lista de documentos
                    streamedAggregation = streamAggregate(db, sel);
                    docs = new ArrayList<>();
                } else if (topK) {
                    docs = streamTopKDocs(db, sel);
                } else {
                    docs = streamWindowDocs(db, sel);
                }
            }
        }
        if (streamedAggregation == null) {
            if (sel.cond() != null) {
                docs.removeIf(doc -> !truthy(evalCond(sel.cond(), doc)));
            }
            applyDataRulesFilter(docs);
            applySearchScores(sel, docs);
            docs.removeIf(doc -> sel.from() != null && !allowed(doc, tableName(sel.from()), "select"));
        }
        boolean joined = !sel.joins().isEmpty();
        if (joined) {
            docs = joinDocs(db, sel, docs);
        }
        List<OrderTerm> orders = ordersByAlias(sel);
        // Com agregação, ORDER BY e LIMIT valem para as linhas agrupadas (depois de GROUP BY e HAVING), não
        // para os documentos de origem: ordenar ou cortar antes mudaria o resultado dos agregados.
        boolean aggregating = !joined && (sel.grouped() || sel.fields().stream().anyMatch(GroupAggregation::containsAggregate)
            || sel.having() != null);
        if (hy != null && orders.isEmpty()) {
            docs.sort((a, b) -> Double.compare(b.searchScore(), a.searchScore()));
        } else if (!orders.isEmpty() && !aggregating) {
            Comparator<Document> cmp = orderComparator(orders);
            int keep = !joined && sel.limit() != null ? windowEnd(sel) : -1;
            if (keep >= 0 && keep < docs.size()) {
                // ORDER BY ... LIMIT n: só as START+LIMIT primeiras linhas importam, então um heap de n em vez de ordenar tudo
                docs = topKOf(docs, cmp, keep);
            } else {
                docs.sort(cmp);
            }
        }
        if (partial) {
            return partialResult(sel, docs, orders, aggregating, streamedAggregation);
        }
        int start = sel.start() != null ? (int) eval(sel.start()).asLong() : 0;
        int limit = sel.limit() != null ? (int) eval(sel.limit()).asLong() : docs.size();
        int stop = Math.min(docs.size(), start + limit);
        List<AxonValue> out = new ArrayList<>();
        boolean scalarAggregate = false;
        // as colunas são mascaradas na origem: apelido, SELECT VALUE, ONLY e agregados veem o valor mascarado
        boolean masking = masksColumns();
        if (aggregating) {
            // FETCH resolve antes da agregación, que traballa sobre todo o conxunto
            if (!sel.fetch().isEmpty()) {
                docs.forEach(d -> fetchDocs(d, sel.fetch()));
            }
            GroupAggregation aggregation = streamedAggregation;
            if (aggregation == null) {
                aggregation = new GroupAggregation(sel, groupRowEval(), this::fieldName);
                for (Document d : docs) {
                    if (masking) {
                        d.setCurrent(applyDataRuleMask(d.current()));
                    }
                    aggregation.accept(d);
                }
            }
            List<AxonValue> rows = aggregation.finish();
            int from = Math.min(Math.max(start, 0), rows.size());
            int to = sel.limit() != null ? (int) Math.min(rows.size(), (long) from + Math.max(limit, 0)) : rows.size();
            out.addAll(rows.subList(from, to));
            // sem GROUP BY o agregado é uma linha só: SELECT VALUE count() devolve o número, não [{count: n}]
            scalarAggregate = sel.valueOnly() && !sel.grouped() && out.size() == 1;
        } else {
            // FETCH resolvase só sobre a fiestra [start, stop), non sobre todos os docs
            for (int i = Math.min(start, docs.size()); i < stop; i++) {
                Document d = docs.get(i);
                if (!sel.fetch().isEmpty() && !joined) {
                    fetchDocs(d, sel.fetch());
                }
                if (masking && !joined) {
                    d.setCurrent(applyDataRuleMask(d.current()));
                }
                AxonValue row = joined ? projectJoin(sel, d) : project(sel, d);
                // FETCH também vale para colunas calculadas (->comprou->produto AS p ... FETCH p)
                out.add(!sel.fetch().isEmpty() && !joined ? fetchProjected(row, sel.fetch()) : row);
            }
        }
        if (scalarAggregate) {
            return out.get(0);
        }
        if (masking) {
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

    /**
     * A parte de um shard numa consulta espalhada: grupos com os acumuladores, ou as primeiras {@code START + LIMIT}
     * linhas, cada uma com os valores do ORDER BY e a chave do registro, para o gateway intercalar.
     */
    private AxonValue partialResult(Statement.Select sel, List<Document> docs, List<OrderTerm> orders,
                                    boolean aggregating, GroupAggregation streamed) {
        if (ds.auditSelect() && sel.from() != null) {
            ds.auditLog(session, "SELECT", tableName(sel.from()), null, null, null);
        }
        if (aggregating) {
            if (masksColumns()) {
                // o mascaramento depende do catálogo do shard e valeria só depois de fundir: recusar não vaza nada
                throw errorStmt(ShardPartial.maskedAggregation());
            }
            GroupAggregation aggregation = streamed;
            if (aggregation == null) {
                aggregation = new GroupAggregation(sel, groupRowEval(), this::fieldName);
                for (Document d : docs) {
                    aggregation.accept(d);
                }
            }
            return ShardPartial.groups(aggregation);
        }
        // a resposta parcial leva a chave do registro e os valores do ORDER BY, que a máscara da linha não cobre
        if (masksColumns() && !MaskedOrder.safe(sel, orders, this::maskedColumn)) {
            throw errorStmt(ShardPartial.maskedOrder());
        }
        long window = docs.size();
        if (sel.limit() != null) {
            long start = sel.start() != null ? eval(sel.start()).asLong() : 0;
            window = Math.min(window, Math.max(0, start) + Math.max(0, eval(sel.limit()).asLong()));
        }
        List<AxonValue> rows = new ArrayList<>();
        for (int i = 0; i < window; i++) {
            Document d = docs.get(i);
            // as chaves de ordenação usam o valor real: MaskedOrder já recusou o que vazaria coluna mascarada
            var keys = sortKeys(orders, d);
            boolean masking = masksColumns();
            if (masking) {
                d.setCurrent(applyDataRuleMask(d.current()));
            }
            AxonValue row = project(sel, d);
            if (masking) {
                row = applyDataRuleMask(row);
            }
            rows.add(ShardPartial.row(d.id().key().keyString(), keys, row));
        }
        return ShardPartial.rows(rows);
    }

    private boolean masksColumns() {
        AxonValue auth = session.auth();
        if (auth == null || !auth.isObject()) {
            return false;
        }
        for (String ruleName : dataRuleNames(auth)) {
            Catalog.DataRuleDef def = db().catalog().dataRule(ruleName);
            if (def != null && !def.maskPatterns().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Alguma Data Rule do usuário mascara uma coluna com este nome. */
    private boolean maskedColumn(String name) {
        for (String ruleName : dataRuleNames(session.auth())) {
            Catalog.DataRuleDef def = db().catalog().dataRule(ruleName);
            if (def != null && def.maskPatterns().stream()
                .anyMatch(pattern -> name.toLowerCase().matches(sqlLikeToRegex(pattern)))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // JOIN: bucle aniñado sobre a fonte do FROM e a(s) fonte(s) enlazada(s)
    // ------------------------------------------------------------------

    /**
     * SELECT de tabela simples com LIMIT, sem ORDER BY, JOIN, agregação ou MATCH: dá para ler em
     * ordem de chave e parar quando a janela START+LIMIT estiver completa.
     */
    private boolean isStreamableWindow(Statement.Select sel) {
        return sel.from() instanceof Expr.Ident
            && sel.limit() != null
            && sel.orders().isEmpty()
            && sel.joins().isEmpty()
            && !sel.grouped()
            && sel.having() == null
            && sel.fields().stream().noneMatch(this::isAggregateCall)
            && (sel.cond() == null || !isSearchMatch(sel.cond()));
    }

    /**
     * Lê a tabela pelo iterador do storage e devolve só os documentos que passam por WHERE, Data
     * Rules e permissões, parando ao completar {@code START + LIMIT}. Os filtros que o
     * {@code runSelect} aplica depois são idempotentes sobre esse resultado.
     */
    /** Agregação sobre tabela simples, que pode ser feita lendo os registros em fluxo (sem JOIN nem busca). */
    private boolean isStreamableAggregate(Statement.Select sel) {
        return sel.from() instanceof Expr.Ident
            && sel.joins().isEmpty()
            && (sel.grouped() || sel.having() != null
                || sel.fields().stream().anyMatch(GroupAggregation::containsAggregate))
            && (sel.cond() == null || !isSearchMatch(sel.cond()));
    }

    /**
     * Lê a tabela pelo iterador do storage e soma cada registro que passa por WHERE, Data Rules e permissões
     * direto nos acumuladores. Memória proporcional ao número de grupos, não de linhas.
     */
    /**
     * Percorre as chaves de registro de uma tabela em ordem de chave, sem passar pelas entradas de índice UNIQUE
     * ({@code !uidx}) que ficam no mesmo prefixo e vêm antes dos registros: as chaves de registro começam por
     * {@code n}, {@code s} ou {@code x} ({@code keyString}), então basta varrer esses três prefixos na ordem. Sem isso
     * um {@code LIMIT 10} numa tabela com índice UNIQUE lia todas as entradas do índice antes do primeiro registro.
     */
    private static void scanRecordKeys(KvBackend kv, String prefix, java.util.function.Predicate<String> visitor) {
        boolean[] stopped = {false};
        for (String lead : RECORD_KEY_LEADS) {
            kv.scanPrefix(prefix + lead, key -> {
                // por registro: cancelamento e orçamento do QoS valem também nas varreduras seriais
                QueryContext.checkCancelled();
                if (!visitor.test(key)) {
                    stopped[0] = true;
                    return false;
                }
                return true;
            });
            if (stopped[0]) {
                return;
            }
        }
    }

    /** Um registro lido do armazenamento: a chave completa e os bytes do valor, numa passada só do iterador. */
    private record RawRecord(String key, byte[] value) {
    }

    /** Como {@link #scanRecordKeys}, mas já com o valor de cada registro, sem uma segunda leitura por chave. */
    private static void scanRecordEntries(KvBackend kv, String prefix, java.util.function.Predicate<RawRecord> visitor) {
        boolean[] stopped = {false};
        for (String lead : RECORD_KEY_LEADS) {
            kv.scanEntries(prefix + lead, (key, value) -> {
                QueryContext.checkCancelled();
                if (!visitor.test(new RawRecord(key, value))) {
                    stopped[0] = true;
                    return false;
                }
                return true;
            });
            if (stopped[0]) {
                return;
            }
        }
    }

    private static final String[] RECORD_KEY_LEADS = {"n", "s", "x"};

    private GroupAggregation streamAggregate(Database db, Statement.Select sel) {
        String table = ((Expr.Ident) sel.from()).name();
        ensureTable(db, table);
        GroupAggregation aggregation = new GroupAggregation(sel, groupRowEval(), this::fieldName);
        List<Expr> rules = dataRulePredicates();
        Expr combinedRules = rules.isEmpty() ? null : rules.size() == 1 ? rules.get(0) : Expr.and(rules);
        Catalog.TableDef def = db.catalog().table(table);
        boolean checkPermissions = def != null && def.permissions() != null;
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
        boolean masking = masksColumns();
        java.util.function.BiConsumer<GroupAggregation, String> consume = (agg, key) -> {
            Document doc = loadDoc(db, new RecordId(table, parseKey(key.substring(prefix.length()))));
            if (doc == null) {
                return;
            }
            if (sel.cond() != null && !truthy(evalCond(sel.cond(), doc))) {
                return;
            }
            if (combinedRules != null && !truthy(asPolicy(() -> evalCond(combinedRules, doc)))) {
                return;
            }
            if (checkPermissions && !allowed(doc, table, "select")) {
                return;
            }
            if (!sel.fetch().isEmpty()) {
                fetchDocs(doc, sel.fetch());
            }
            if (masking) {
                doc.setCurrent(applyDataRuleMask(doc.current()));
            }
            agg.accept(doc);
        };
        if (canScanInParallel(sel, combinedRules, def)) {
            // tabela grande: as threads leem e avaliam lotes de registros e os resultados parciais são fundidos na ordem
            KvBackend kv = records(db);
            ParallelScan.run(visitor -> scanRecordKeys(kv, prefix, visitor), aggregation,
                () -> new GroupAggregation(sel, groupRowEval(), this::fieldName),
                consume, GroupAggregation::merge);
            return aggregation;
        }
        scanRecordKeys(records(db), prefix, key -> {
            consume.accept(aggregation, key);
            return true;
        });
        return aggregation;
    }

    private boolean canScanInParallel(Database db, Statement.Select sel, String table) {
        List<Expr> rules = dataRulePredicates();
        Expr combinedRules = rules.isEmpty() ? null : rules.size() == 1 ? rules.get(0) : Expr.and(rules);
        return canScanInParallel(sel, combinedRules, db.catalog().table(table));
    }

    /**
     * A varredura pode ir em várias threads quando nada nela depende de estado da sessão que não seja só leitura:
     * fora de transação (a transação não é compartilhável entre threads), sem FETCH e com WHERE, agregados, GROUP BY,
     * HAVING, Data Rules e permissões feitos só de campos, literais, parâmetros, operadores e funções puras. Caso
     * contrário segue sequencial, com o mesmo resultado.
     */
    private boolean canScanInParallel(Statement.Select sel, Expr combinedRules, Catalog.TableDef def) {
        Transaction tx = session.tx();
        if ((tx != null && tx.isOpen()) || !sel.fetch().isEmpty() || ParallelScan.degree() < 2) {
            return false;
        }
        if (!isPureRowExpr(sel.cond()) || !isPureRowExpr(combinedRules) || !isPureRowExpr(sel.having())) {
            return false;
        }
        if (def != null && def.permissions() != null && !isPureRowExpr(def.permissions().select())) {
            return false;
        }
        return sel.fields().stream().allMatch(Executor::isPureRowExpr)
            && sel.group().stream().allMatch(Executor::isPureRowExpr)
            && sel.orders().stream().allMatch(o -> isPureRowExpr(o.field()));
    }

    /** Expressão sem subquery, sem acesso ao armazenamento e sem efeito colateral: segura para várias threads. */
    private static boolean isPureRowExpr(Expr e) {
        return switch (e) {
            case null -> true;
            case Expr.Literal l -> true;
            case Expr.Param p -> true;
            case Expr.Ident i -> true;
            case Expr.Alias a -> isPureRowExpr(a.expr());
            case Expr.Unary u -> isPureRowExpr(u.operand());
            case Expr.Cast c -> isPureRowExpr(c.operand());
            case Expr.Binary b -> isPureRowExpr(b.left()) && isPureRowExpr(b.right());
            case Expr.ArrayLit a -> a.items().stream().allMatch(Executor::isPureRowExpr);
            case Expr.ObjectLit o -> o.entries().stream().allMatch(en -> isPureRowExpr(en.value()));
            case Expr.Idiom i -> isPureRowExpr(i.base()) && i.parts().stream().allMatch(part -> part instanceof Expr.Part.Field);
            case Expr.Call c -> pureFunction(c.name()) && c.args().stream().allMatch(Executor::isPureRowExpr);
            default -> false;
        };
    }

    private static boolean pureFunction(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("string::") || n.startsWith("math::") || n.startsWith("array::")
            || n.startsWith("geo::") || n.startsWith("type::") || n.startsWith("object::")
            || n.equals("count") || n.equals("sum") || n.equals("avg") || n.equals("min") || n.equals("max")
            || n.equals(Expr.COUNT_DISTINCT);
    }

    /** Condições ligadas por AND no topo do WHERE, na ordem em que foram escritas. */
    private static List<Expr> conjuncts(Expr cond) {
        List<Expr> out = new ArrayList<>();
        collectConjuncts(cond, out);
        return out;
    }

    private static void collectConjuncts(Expr cond, List<Expr> out) {
        if (cond instanceof Expr.Binary b && b.op() == Expr.BinaryOp.AND) {
            collectConjuncts(b.left(), out);
            collectConjuncts(b.right(), out);
        } else if (cond != null) {
            out.add(cond);
        }
    }

    private static Statement.Select withoutOrders(Statement.Select sel) {
        return new Statement.Select(sel.fields(), sel.valueOnly(), sel.only(), sel.from(), sel.cond(), List.of(),
            sel.group(), sel.limit(), sel.start(), sel.fetch(), sel.joins(), sel.timeTravel(), sel.having(),
            sel.groupAll());
    }

    private static Statement.Select withCond(Statement.Select sel, Expr cond) {
        return new Statement.Select(sel.fields(), sel.valueOnly(), sel.only(), sel.from(), cond, sel.orders(),
            sel.group(), sel.limit(), sel.start(), sel.fetch(), sel.joins(), sel.timeTravel(), sel.having(),
            sel.groupAll());
    }

    /**
     * Candidatos vindos de um índice que atenda uma das partes do WHERE ligadas por AND (UNIQUE, GEO ou
     * columnar), ou {@code null} se nenhuma parte tem índice. O chamador reavalia o WHERE inteiro.
     */
    private List<Document> conjunctIndexDocs(Database db, Statement.Select sel, Window window) {
        if (!(sel.from() instanceof Expr.Ident) || sel.cond() == null) {
            return null;
        }
        List<Expr> parts = conjuncts(sel.cond());
        if (parts.size() < 2) {
            return null;
        }
        for (Expr part : parts) {
            Statement.Select probe = withCond(sel, part);
            List<Document> hit = uniqueLookupSourceDocs(db, probe,
                // sem ORDER BY para o GEO: os mais próximos só valem com o WHERE inteiro, nunca com uma parte dele
                () -> geoSourceDocs(db, withoutOrders(probe), window,
                    () -> columnarSourceDocs(db, probe, window, () -> null)));
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Janela START+LIMIT de um SELECT que dá para ler parando cedo: {@code accepts} aplica o WHERE inteiro, as Data
     * Rules e as permissões a um documento, e {@code wanted} é quantas linhas completam a janela.
     */
    private record Window(int wanted, java.util.function.Predicate<Document> accepts) {
    }

    /** START+LIMIT (limitado a Integer.MAX_VALUE); só vale com LIMIT. */
    private int windowEnd(Statement.Select sel) {
        long start = sel.start() != null ? eval(sel.start()).asLong() : 0L;
        long wantedLong = start + eval(sel.limit()).asLong();
        return (int) Math.min(Math.max(wantedLong, 0L), Integer.MAX_VALUE);
    }

    private Window windowFor(Database db, Statement.Select sel) {
        String table = ((Expr.Ident) sel.from()).name();
        List<Expr> rules = dataRulePredicates();
        Expr combinedRules = rules.isEmpty() ? null : rules.size() == 1 ? rules.get(0) : Expr.and(rules);
        Catalog.TableDef def = db.catalog().table(table);
        boolean checkPermissions = def != null && def.permissions() != null;
        return new Window(windowEnd(sel), doc -> {
            if (sel.cond() != null && !truthy(evalCond(sel.cond(), doc))) {
                return false;
            }
            if (combinedRules != null && !truthy(asPolicy(() -> evalCond(combinedRules, doc)))) {
                return false;
            }
            return !checkPermissions || allowed(doc, table, "select");
        });
    }

    private List<Document> streamWindowDocs(Database db, Statement.Select sel) {
        String table = ((Expr.Ident) sel.from()).name();
        ensureTable(db, table);
        Window window = windowFor(db, sel);
        List<Document> out = new ArrayList<>();
        if (window.wanted() == 0) {
            return out;
        }
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
        java.util.function.BiConsumer<List<Document>, RawRecord> consume = (acc, raw) -> {
            if (acc.size() >= window.wanted()) {
                return;
            }
            Document doc = docOf(table, prefix, raw);
            if (doc != null && window.accepts().test(doc)) {
                acc.add(doc);
            }
        };
        if (canScanInParallel(db, sel, table)) {
            // tabela grande: lotes avaliados em paralelo e juntados na ordem; para quando a janela fecha
            ParallelScan.run(visitor -> scanRecordEntries(records(db), prefix, visitor), out, ArrayList::new, consume,
                List::addAll, () -> out.size() >= window.wanted());
            if (out.size() > window.wanted()) {
                out.subList(window.wanted(), out.size()).clear();
            }
        } else {
            scanRecordEntries(records(db), prefix, key -> {
                consume.accept(out, key);
                return out.size() < window.wanted();
            });
        }
        return out;
    }

    /** ORDER BY ... LIMIT sobre tabela simples, sem JOIN, agregação nem MATCH: dá para guardar só as START+LIMIT melhores. */
    private boolean isTopKWindow(Statement.Select sel) {
        return sel.from() instanceof Expr.Ident
            && sel.limit() != null
            && !sel.orders().isEmpty()
            && sel.joins().isEmpty()
            && !sel.grouped()
            && sel.having() == null
            && sel.fields().stream().noneMatch(this::isAggregateCall)
            && (sel.cond() == null || !isSearchMatch(sel.cond()));
    }

    /**
     * Lê a tabela em fluxo e guarda num heap só as START+LIMIT primeiras linhas na ordem do ORDER BY. As chaves de
     * ordenação saem uma vez por linha e o desempate é a ordem de leitura, o que dá o mesmo resultado de ordenar
     * tudo com a ordenação estável.
     */
    private List<Document> streamTopKDocs(Database db, Statement.Select sel) {
        String table = ((Expr.Ident) sel.from()).name();
        ensureTable(db, table);
        Window window = windowFor(db, sel);
        List<Document> out = new ArrayList<>();
        if (window.wanted() == 0) {
            return out;
        }
        List<OrderTerm> orders = ordersByAlias(sel);
        Comparator<AxonValue[]> keyCmp = keyedComparator(orders);
        Comparator<Keyed> keyedCmp = (a, b) -> keyCmp.compare(a.keys(), b.keys());
        TopK<Keyed> top = new TopK<>(window.wanted(), keyedCmp);
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
        java.util.function.BiConsumer<TopK<Keyed>, RawRecord> consume = (acc, raw) -> {
            Document doc = docOf(table, prefix, raw);
            if (doc != null && window.accepts().test(doc)) {
                acc.offer(new Keyed(doc, sortKeys(orders, doc)));
            }
        };
        if (canScanInParallel(db, sel, table)) {
            // cada lote vira um heap próprio e os heaps são fundidos na ordem dos lotes (o desempate segue a ordem de leitura)
            ParallelScan.run(visitor -> scanRecordEntries(records(db), prefix, visitor), top,
                () -> new TopK<>(window.wanted(), keyedCmp), consume, TopK::merge);
        } else {
            scanRecordEntries(records(db), prefix, key -> {
                consume.accept(top, key);
                return true;
            });
        }
        for (Keyed k : top.sorted()) {
            out.add(k.doc());
        }
        return out;
    }

    private record Keyed(Document doc, AxonValue[] keys) {
    }

    private AxonValue[] sortKeys(List<OrderTerm> orders, Document doc) {
        AxonValue[] keys = new AxonValue[orders.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = evalField(orders.get(i).field(), doc);
        }
        return keys;
    }

    private static Comparator<AxonValue[]> keyedComparator(List<OrderTerm> orders) {
        return (a, b) -> {
            for (int i = 0; i < a.length; i++) {
                int c = a[i].compareTo(b[i]);
                if (c != 0) {
                    return orders.get(i).descending() ? -c : c;
                }
            }
            return 0;
        };
    }

    /** As {@code k} primeiras linhas de {@code docs} na ordem de {@code cmp}, igual a ordenar tudo (estável) e cortar. */
    private List<Document> topKOf(List<Document> docs, Comparator<Document> cmp, int k) {
        if (k <= 0) {
            return new ArrayList<>();
        }
        TopK<Document> top = new TopK<>(k, cmp);
        for (Document d : docs) {
            top.offer(d);
        }
        return top.sorted();
    }

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
        List<Document> inner = new ArrayList<>(dbDocs(db, join.source()));
        applyDataRulesFilter(inner);
        boolean masking = masksColumns();
        List<Document> out = new ArrayList<>();
        for (Document lo : outer) {
            AxonValue ok = fieldValue(lo.current(), outerKey);
            if (unspecified(ok)) {
                continue;
            }
            for (Document li : inner) {
                AxonValue ik = fieldValue(li.current(), innerKey);
                if (!unspecified(ik) && ok.equals(ik)) {
                    out.add(flattenJoin(outerTable, masking ? masked(lo) : lo,
                        innerTable, masking ? masked(li) : li));
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
    private List<Document> vectorSourceDocs(Database db, Statement.Select sel, java.util.function.Supplier<List<Document>> fallback) {
        if (!(sel.from() instanceof Expr.Ident table) || sel.orders().isEmpty()
            || !(sel.orders().get(0).field() instanceof Expr.Call call)
            || !call.name().startsWith("vector::distance::") || call.args().isEmpty()
            || !(call.args().get(0) instanceof Expr.Ident field)) {
            return fallback.get();
        }
        Catalog.TableDef def = db.catalog().table(table.name());
        if (def == null) {
            return fallback.get();
        }
        Catalog.IndexDef index = def.indexes().values().stream().filter(Catalog.IndexDef::ready)
            .filter(i -> i.vector() && i.columns().contains(field.name())).findFirst().orElse(null);
        if (index == null) {
            return fallback.get();
        }
        AxonValue query = call.args().size() > 1 ? eval(call.args().get(1)) : AxonValue.nul();
        if (!validVector(query, index.vectorDimension())) {
            return fallback.get();
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
        GraphTop top = graphTop(db, table, index);
        String entry = top.entry();
        if (entry == null) {
            return new LinkedHashSet<>();
        }
        for (int level = top.level(); level > 0; level--) {
            List<Neighbor> near = searchLevel(db, table, index, level, List.of(entry), query, 1);
            if (!near.isEmpty()) {
                entry = near.get(0).key();
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (Neighbor candidate : searchLevel(db, table, index, 0, List.of(entry), query, Math.max(1, efSearch))) {
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

    /** Chave do maior nível do grafo HNSW: o contador que evita varrer o grafo para descobri-lo. */
    private String vhTopKey(Database db, String table, String index) {
        return "VHT|" + db.ns() + "|" + db.db() + "|" + table + "|" + index;
    }

    /**
     * Maior nível do grafo HNSW, ou -1 quando vazio. Lê o contador persistido; um grafo antigo, sem contador, é
     * varrido uma única vez e o resultado fica gravado (migração preguiçosa).
     */
    private int maxLevel(Database db, String table, Catalog.IndexDef index) {
        Optional<byte[]> stored = records(db).get(vhTopKey(db, table, index.name()));
        if (stored.isPresent()) {
            try {
                return Integer.parseInt(new String(stored.get(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (NumberFormatException ignored) {
                // contador ilegível: recalcula abaixo
            }
        }
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
        if (max >= 0) {
            records(db).put(vhTopKey(db, table, index.name()),
                Integer.toString(max).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        String[] found = {null};
        records(db).scanPrefix(prefix, key -> {
            if (key.endsWith("|@entry")) {
                return true;
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
                found[0] = node;
                return false;
            }
            return true;
        });
        if (found[0] != null) {
            records(db).put(vhEntryKey(db, table, index, level),
                found[0].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return found[0];
        }
        records(db).delete(vhEntryKey(db, table, index, level));
        return null;
    }

    /**
     * Busca greedy num nível a partir de um ou mais pontos de entrada: devolve até <code>ef</code> candidatos
     * ordenados pela menor distância, usando as arestas persistidas VH|...|<nivel>|<key>|<vizinho>.
     */
    private List<Neighbor> searchLevel(Database db, String table, Catalog.IndexDef index, int level,
                                       List<String> entries, AxonValue query, int ef) {
        PriorityQueue<Neighbor> candidates = new PriorityQueue<>(
            Comparator.comparingDouble(Neighbor::distance));
        PriorityQueue<Neighbor> results = new PriorityQueue<>(
            java.util.Comparator.comparingDouble(Neighbor::distance).reversed());
        Set<String> visited = new HashSet<>();
        for (String entry : entries) {
            if (visited.add(entry)) {
                Neighbor start = new Neighbor(entry, vectorNodeDistance(db, table, index, entry, query));
                candidates.add(start);
                results.add(start);
            }
        }
        while (results.size() > ef) {
            results.poll();
        }
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

    /** Distâncias já calculadas durante uma inserção no grafo (o nó se repete em vários níveis); {@code null} fora dela. */
    private java.util.Map<String, Double> hnswDistances;

    private double vectorNodeDistance(Database db, String table, Catalog.IndexDef index, String key,
                                      AxonValue query) {
        java.util.Map<String, Double> cache = hnswDistances;
        if (cache != null) {
            Double known = cache.get(key);
            if (known != null) {
                return known;
            }
            double computed = vectorNodeDistanceUncached(db, table, index, key, query);
            cache.put(key, computed);
            return computed;
        }
        return vectorNodeDistanceUncached(db, table, index, key, query);
    }

    private double vectorNodeDistanceUncached(Database db, String table, Catalog.IndexDef index, String key,
                                              AxonValue query) {
        Document doc = loadDoc(db, new RecordId(table, parseKey(key)));
        if (doc == null || !doc.current().isObject()) {
            return Double.MAX_VALUE;
        }
        AxonValue vector = doc.current().asObject().get(index.columns().get(0));
        return validVector(vector, index.vectorDimension())
            ? vectorDistance(index.vectorDistance(), vector, query) : Double.MAX_VALUE;
    }

    /**
     * {@code WHERE geo::contains(<polígono>, campo)} com um índice GEO em {@code campo}: lê só as células que
     * cobrem a caixa do polígono. O polígono não pode depender da linha (literal, variável, {@code $parent...}
     * ou subquery); o teste exato é refeito pelo WHERE sobre os candidatos.
     */
    private List<Document> geoContainsSourceDocs(Database db, Statement.Select sel, Expr.Ident table, Expr.Call call,
                                                 java.util.function.Supplier<List<Document>> fallback) {
        if (!(call.args().get(1) instanceof Expr.Ident field) || !isRowIndependent(call.args().get(0))) {
            return fallback.get();
        }
        Catalog.IndexDef index = geoIndexOn(db.catalog().table(table.name()), field.name());
        if (index == null) {
            return fallback.get();
        }
        double[] box;
        try {
            box = GeoVector.bbox(eval(call.args().get(0)));
        } catch (RuntimeException e) {
            return fallback.get();
        }
        if (box == null) {
            return fallback.get();
        }
        List<Document> docs = new ArrayList<>();
        geoIndex(db, table.name(), index).box(records(db), box, id -> {
            Document doc = loadDoc(db, new RecordId(table.name(), parseKey(id)));
            if (doc != null) {
                docs.add(doc);
            }
            return true;
        });
        return docs;
    }

    /** Índice GEO pronto cuja coluna indexada (a primeira) é o campo. */
    private static Catalog.IndexDef geoIndexOn(Catalog.TableDef def, String column) {
        return def == null ? null : def.indexes().values().stream().filter(Catalog.IndexDef::ready)
            .filter(i -> i.geo() && i.columns().get(0).equals(column)).findFirst().orElse(null);
    }

    private static GeoIndex geoIndex(Database db, String table, Catalog.IndexDef index) {
        return new GeoIndex(db.ns(), db.db(), table, index.name());
    }

    /** A expressão não lê campos da linha (só literais, variáveis e subqueries), então vale o mesmo para todas. */
    private static boolean isRowIndependent(Expr e) {
        return switch (e) {
            case Expr.Literal l -> true;
            case Expr.Param p -> true;
            case Expr.SubQuery q -> true;
            case Expr.Idiom i -> isRowIndependent(i.base()) && i.parts().stream().allMatch(part -> switch (part) {
                case Expr.Part.Field f -> true;
                case Expr.Part.First f -> true;
                case Expr.Part.Last l -> true;
                case Expr.Part.Index x -> isRowIndependent(x.index());
                default -> false;
            });
            case Expr.Call c -> c.args().stream().allMatch(Executor::isRowIndependent);
            case Expr.ObjectLit o -> o.entries().stream().allMatch(en -> isRowIndependent(en.value()));
            case Expr.ArrayLit a -> a.items().stream().allMatch(Executor::isRowIndependent);
            case Expr.Unary u -> isRowIndependent(u.operand());
            case Expr.Binary b -> isRowIndependent(b.left()) && isRowIndependent(b.right());
            default -> false;
        };
    }

    private List<Document> geoSourceDocs(Database db, Statement.Select sel, java.util.function.Supplier<List<Document>> fallback) {
        return geoSourceDocs(db, sel, null, fallback);
    }

    /**
     * Fonte pelo índice GEO (ver {@link GeoIndex}). Raio: {@code geo::distance(campo, ponto) <= raio}; vizinhos mais
     * próximos: {@code ORDER BY geo::distance(campo, ponto) LIMIT n}; e {@code geo::contains}. O WHERE inteiro é
     * reavaliado sobre os documentos devolvidos. Com {@code window} a leitura para ao completar START+LIMIT linhas
     * que passam no filtro.
     */
    private List<Document> geoSourceDocs(Database db, Statement.Select sel, Window window,
                                         java.util.function.Supplier<List<Document>> fallback) {
        if (sel.from() instanceof Expr.Ident geoTable && sel.cond() instanceof Expr.Call contains
            && contains.name().equals("geo::contains") && contains.args().size() == 2) {
            return geoContainsSourceDocs(db, sel, geoTable, contains, fallback);
        }
        if (!(sel.from() instanceof Expr.Ident table) || !(sel.cond() instanceof Expr.Binary comparison)
            || (comparison.op() != Expr.BinaryOp.LT && comparison.op() != Expr.BinaryOp.LE)
            || !(comparison.left() instanceof Expr.Call distance)
            || !distance.name().equals("geo::distance") || distance.args().size() != 2
            || !(distance.args().get(0) instanceof Expr.Ident field)) {
            List<Document> nearest = geoNearestDocs(db, sel);
            return nearest != null ? nearest : fallback.get();
        }
        // centro e raio precisam valer para todas as linhas: aqui eles são avaliados sem nenhuma
        if (!isRowIndependent(distance.args().get(1)) || !isRowIndependent(comparison.right())) {
            return fallback.get();
        }
        AxonValue center = eval(distance.args().get(1));
        AxonValue radius = eval(comparison.right());
        double[] point = GeoIndex.pointOf(center);
        if (point == null || !radius.isNumber()) {
            return fallback.get();
        }
        Catalog.IndexDef index = geoIndexOn(db.catalog().table(table.name()), field.name());
        if (index == null) {
            return fallback.get();
        }
        List<Document> docs = new ArrayList<>();
        boolean served = geoIndex(db, table.name(), index).radius(records(db), point, radius.asDouble(), id -> {
            Document doc = loadDoc(db, new RecordId(table.name(), parseKey(id)));
            if (doc != null && (window == null || window.accepts().test(doc))) {
                docs.add(doc);
            }
            return window == null || docs.size() < window.wanted();
        });
        return served ? docs : fallback.get();
    }

    /** Plano dos vizinhos mais próximos: tabela, índice, centro e quantos (START+LIMIT). */
    private record NearestPlan(String table, Catalog.IndexDef index, double[] center, int wanted) {
    }

    /**
     * {@code ORDER BY geo::distance(campo, ponto) [ASC] LIMIT n} sobre tabela simples com índice GEO que só guarda
     * pontos, ou {@code null}. O WHERE (se houver) é reavaliado em cada candidato.
     */
    private NearestPlan nearestPlan(Database db, Statement.Select sel) {
        if (!(sel.from() instanceof Expr.Ident table) || sel.limit() == null || sel.orders().size() != 1
            || !sel.joins().isEmpty() || sel.grouped() || sel.having() != null
            || sel.fields().stream().anyMatch(this::isAggregateCall)
            || (sel.cond() != null && isSearchMatch(sel.cond()))
            // numa transação a busca registraria faixas do tamanho de continentes como lidas
            || (session.tx() != null && session.tx().isOpen())) {
            return null;
        }
        OrderTerm term = ordersByAlias(sel).get(0);
        if (term.descending() || !(term.field() instanceof Expr.Call call) || !call.name().equals("geo::distance")
            || call.args().size() != 2 || !(call.args().get(0) instanceof Expr.Ident field)
            || !isRowIndependent(call.args().get(1))) {
            return null;
        }
        Catalog.IndexDef index = geoIndexOn(db.catalog().table(table.name()), field.name());
        if (index == null || geoIndex(db, table.name(), index).hasUnlocated(records(db))) {
            return null;
        }
        double[] center = GeoIndex.pointOf(eval(call.args().get(1)));
        int wanted = windowEnd(sel);
        return !GeoIndex.isLocatable(center) || wanted <= 0 ? null
            : new NearestPlan(table.name(), index, center, wanted);
    }

    /** Os {@code n} mais próximos do ponto, em ordem de distância, sem ler o índice além do necessário. */
    private List<Document> geoNearestDocs(Database db, Statement.Select sel) {
        NearestPlan plan = nearestPlan(db, sel);
        if (plan == null) {
            return null;
        }
        String column = plan.index().columns().get(0);
        Catalog.TableDef tableDef = db.catalog().table(plan.table());
        boolean filtered = sel.cond() != null || !dataRulePredicates().isEmpty()
            || (tableDef != null && tableDef.permissions() != null);
        Window filter = filtered ? windowFor(db, sel) : null;
        List<Document> out = new ArrayList<>();
        java.util.Set<String> taken = new java.util.HashSet<>();
        int[] inPlace = {0};
        geoIndex(db, plan.table(), plan.index()).nearest(records(db), plan.center(), (id, meters, at) -> {
            if (taken.contains(id)) {
                return true;
            }
            Document doc = loadDoc(db, new RecordId(plan.table(), parseKey(id)));
            if (doc != null && GeoIndex.hasPoint(doc.current(), column)
                && (filter == null || filter.accepts().test(doc))) {
                taken.add(id);
                out.add(doc);
                // Quem se moveu depois de a entrada ser lida também entra (o ORDER BY reordena pelo ponto atual),
                // mas só quem está onde a entrada diz conta para encerrar a busca.
                if (GeoIndex.isAt(doc.current(), column, at)) {
                    inPlace[0]++;
                }
            }
            return inPlace[0] < plan.wanted();
        });
        return out;
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
        VectorSide vectorSide = null;
        for (Expr side : List.of(and.left(), and.right())) {
            VectorSide vs = vectorSide(side);
            if (vs != null && vectorSide == null) {
                vectorSide = vs;
            } else if (searchSide == null && isTextMatch(side)) {
                searchSide = (Expr.Binary) side;
            }
        }
        if (searchSide == null || vectorSide == null) {
            return null;
        }
        final String vectorField = vectorSide.field();
        String searchField = ((Expr.Ident) searchSide.left()).name();
        AxonValue query = eval(searchSide.right());
        Catalog.TableDef def = db().catalog().table(tableName(sel.from()));
        if (def == null) {
            return null;
        }
        Catalog.IndexDef searchIndex = def.indexes().values().stream().filter(Catalog.IndexDef::ready)
            .filter(i -> i.search() && i.columns().contains(searchField)).findFirst().orElse(null);
        Catalog.IndexDef vectorIndex = def.indexes().values().stream().filter(Catalog.IndexDef::ready)
            .filter(i -> i.vector() && i.columns().contains(vectorField)).findFirst().orElse(null);
        if (searchIndex == null || vectorIndex == null) {
            return null;
        }
        return new HybridSearch(searchIndex, vectorIndex, query.asString(), vectorSide.query());
    }

    /** Lado vetorial de uma consulta híbrida: o campo e o vetor da consulta. */
    private record VectorSide(String field, AxonValue query) {
    }

    /**
     * Lado vetorial de um AND: {@code vector::similarity::cosine(campo, vec) > limiar} ou
     * {@code campo @@ vetor} (sem limiar). {@code null} quando a expressão é outra coisa.
     */
    private VectorSide vectorSide(Expr e) {
        if (isVectorCond(e)) {
            Expr.Call vc = (Expr.Call) ((Expr.Binary) e).left();
            return new VectorSide(((Expr.Ident) vc.args().get(0)).name(), eval(vc.args().get(1)));
        }
        if (e instanceof Expr.Binary b && b.op() == Expr.BinaryOp.MATCH && b.left() instanceof Expr.Ident field) {
            AxonValue q = eval(b.right());
            if (arrayVector(q)) {
                return new VectorSide(field.name(), q);
            }
        }
        return null;
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

    /** {@code campo @@ "termos"}: busca textual (a consulta é texto). */
    private boolean isTextMatch(Expr e) {
        return e instanceof Expr.Binary b && b.op() == Expr.BinaryOp.MATCH && b.left() instanceof Expr.Ident
            && eval(b.right()).isString();
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
        Catalog.IndexDef index = def.indexes().values().stream().filter(Catalog.IndexDef::ready)
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
        if (from instanceof Expr.SubQuery || from instanceof Expr.ArrayLit || from instanceof Expr.Param) {
            // FROM $variavel: o valor guardado por LET (array de objetos) vira a fonte, como numa subquery
            AxonValue source = eval(from);
            List<Document> byId = docsOfRecordIds(db, source);
            if (byId != null) {
                return byId;
            }
            return virtualDocs(source.isArray() || source.isObject() ? source : AxonValue.array(List.of(source)));
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
        if (from instanceof Expr.Ident table) {
            ensureTable(db, table.name());
        }
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
        Database targetDb = localQualifiedDatabase(ns, q);
        String tableName = q.table();
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

    /** {@code banco.tabela} aponta para um banco do próprio namespace, e não para um DATABASE LINK. */
    private boolean isLocalQualified(Expr.Qualified q) {
        String ns = session.namespace();
        return ns != null && !ns.isBlank() && ds.databaseLink(ns, q.link()) == null;
    }

    /** O banco do namespace citado em {@code banco.tabela}, já com a tabela garantida. */
    private Database localQualifiedDatabase(String ns, Expr.Qualified q) {
        Database targetDb = ds.ensureDatabase(session, ns, q.link());
        ensureTable(targetDb, q.table());
        return targetDb;
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
            String keyStr = com.axonbase.parser.Render.key(rid.key());
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
    /** ORDER BY geo::distance(...) LIMIT n pelo índice GEO: amplia anéis de células até ter n certos. */
    private static final String PS_GEO_KNN = "GEO_KNN";
    private static final String PS_VECTOR = "VECTOR";
    private static final String PS_HYBRID = "HYBRID";
    private static final String PS_COLUMNAR = "COLUMNAR";
    /** Agregação respondida só pelas entradas do índice columnar, sem ler documentos. */
    private static final String PS_COLUMNAR_AGG = "COLUMNAR_AGG";

    /** {@code via}: a parte do WHERE (ligada por AND) que o índice atende, ou {@code null} se for o WHERE inteiro. */
    private record ExplainPlan(String strategy, Catalog.IndexDef index, String table, Expr via) {
        ExplainPlan(String strategy, Catalog.IndexDef index, String table) {
            this(strategy, index, table, null);
        }
    }

    /**
     * EXPLAIN devolve un object determinista co plan: strategy, table, indexName
     * (se hai) e examinedRows. Sen ANALYZE as filas son unha estimación e o SELECT
     * non se executa; con ANALYZE execútase a mesma fonte elixida e contánse as
     * filas reais que pasan pola etapa de orixe.
     */
    private AxonValue runExplain(Statement.Explain ex) {
        Database db = db();
        Statement.Select sel = rewriteEmbeddingMatches(ex.select());
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
        // WHERE a AND b: se uma das partes tem índice (UNIQUE, columnar ou GEO) é ela que abre a leitura
        List<Expr> parts = sel.cond() == null || !(sel.from() instanceof Expr.Ident) ? List.of() : conjuncts(sel.cond());
        if (parts.size() > 1) {
            for (Expr part : parts) {
                ExplainPlan viaPart = planFor(db, withCond(sel, part));
                if (PS_UNIQUE.equals(viaPart.strategy()) || PS_COLUMNAR.equals(viaPart.strategy())
                    || PS_GEO.equals(viaPart.strategy())) {
                    return new ExplainPlan(viaPart.strategy(), viaPart.index(), viaPart.table(), part);
                }
            }
        }
        return planFor(db, sel);
    }

    private ExplainPlan planFor(Database db, Statement.Select sel) {
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
        List<Catalog.IndexDef> covering = columnarCoveringIndexes(db, sel);
        if (covering != null) {
            return new ExplainPlan(PS_COLUMNAR_AGG, covering.get(0), table);
        }
        ix = vectorIndexFor(db, table, sel);
        if (ix != null) {
            return new ExplainPlan(PS_VECTOR, ix, table);
        }
        ix = geoIndexFor(db, table, sel);
        if (ix != null) {
            return new ExplainPlan(PS_GEO, ix, table);
        }
        NearestPlan nearest = nearestPlan(db, sel);
        if (nearest != null) {
            return new ExplainPlan(PS_GEO_KNN, nearest.index(), table);
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
        return def.indexes().values().stream().filter(Catalog.IndexDef::ready)
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
        return def.indexes().values().stream().filter(Catalog.IndexDef::ready)
            .filter(i -> i.vector() && i.columns().contains(field.name())).findFirst().orElse(null);
    }

    /**
     * `geo::distance(campo, centro) &lt; radio` ou `geo::contains(polígono, campo)`: índice GEO do campo.
     * O polígono não pode depender da linha.
     */
    private Catalog.IndexDef geoIndexFor(Database db, String table, Statement.Select sel) {
        if (!table.isBlank() && sel.cond() instanceof Expr.Call contains && contains.name().equals("geo::contains")
            && contains.args().size() == 2 && contains.args().get(1) instanceof Expr.Ident containsField
            && isRowIndependent(contains.args().get(0))) {
            Catalog.TableDef tdef = db.catalog().table(table);
            return geoIndexOn(tdef, containsField.name());
        }
        if (table.isBlank() || !(sel.cond() instanceof Expr.Binary comparison)
            || (comparison.op() != Expr.BinaryOp.LT && comparison.op() != Expr.BinaryOp.LE)
            || !(comparison.left() instanceof Expr.Call distance)
            || !distance.name().equals("geo::distance") || distance.args().size() != 2
            || !(distance.args().get(0) instanceof Expr.Ident field)
            || !isRowIndependent(distance.args().get(1)) || !isRowIndependent(comparison.right())) {
            return null;
        }
        return geoIndexOn(db.catalog().table(table), field.name());
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
        return def.indexes().values().stream().filter(Catalog.IndexDef::ready)
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
        if (bk instanceof com.axonbase.core.partition.PartitionedKv view && db.catalog().partitioning(table) == null) {
            bk = view.base(); // a vista só importa para tabela particionada; o histórico precisa do armazenamento
        }
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
        if (PS_COLUMNAR_AGG.equals(plan.strategy())) {
            return estimatedColumnarEntries(db, plan);
        }
        return countSourceRows(db, sel.from());
    }

    /**
     * Quantos documentos {@link #sourceDocs} devolveria. Numa tabela, conta as chaves em fluxo, sem montar a lista
     * nem ler os valores; as demais origens (registro, subconsulta, lista, DATABASE LINK) passam por
     * {@code sourceDocs}. Como ele, não filtra por regra de acesso.
     */
    private long countSourceRows(Database db, Expr from) {
        if (from instanceof Expr.Ident table) {
            ensureTable(db, table.name());
            return countRecords(db, table.name());
        }
        if (from instanceof Expr.Qualified q && isLocalQualified(q)) {
            return countRecords(localQualifiedDatabase(session.namespace(), q), q.table());
        }
        return sourceDocs(db, from).size();
    }

    private long countRecords(Database db, String table) {
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
        KvBackend kv = countingRecords(db);
        long[] count = {0};
        scanRecordKeys(kv, prefix, key -> {
            // como a leitura por loadDoc: vale a chave que o id lido dela alcança. No caso normal é ela mesma, e
            // nada é lido
            String reached = new RecordId(table, parseKey(key.substring(prefix.length())))
                .storageKey(db.ns(), db.db());
            if (reached.equals(key) || kv.get(reached).isPresent()) {
                count[0]++;
            }
            return true;
        });
        return count[0];
    }

    /** Como {@link #records}, para só contar: numa transação, não registra leituras nem guarda o que passa. */
    private KvBackend countingRecords(Database db) {
        KvBackend raw = rawRecords(db);
        if (raw instanceof Transaction tx) {
            raw = tx.untrackedReads();
        }
        return com.axonbase.core.partition.PartitionedKv.wrap(db, raw, partitions, ds.coldTier().records());
    }

    private long estimatedColumnarEntries(Database db, ExplainPlan plan) {
        long[] count = {0};
        countingRecords(db).scanPrefix(columnarCountPrefix(db, plan), key -> {
            QueryContext.checkCancelled();
            count[0]++;
            return true;
        });
        return count[0];
    }

    /** Entradas de uma coluna do índice columnar (uma por linha da tabela). */
    private long columnarEntryCount(Database db, ExplainPlan plan) {
        return records(db).keysWithPrefix(columnarCountPrefix(db, plan)).size();
    }

    private String columnarCountPrefix(Database db, ExplainPlan plan) {
        return com.axonbase.core.storage.ColumnarIndex.columnPrefix(
            db.ns(), db.db(), plan.table(), plan.index().name(), plan.index().columns().get(0));
    }

    private long explainedRows(Database db, Statement.Select whole, ExplainPlan plan) {
        Statement.Select sel = plan.via() == null ? whole : withCond(whole, plan.via());
        if (PS_COLUMNAR_AGG.equals(plan.strategy())) {
            return columnarEntryCount(db, plan);
        }
        List<Document> docs = switch (plan.strategy()) {
            case PS_UNIQUE -> loadUniqueDocs(db, sel, plan.index());
            case PS_FULLTEXT -> searchSourceDocs(db, sel);
            case PS_GEO, PS_GEO_KNN -> geoSourceDocs(db, sel, () -> fullScanDocs(db, sel));
            case PS_VECTOR -> vectorSourceDocs(db, sel, () -> fullScanDocs(db, sel));
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
                                                  java.util.function.Supplier<List<Document>> fallback) {
        Catalog.IndexDef index = uniqueIndexFor(db, tableName(sel.from()), sel);
        if (index == null) {
            return fallback.get();
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
    /** Quando o valor é uma lista (ou um único) de ids de registro, carrega os registros; senão, {@code null}. */
    private List<Document> docsOfRecordIds(Database db, AxonValue value) {
        List<AxonValue> items = value.isArray() ? value.asArray() : value.isRecordId() ? List.of(value) : null;
        if (items == null || items.isEmpty() || !items.stream().allMatch(AxonValue::isRecordId)) {
            return null;
        }
        List<Document> docs = new ArrayList<>();
        for (AxonValue item : items) {
            AxonValue.RecordId id = item.asRecordId();
            Document doc = loadDoc(db, new RecordId(id.table(), id.key() instanceof AxonValue kv ? kv : keyOf(id.key())));
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

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
        return GroupAggregation.containsAggregate(f);
    }

    /** Avalia expressões sobre uma linha para o agregador. */
    private GroupAggregation.RowEval groupRowEval() {
        return new GroupAggregation.RowEval() {
            @Override
            public AxonValue eval(Expr expr, Document row) {
                return evalExprInDoc(expr, row);
            }

            @Override
            public boolean truthy(AxonValue value) {
                return Executor.this.truthy(value);
            }
        };
    }

    /** FETCH sobre as colunas da linha já projetada: troca record ids (ou listas deles) pelos registros. */
    private AxonValue fetchProjected(AxonValue row, List<String> fields) {
        if (!row.isObject()) {
            return row;
        }
        Map<String, AxonValue> map = new LinkedHashMap<>(row.asObject());
        boolean changed = false;
        for (String name : fields) {
            AxonValue cur = map.get(name);
            if (cur == null) {
                continue;
            }
            if (cur.isRecordId()) {
                AxonValue filled = fetchOne(cur.asRecordId());
                if (filled != null) {
                    map.put(name, filled);
                    changed = true;
                }
            } else if (cur.isArray()) {
                List<AxonValue> filled = new ArrayList<>();
                boolean any = false;
                for (AxonValue item : cur.asArray()) {
                    AxonValue f = item.isRecordId() ? fetchOne(item.asRecordId()) : null;
                    filled.add(f != null ? f : item);
                    any |= f != null;
                }
                if (any) {
                    map.put(name, AxonValue.array(filled));
                    changed = true;
                }
            }
        }
        return changed ? AxonValue.object(map) : row;
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
            if (doc == null) {
                return null;
            }
            if (evaluatingPolicy.get() != null || !com.axonbase.core.security.Authz.hasDataRules(session.auth())) {
                return doc.current();
            }
            // acesso por id e FETCH: registro oculto não aparece e o visível vem mascarado
            return dataRuleFilter(doc) ? applyDataRuleMask(doc.current()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSingleTarget(Expr from) {
        return from instanceof Expr.RecordId;
    }

    /**
     * {@code ORDER BY apelido}: quando o nome é o apelido de uma coluna calculada do SELECT
     * ({@code geo::distance(...) AS metros}), ordena pela expressão; sem isso o apelido não existe no documento
     * de origem e a ordenação seria ignorada. Colunas simples e nomes que não são apelido ficam como estão.
     */
    private static List<OrderTerm> ordersByAlias(Statement.Select sel) {
        if (sel.orders().isEmpty()) {
            return sel.orders();
        }
        Map<String, Expr> aliases = new java.util.HashMap<>();
        for (Expr f : sel.fields()) {
            if (f instanceof Expr.Alias a && !(a.expr() instanceof Expr.Ident)) {
                aliases.put(a.name(), a.expr());
            }
        }
        if (aliases.isEmpty()) {
            return sel.orders();
        }
        List<OrderTerm> out = new ArrayList<>();
        for (OrderTerm ot : sel.orders()) {
            Expr target = ot.field() instanceof Expr.Ident id ? aliases.get(id.name()) : null;
            out.add(target != null ? new OrderTerm(target, ot.descending()) : ot);
        }
        return out;
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
            return c.name().equals(Expr.COUNT_DISTINCT) ? "count" : c.name();
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
        Map<String, AxonValue> assigned = new LinkedHashMap<>();
        if (r.data() instanceof Data.SetClause sc) {
            for (Assignment a : sc.assignments()) {
                assigned.put(fieldOf(a.path()), evalExpr(a.value()));
            }
        }
        if (from.isRecordId() && to.isRecordId()) {
            AxonValue.RecordId fromR = from.asRecordId();
            AxonValue.RecordId toR = to.asRecordId();
            // o tipo da aresta passa a existir no catálogo (como DEFINE TABLE tipo SCHEMALESS), que é por onde
            // INFO FOR DATABASE e a página Relations graph do Enterprise descobrem as arestas de um banco
            if (Datastore.PLAIN_NAME.matcher(kind).matches()) {
                ensureTable(db, kind);
            }
            // in e out são as pontas da aresta, não propriedades
            Map<String, AxonValue> properties = new LinkedHashMap<>(assigned);
            properties.remove("in");
            properties.remove("out");
            persistEdge(db, fromR, kind, toR, properties);
        }
        Map<String, AxonValue> edge = new LinkedHashMap<>();
        edge.put("in", from);
        edge.put("out", to);
        edge.putAll(assigned);
        return AxonValue.object(edge);
    }

    /**
     * UNRELATE a->tipo->b remove a aresta (nas duas direções); UNRELATE a->tipo remove todas as arestas de saída de
     * {@code a} com esse tipo. Devolve as arestas removidas (lista vazia se não havia nenhuma).
     */
    private AxonValue runUnrelate(Statement.Unrelate u) {
        if (u.from() instanceof Expr.Qualified || u.to() instanceof Expr.Qualified
            || (u.from() instanceof Expr.RecordId fr && fr.table() instanceof Expr.Qualified)
            || (u.to() instanceof Expr.RecordId tr && tr.table() instanceof Expr.Qualified)) {
            throw errorStmt(Messages.get("stmt_unrelate_link_unsupported"));
        }
        Database db = db();
        AxonValue from = mapping(u.from());
        AxonValue to = u.to() == null ? null : mapping(u.to());
        String kind = tableOf(u.kind());
        List<AxonValue> removed = new ArrayList<>();
        if (!from.isRecordId() || (to != null && !to.isRecordId())) {
            return AxonValue.array(removed);
        }
        AxonValue.RecordId f = from.asRecordId();
        if (to != null) {
            if (removeEdge(db, f, kind, to.asRecordId())) {
                removed.add(edgeValue(from, to));
            }
            return AxonValue.array(removed);
        }
        String prefix = edgePrefix(db, "out", f) + kind + "|";
        for (String key : records(db).keysWithPrefix(prefix)) {
            String[] parts = key.substring(prefix.length()).split("\\|", 2);
            if (parts.length < 2) {
                continue;
            }
            AxonValue target = AxonValue.record(parts[0], parseKeyVal(parts[1]));
            if (removeEdge(db, f, kind, target.asRecordId())) {
                removed.add(edgeValue(from, target));
            }
        }
        return AxonValue.array(removed);
    }

    private static AxonValue edgeValue(AxonValue from, AxonValue to) {
        Map<String, AxonValue> edge = new LinkedHashMap<>();
        edge.put("in", from);
        edge.put("out", to);
        return AxonValue.object(edge);
    }

    /** Apaga a chave de saída e o espelho de entrada; {@code true} se a aresta existia. */
    private boolean removeEdge(Database db, AxonValue.RecordId from, String kind, AxonValue.RecordId to) {
        boolean existed = records(db).delete(edgeKey(db, "out", from, kind, to));
        existed |= records(db).delete(edgeKey(db, "in", to, kind, from));
        return existed;
    }

    /** Há alguma aresta neste banco? Uma consulta de uma chave só, para o DELETE em massa não pagar a cascata à toa. */
    private boolean hasAnyEdge(Database db) {
        boolean[] found = {false};
        records(db).scanPrefix("E|" + db.ns() + "|" + db.db() + "|", key -> {
            found[0] = true;
            return false;
        });
        return found[0];
    }

    /** Apaga as arestas que saem e chegam num registro (e os espelhos delas): registro apagado não deixa aresta solta. */
    private void removeEdgesOf(Database db, RecordId rid) {
        String self = rid.table() + "|" + keyToken(rid.key());
        String ns = db.ns();
        String dbName = db.db();
        for (String dir : new String[] {"out", "in"}) {
            String other = dir.equals("out") ? "in" : "out";
            String prefix = "E|" + ns + "|" + dbName + "|" + dir + "|" + self + "|";
            for (String key : records(db).keysWithPrefix(prefix)) {
                // kind|tabelaDoOutroLado|chaveDoOutroLado
                String[] parts = key.substring(prefix.length()).split("\\|", 3);
                records(db).delete(key);
                if (parts.length == 3) {
                    records(db).delete("E|" + ns + "|" + dbName + "|" + other + "|" + parts[1] + "|" + parts[2] + "|"
                        + parts[0] + "|" + self);
                }
            }
        }
    }

    /** Apaga todas as arestas de um tipo (DROP TABLE do tipo). */
    private void removeEdgesOfKind(Database db, String kind) {
        String ns = db.ns();
        String dbName = db.db();
        String prefix = "E|" + ns + "|" + dbName + "|out|";
        List<String> keys = new ArrayList<>();
        List<String> mirrors = new ArrayList<>();
        records(db).scanPrefix(prefix, key -> {
            // tabelaOrigem|chaveOrigem|tipo|tabelaDestino|chaveDestino
            String[] p = key.substring(prefix.length()).split("\\|", 5);
            if (p.length == 5 && p[2].equals(kind)) {
                keys.add(key);
                mirrors.add("E|" + ns + "|" + dbName + "|in|" + p[3] + "|" + p[4] + "|" + kind + "|" + p[0] + "|" + p[1]);
            }
            return true;
        });
        keys.forEach(records(db)::delete);
        mirrors.forEach(records(db)::delete);
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
            def.checks(previous.checks());
            def.keyedById(previous.keyedById());
        }
        def.permissions(dt.permissions());
        def.shardBy(dt.shardBy());
        db.catalog().defineTable(def);
        persistTable(db, dt.name());
        return AxonValue.nul();
    }

    /**
     * Grava a tabela como ela está agora. O plano de controle guarda uma definição por tabela, então gravar o comando
     * recebido (um ALTER, um DEFINE TABLE, um segundo CREATE TABLE) faria o reinício esquecer o que veio antes. As
     * colunas declaradas por CREATE ou ALTER TABLE vão num CREATE TABLE; as de DEFINE FIELD têm definição própria.
     */
    private void persistTable(Database db, String table) {
        if (ds.isReplaying()) {
            return;
        }
        Catalog.TableDef def = db.catalog().table(table);
        java.util.Set<String> fieldDefinitions = ds.controlSnapshot().commands().stream()
            .filter(c -> c.kind() == ControlCommand.Kind.FIELD && c.namespace().equals(db.ns())
                && c.database().equals(db.db()))
            .map(ControlCommand::name)
            .collect(java.util.stream.Collectors.toSet());
        List<Statement.ColumnDef> columns = def.fields().values().stream()
            .filter(field -> !fieldDefinitions.contains(table + ":" + field.name()))
            .sorted(java.util.Comparator.comparing(Catalog.FieldDef::name))
            .map(field -> new Statement.ColumnDef(field.name(), field.type() == null ? "any" : field.type(),
                field.primaryKey(), field.notNull(), field.defaultExpr(), field.assertExpr(), field.references()))
            .collect(java.util.stream.Collectors.toList());
        if (def.keyedById()) {
            // a coluna id não vira campo, mas precisa voltar para a chave composta continuar sendo a mesma
            columns.add(0, new Statement.ColumnDef("id", "string", true, false, null, null, null));
        }
        String definition = com.axonbase.parser.Render.stmt(new DefineTable(table, def.schemafull(), def.drop(),
            def.permissions(), def.shardBy()));
        if (!columns.isEmpty() || !def.checks().isEmpty()) {
            // o CREATE TABLE vem primeiro: o DEFINE TABLE mantém as colunas e repõe as opções da tabela
            definition = com.axonbase.parser.Render.stmt(new Statement.CreateTable(table, false, def.schemafull(),
                columns, def.checks())) + ";\n" + definition;
        }
        control(db, ControlCommand.Kind.TABLE, table, definition);
    }

    /** A coluna passou a ser declarada pela tabela (ou saiu dela): um DEFINE FIELD antigo não pode repô-la. */
    private void forgetFieldDefinition(Database db, String table, String field) {
        ds.removeControl(session, db.ns(), db.db(), ControlCommand.Kind.FIELD, table + ":" + field);
    }

    private AxonValue runCreateTable(Statement.CreateTable ct) {
        Database db = db();
        if (ct.ifNotExists() && db.catalog().table(ct.name()) != null) {
            return AxonValue.nul();
        }
        Catalog.TableDef previous = db.catalog().table(ct.name());
        Catalog.TableDef def = new Catalog.TableDef(ct.name(), ct.schemafull(), false);
        if (previous != null) {
            def.shardBy(previous.shardBy());
            def.fields().putAll(previous.fields());
            def.indexes().putAll(previous.indexes());
            def.events().putAll(previous.events());
            def.checks(previous.checks());
            def.keyedById(previous.keyedById());
        }
        List<Expr> checks = new ArrayList<>(def.checks());
        ct.checks().stream().filter(check -> !checks.contains(check)).forEach(checks::add);
        def.checks(checks);
        db.catalog().defineTable(def);

        for (Statement.ColumnDef col : ct.columns()) {
            if ("id".equalsIgnoreCase(col.name())) {
                if (col.primaryKey()) {
                    def.keyedById(true);
                }
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
            def.fields().put(col.name(), fd.constrained(col.notNull(), col.primaryKey()));
            forgetFieldDefinition(db, ct.name(), col.name());
        }

        persistTable(db, ct.name());
        refreshPrimaryKey(db, ct.name());
        return AxonValue.nul();
    }

    private AxonValue runDefineField(DefineField df) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, df.table());
        if (df.embeddingName() != null) {
            checkEmbeddingField(db, def, df);
        }
        def.fields().put(df.name(),
            new Catalog.FieldDef(df.name(), df.type(), df.readonly(), df.assertExpr(), df.defaultExpr(),
                df.references(), df.embeddingName(), df.embeddingFrom()));
        control(db, ControlCommand.Kind.FIELD, df.table() + ":" + df.name(),
            com.axonbase.parser.Render.stmt(df));
        return AxonValue.nul();
    }

    private AxonValue runDefineIndex(DefineIndex di) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, di.table());
        // no replay a definição já foi aceita: recusá-la agora impediria a base de abrir
        if (!ds.isReplaying()) {
            com.axonbase.core.partition.PartitionRunner.checkIndex(db, di.table(), di.name(),
                di.searchAnalyzer() != null || di.geo() || di.vectorDimension() != null || di.count());
        }
        // no replay o analyzer pode ter sido apagado depois do índice: o índice continua, como antes do reinício
        if (!ds.isReplaying() && di.searchAnalyzer() != null && db.catalog().analyzer(di.searchAnalyzer()) == null) {
            throw errorStmt(Messages.get("stmt_analyzer_missing", di.searchAnalyzer()));
        }
        if (di.vectorDimension() != null) {
            for (String column : di.columns()) {
                Catalog.EmbeddingDef bound = embeddingOfField(db, di.table(), column);
                if (bound != null && bound.dimension() != di.vectorDimension()) {
                    throw errorStmt(Messages.get("embedding_index_dimension", di.name(), di.vectorDimension(), column,
                        bound.name(), bound.dimension()));
                }
            }
        }
        Catalog.IndexDef newIndex = new Catalog.IndexDef(di.name(), di.columns(), di.unique(), di.count(),
            di.searchAnalyzer(), di.geo(), di.columnar(), di.vectorDimension(), di.vectorDistance(),
            di.m(), di.efConstruction(), di.efSearch());
        Catalog.IndexDef previous = def.indexes().get(di.name());
        boolean sameUnique = previous != null && previous.unique() && di.unique()
            && previous.columns().equals(di.columns());
        boolean advanced = di.searchAnalyzer() != null || di.geo() || di.columnar() || di.vectorDimension() != null;
        // em construção sem ninguém construindo: parou (queda ou camada fria fora), e redefinir é tentar de novo
        boolean stalled = previous != null && !previous.ready()
            && !IndexBuilder.running(ds, db.ns(), db.db(), di.table(), di.name());
        String marker = buildMarkerKey(db, di.table(), di.name());
        def.indexFailures().remove(di.name());
        String definition = com.axonbase.parser.Render.stmt(di);
        String controlName = di.table() + ":" + di.name();
        if (ds.isReplaying()) {
            // No replay as entradas já estão no KV. Só um marcador de construção (queda no meio) manda refazer.
            // O GEO decide pelo próprio formato: índice geohash antigo ou atrasado é reconstruído (ver GeoMigration).
            boolean interrupted = di.geo() ? GeoMigration.mustRebuild(db, di.table(), di.name())
                : records(db).get(marker).isPresent();
            if (interrupted) {
                newIndex.markBuilding();
            }
            def.indexes().put(di.name(), newIndex);
            control(db, ControlCommand.Kind.INDEX, controlName, definition);
            if (interrupted) {
                // se a construção anterior ainda está saindo, outra começa quando ela terminar
                IndexBuilder.restartAsync(ds, db.ns(), db.db(), di.table(), di.name());
            }
            return AxonValue.nul();
        }
        // Redefinir o mesmo UNIQUE é idempotente: só um índice novo precisa olhar os dados.
        if (!((di.unique() && !sameUnique) || advanced || stalled)) {
            if (previous != null && previous.geo()) {
                // o GEO deixa marcador e entradas: saem junto com a troca, sem escrita no meio
                java.util.concurrent.locks.Lock swap = exclusiveIndexLock(db);
                swap.lock();
                try {
                    purgeIndexEntries(db, di.table(), previous);
                    records(db).delete(marker);
                    def.indexes().put(di.name(), newIndex);
                } finally {
                    swap.unlock();
                }
            }
            def.indexes().put(di.name(), newIndex);
            control(db, ControlCommand.Kind.INDEX, controlName, definition);
            return AxonValue.nul();
        }
        // sem as linhas frias ao alcance, nada é apagado nem registrado
        ds.coldTier().requireReadable(db, di.table());
        java.util.concurrent.locks.Lock write = db.indexLock().writeLock();
        write.lock();
        try {
            if (previous != null && (previous.search() || previous.geo() || previous.columnar() || previous.vector()
                || (previous.unique() && !sameUnique))) {
                purgeIndexEntries(db, di.table(), previous);
            }
            records(db).put(marker, new byte[]{1});
            newIndex.markBuilding();
            def.indexes().put(di.name(), newIndex);
            bumpIndexEpoch(db);
        } finally {
            write.unlock();
        }
        // numa importação a construção em segundo plano não veria as linhas da transação ainda não confirmada
        if (session.restoring() || countRows(db, di.table(), db.indexInlineRows() + 1) <= db.indexInlineRows()) {
            // tabela pequena: constrói no próprio comando, e uma duplicata falha aqui, sem registrar nada
            IndexBuilder.build(ds, this, db.ns(), db.db(), di.table(), di.name(), false, stalled);
            control(db, ControlCommand.Kind.INDEX, controlName, definition);
        } else {
            control(db, ControlCommand.Kind.INDEX, controlName, definition);
            IndexBuilder.startAsync(ds, db.ns(), db.db(), di.table(), di.name(), false);
        }
        return AxonValue.nul();
    }

    /**
     * O lado de escrita do indexLock, ou a própria leitura quando esta thread já a segura (um evento que faz DDL no
     * meio de uma escrita): pedir a escrita nesse caso travaria para sempre.
     */
    private static java.util.concurrent.locks.Lock exclusiveIndexLock(Database db) {
        return db.indexLock().getReadHoldCount() > 0 ? db.indexLock().readLock() : db.indexLock().writeLock();
    }

    /** Avança a época de índices; o DDL da própria transação aberta não a invalida. */
    private void bumpIndexEpoch(Database db) {
        long now = db.bumpIndexEpoch();
        if (session.inTransaction() && session.txIndexEpoch() == now - 1) {
            session.txIndexEpoch(now);
        }
    }

    private static String buildMarkerKey(Database db, String table, String index) {
        return "IXB|" + db.ns() + "|" + db.db() + "|" + table + "|" + index;
    }

    /** Conta registros da tabela, parando em {@code limit}. */
    private int countRows(Database db, String table, int limit) {
        String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
        int[] n = {0};
        records(db).scanPrefix(prefix, k -> {
            if (k.length() > prefix.length() && k.charAt(prefix.length()) != '!') {
                n[0]++;
            }
            return n[0] < limit;
        });
        return n[0];
    }

    // ---- construção de índice em segundo plano (ver IndexBuilder) ----

    /** Prepara este executor para a thread de construção, que não tem sessão de cliente. */
    Executor forBuild(Session buildSession) {
        this.session = buildSession;
        return this;
    }

    /** Índice que a indexação em curso deve tocar; {@code null} nas escritas normais, que mantêm todos. */
    private Catalog.IndexDef onlyIndex;

    /** Roda o trabalho num lote atômico quando o backend tem lote; senão, direto. */
    private void inBatch(Database db, Runnable work) {
        if (batch != null || !(db.records() instanceof com.axonbase.core.storage.RocksDbBackend rocks)) {
            work.run();
            return;
        }
        com.axonbase.core.storage.StatementBatch mine = new com.axonbase.core.storage.StatementBatch(rocks);
        batch = mine;
        try {
            work.run();
            batch = null;
            mine.commit();
        } finally {
            batch = null;
            mine.discard();
        }
    }

    /** Indexa um bloco de registros (chaves já sem o prefixo da tabela). Lança em duplicata de UNIQUE. */
    void buildChunk(Database db, String table, Catalog.IndexDef index, List<String> keyParts) {
        if (index.geo()) {
            buildGeoChunk(db, table, index, keyParts);
            return;
        }
        inBatch(db, () -> {
            for (String part : keyParts) {
                indexRow(db, table, index, new RecordId(table, parseKey(part)));
            }
        });
    }

    /**
     * Bloco do índice GEO. Num seguidor, um lote replicado pode mudar um registro entre a leitura e a gravação da
     * entrada (o consenso não usa o indexLock): cada registro é relido depois de gravar e refeito se mudou.
     */
    private void buildGeoChunk(Database db, String table, Catalog.IndexDef index, List<String> keyParts) {
        String column = index.columns().get(0);
        List<String> pending = keyParts;
        while (!pending.isEmpty()) {
            List<String> round = pending;
            Map<String, byte[]> seen = new java.util.HashMap<>();
            inBatch(db, () -> {
                for (String part : round) {
                    RecordId rid = new RecordId(table, parseKey(part));
                    byte[] raw = records(db).get(rid.storageKey(db.ns(), db.db())).orElse(null);
                    seen.put(part, raw);
                    AxonValue record = raw == null ? null : decode(raw);
                    AxonValue geometry = record != null && record.isObject() ? record.asObject().get(column) : null;
                    geoIndex(db, table, index).update(records(db), rid.key().keyString(), geometry);
                }
            });
            pending = round.stream().filter(part -> !java.util.Arrays.equals(seen.get(part), records(db)
                .get(new RecordId(table, parseKey(part)).storageKey(db.ns(), db.db())).orElse(null))).toList();
        }
    }

    private void indexRow(Database db, String table, Catalog.IndexDef index, RecordId rid) {
        Document doc = loadDoc(db, rid);
        if (doc == null) {
            return;
        }
        AxonValue current = doc.current();
        if (index.unique()) {
            String prefix = uniqueIndexPrefix(db, table, index, current);
            if (prefix != null) {
                String own = prefix + rid.key().keyString();
                if (records(db).keysWithPrefix(prefix).stream().anyMatch(k -> !k.equals(own))) {
                    throw AxonError.internal(Messages.get("stmt_unique_index_violation", index.name()));
                }
                records(db).put(own, new byte[0]);
            }
        }
        onlyIndex = index;
        try {
            // uma escrita concorrente já pode ter indexado a linha: o tamanho do documento marca isso
            if (index.search() && records(db).get(docLenKey(db, table, index.name(), rid)).isEmpty()) {
                indexSearch(db, rid, null, current);
            }
            if (index.vector()) {
                indexAdvanced(db, rid, null, current);
            }
            if (index.columnar()) {
                indexColumnar(db, rid, null, current);
            }
        } finally {
            onlyIndex = null;
        }
    }

    /** Limpa as entradas parciais antes de refazer o índice. O marcador de construção é gravado antes de tudo. */
    void purgeForRebuild(Database db, String table, Catalog.IndexDef index) {
        records(db).put(buildMarkerKey(db, table, index.name()), new byte[]{1});
        purgeIndexEntries(db, table, index);
    }

    /** Apaga o que o índice geohash deixou, depois de garantir o marcador para o binário antigo. */
    void dropLegacyGeoEntries(Database db, String table, Catalog.IndexDef index) {
        if (index.geo()) {
            records(db).put(buildMarkerKey(db, table, index.name()), new byte[]{1});
            geoIndex(db, table, index).purgeLegacy(records(db), prefix -> deleteAll(db, prefix));
        }
    }

    /**
     * Última etapa: grava as chaves de "pronto" e apaga o marcador no mesmo lote. O índice GEO mantém o marcador de
     * propósito (ver {@link GeoIndex#tripwireKey}).
     *
     * @return {@code false} quando a construção precisa ser refeita: um binário antigo escreveu entradas geohash
     *     durante a varredura
     */
    boolean finishIndex(Database db, String table, Catalog.IndexDef index) {
        if (hasLegacyGeoEntries(db, table, index)) {
            return false;
        }
        inBatch(db, () -> {
            if (index.columnar()) {
                records(db).put(com.axonbase.core.storage.ColumnarIndex.readyKey(db.ns(), db.db(), table, index.name()),
                    new byte[]{1});
            }
            if (index.geo()) {
                geoIndex(db, table, index).markReady(records(db));
            } else {
                records(db).delete(buildMarkerKey(db, table, index.name()));
            }
        });
        return true;
    }

    /** Há escrita de um binário antigo no índice GEO. */
    boolean hasLegacyGeoEntries(Database db, String table, Catalog.IndexDef index) {
        return index.geo() && geoIndex(db, table, index).hasLegacyEntries(records(db));
    }

    /** Desfaz uma construção que falhou: tira o índice do catálogo e do plano de controle e apaga suas entradas. */
    void abortIndex(Database db, String table, Catalog.IndexDef index, String message) {
        Catalog.TableDef def = db.catalog().table(table);
        if (def != null) {
            def.indexes().remove(index.name(), index);
            def.indexFailures().put(index.name(), message);
        }
        purgeIndexEntries(db, table, index);
        ds.removeControl(session, db.ns(), db.db(), ControlCommand.Kind.INDEX, table + ":" + index.name());
        records(db).delete(buildMarkerKey(db, table, index.name()));
    }

    /** Apaga as chaves com o prefixo, em lotes, sem listar tudo na memória. */
    private void deleteAll(Database db, String prefix) {
        while (true) {
            List<String> batchKeys = new ArrayList<>(10_000);
            records(db).scanPrefix(prefix, k -> {
                batchKeys.add(k);
                return batchKeys.size() < 10_000;
            });
            if (batchKeys.isEmpty()) {
                return;
            }
            for (String k : batchKeys) {
                records(db).delete(k);
            }
        }
    }

    /** Apaga todas as entradas que um índice mantém, qualquer que seja o tipo. */
    void purgeIndexEntries(Database db, String table, Catalog.IndexDef index) {
        String name = index.name();
        String base = "|" + db.ns() + "|" + db.db() + "|" + table + "|" + name + "|";
        if (index.unique()) {
            Catalog.TableDef def = db.catalog().table(table);
            boolean sharedColumn = index.columns().size() == 1 && def != null && def.indexes().values().stream()
                .anyMatch(o -> o != index && o.unique() && o.columns().equals(index.columns()));
            if (!sharedColumn) {
                String head = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000!uidx\u0000";
                deleteAll(db, head + (index.columns().size() == 1 ? index.columns().get(0) : name) + "\u0000");
            }
        }
        if (index.columnar()) {
            records(db).delete(com.axonbase.core.storage.ColumnarIndex.readyKey(db.ns(), db.db(), table, name));
            deleteAll(db, com.axonbase.core.storage.ColumnarIndex.indexPrefix(db.ns(), db.db(), table, name));
        }
        if (index.vector()) {
            records(db).delete(vhTopKey(db, table, name));
            deleteAll(db, "VX" + base);
            deleteAll(db, "VH" + base);
        }
        if (index.geo()) {
            geoIndex(db, table, index).purge(records(db), prefix -> deleteAll(db, prefix));
        }
        if (index.search()) {
            deleteAll(db, "FT" + base);
            deleteAll(db, "FTDF" + base);
            deleteAll(db, "FTLEN" + base);
        }
    }

    /**
     * Preenche um índice UNIQUE criado sobre registros que já existem e recusa o índice se algum
     * valor se repete. Valida tudo antes de gravar, para que uma duplicata não deixe entradas
     * pela metade. Registros sem o campo ficam fora do índice, como na escrita normal.
     */
    private void backfillUniqueIndex(Database db, String table, Catalog.IndexDef index) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> entries = new ArrayList<>();
        for (RecordId rid : resolveTargets(new Expr.Ident(table))) {
            Document doc = loadDoc(db, rid);
            if (doc == null) {
                continue;
            }
            String prefix = uniqueIndexPrefix(db, table, index, doc.current());
            if (prefix == null) {
                continue;
            }
            if (!seen.add(prefix)) {
                throw AxonError.internal(Messages.get("stmt_unique_index_violation", index.name()));
            }
            entries.add(prefix + rid.key().keyString());
        }
        for (String entry : entries) {
            records(db).put(entry, new byte[0]);
        }
    }

    // ------------------------------------------------------------------
    // EMBEDDING: provedor HTTP que transforma texto em vetor
    // ------------------------------------------------------------------

    private AxonValue runDefineEmbedding(Statement.DefineEmbedding de) {
        Database db = db();
        if (!"http".equals(de.provider())) {
            throw errorStmt(Messages.get("embedding_provider_unsupported", de.provider()));
        }
        ds.embeddings().checkUrl(de.url());
        db.catalog().defineEmbedding(new Catalog.EmbeddingDef(de.name(), de.url(), de.model(), de.dimension(),
            de.keyEnv(), de.timeoutSeconds() == null ? Catalog.EmbeddingDef.DEFAULT_TIMEOUT_SECONDS : de.timeoutSeconds(),
            de.batch() == null ? Catalog.EmbeddingDef.DEFAULT_BATCH : de.batch(), de.documentPrefix(),
            de.queryPrefix(), de.sendDimension(), de.authHeader()));
        control(db, ControlCommand.Kind.EMBEDDING, de.name(), com.axonbase.parser.Render.stmt(de));
        return AxonValue.nul();
    }

    private AxonValue runDropEmbedding(Statement.DropEmbedding de) {
        Database db = db();
        if (db.catalog().embedding(de.name()) == null) {
            throw errorStmt(Messages.get("embedding_missing", de.name()));
        }
        for (Catalog.TableDef table : db.catalog().tables()) {
            for (Catalog.FieldDef f : table.fields().values()) {
                if (de.name().equals(f.embeddingName())) {
                    throw errorStmt(Messages.get("embedding_in_use", de.name(), table.name(), f.name()));
                }
            }
        }
        db.catalog().removeEmbedding(de.name());
        ds.removeControl(session, db.ns(), db.db(), ControlCommand.Kind.EMBEDDING, de.name());
        return AxonValue.nul();
    }

    /** Valida {@code DEFINE FIELD ... TYPE vector<N> EMBEDDING e FROM f}: o embedding existe e as dimensões batem. */
    private void checkEmbeddingField(Database db, Catalog.TableDef table, Statement.DefineField df) {
        Catalog.EmbeddingDef embedding = db.catalog().embedding(df.embeddingName());
        if (embedding == null) {
            throw errorStmt(Messages.get("embedding_missing", df.embeddingName()));
        }
        if (vectorTypeSize(df.type()) != embedding.dimension()) {
            throw errorStmt(Messages.get("embedding_field_type", df.name(), embedding.dimension(),
                embedding.dimension()));
        }
        for (Catalog.IndexDef ix : table.indexes().values()) {
            if (ix.vector() && ix.columns().contains(df.name()) && !ix.vectorDimension().equals(embedding.dimension())) {
                throw errorStmt(Messages.get("embedding_index_dimension", ix.name(), ix.vectorDimension(), df.name(),
                    embedding.name(), embedding.dimension()));
            }
        }
    }

    /** N de {@code vector<N>}, ou -1 se o tipo é outro. */
    private static int vectorTypeSize(String type) {
        if (type == null) {
            return -1;
        }
        String t = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (!t.startsWith("vector<") || !t.endsWith(">")) {
            return -1;
        }
        try {
            return Integer.parseInt(t.substring(7, t.length() - 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Embedding que um campo segue, ou {@code null}. */
    private Catalog.EmbeddingDef embeddingOfField(Database db, String table, String field) {
        Catalog.TableDef def = db.catalog().table(table);
        Catalog.FieldDef fd = def == null ? null : def.fields().get(field);
        return fd == null || fd.embeddingName() == null ? null : db.catalog().embedding(fd.embeddingName());
    }

    /** Embedding de um nome (ou o padrão {@code default}); erro claro se não existe. */
    private Catalog.EmbeddingDef requireEmbedding(String name) {
        Catalog.EmbeddingDef def = db().catalog().embedding(name);
        if (def == null) {
            throw errorStmt(Messages.get("embedding_missing", name));
        }
        return def;
    }

    /** {@code vector::embed(texto [, nome])} e o apelido {@code vector::fromstr}. */
    private AxonValue invokeEmbed(List<AxonValue> args) {
        if (args.isEmpty() || args.get(0) == null || args.get(0).isNull() || args.get(0).isNone()) {
            return AxonValue.nul();
        }
        if (!args.get(0).isString()) {
            throw errorStmt(Messages.get("embedding_input_required"));
        }
        String name = args.size() > 1 && args.get(1).isString() ? args.get(1).asString() : "default";
        Catalog.EmbeddingDef def = requireEmbedding(name);
        return com.axonbase.core.embedding.EmbeddingClient.toValue(ds.embeddings().embedOne(def,
            args.get(0).asString(), com.axonbase.core.embedding.EmbeddingClient.Role.QUERY));
    }

    /**
     * Preenche os campos que seguem um embedding a partir do texto de origem. Na criação (ou quando o texto de
     * origem mudou) o vetor é calculado e gravado; se o registro já traz o vetor na mão, ele é mantido (só a
     * dimensão é conferida). Origem ausente ou vazia não gera vetor. Um provedor fora do ar derruba a escrita:
     * nunca se grava registro sem o vetor que o chamador esperava.
     */
    private void autoEmbed(Database db, Catalog.TableDef def, Map<String, AxonValue> obj, AxonValue before) {
        List<Catalog.FieldDef> fields = def.fields().values().stream().filter(f -> f.embeddingName() != null).toList();
        if (fields.isEmpty()) {
            return;
        }
        Map<Catalog.EmbeddingDef, List<Catalog.FieldDef>> byEmbedding = new LinkedHashMap<>();
        Map<Catalog.FieldDef, String> texts = new LinkedHashMap<>();
        for (Catalog.FieldDef fd : fields) {
            Catalog.EmbeddingDef embedding = db.catalog().embedding(fd.embeddingName());
            if (embedding == null) {
                throw errorStmt(Messages.get("embedding_missing", fd.embeddingName()));
            }
            AxonValue source = obj.get(fd.embeddingFrom());
            if (source == null || source.isNull() || source.isNone()) {
                continue;
            }
            if (!source.isString()) {
                throw errorStmt(Messages.get("embedding_source_not_text", fd.embeddingFrom()));
            }
            if (source.asString().isBlank()) {
                continue;
            }
            AxonValue given = obj.get(fd.name());
            boolean sourceChanged = before == null || !before.isObject()
                || !source.equals(before.asObject().get(fd.embeddingFrom()));
            boolean hasVector = given != null && given.isArray();
            boolean givenChanged = hasVector && (before == null || !before.isObject()
                || !given.equals(before.asObject().get(fd.name())));
            if (givenChanged) {
                if (given.asArray().size() != embedding.dimension()) {
                    throw errorStmt(Messages.get("embedding_dimension_mismatch", embedding.name(),
                        given.asArray().size(), embedding.dimension()));
                }
                continue;
            }
            if (hasVector && !sourceChanged) {
                continue;
            }
            byEmbedding.computeIfAbsent(embedding, k -> new ArrayList<>()).add(fd);
            texts.put(fd, source.asString());
        }
        for (Map.Entry<Catalog.EmbeddingDef, List<Catalog.FieldDef>> entry : byEmbedding.entrySet()) {
            List<Catalog.FieldDef> group = entry.getValue();
            List<String> input = group.stream().map(texts::get).toList();
            List<double[]> vectors = ds.embeddings().embed(entry.getKey(), input,
                com.axonbase.core.embedding.EmbeddingClient.Role.DOCUMENT);
            for (int i = 0; i < group.size(); i++) {
                obj.put(group.get(i).name(), com.axonbase.core.embedding.EmbeddingClient.toValue(vectors.get(i)));
            }
        }
    }

    /**
     * {@code REEMBED TABLE t [FIELD f] [DRY RUN]}: regera os vetores. {@code DRY RUN} não chama o provedor: conta
     * linhas, caracteres e estima os tokens (cerca de um a cada 4 caracteres), para saber o custo antes.
     */
    private AxonValue runReembed(Statement.Reembed re) {
        Database db = db();
        Catalog.TableDef def = db.catalog().table(re.table());
        if (def == null) {
            throw errorStmt(Messages.get("stmt_table_undefined", re.table()));
        }
        List<Catalog.FieldDef> fields = def.fields().values().stream().filter(f -> f.embeddingName() != null)
            .filter(f -> re.field() == null || f.name().equals(re.field())).toList();
        if (fields.isEmpty()) {
            throw errorStmt(Messages.get("embedding_field_unknown", re.field() == null ? "*" : re.field(), re.table()));
        }
        long rows = 0;
        long characters = 0;
        long updated = 0;
        for (RecordId rid : resolveTargets(new Expr.Ident(re.table()))) {
            Document doc = loadDoc(db, rid);
            if (doc == null || !doc.current().isObject()) {
                continue;
            }
            Map<String, AxonValue> current = new LinkedHashMap<>(doc.current().asObject());
            boolean any = false;
            for (Catalog.FieldDef fd : fields) {
                AxonValue source = current.get(fd.embeddingFrom());
                if (source != null && source.isString() && !source.asString().isBlank()) {
                    characters += source.asString().length();
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            rows++;
            if (re.dryRun()) {
                continue;
            }
            for (Catalog.FieldDef fd : fields) {
                current.remove(fd.name());
            }
            autoEmbed(db, def, current, null);
            AxonValue next = AxonValue.object(current);
            records(db).put(rid.storageKey(db.ns(), db.db()), encode(next));
            indexSearch(db, rid, doc.current(), next);
            indexAdvanced(db, rid, doc.current(), next);
            indexColumnar(db, rid, doc.current(), next);
            updated++;
        }
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("rows", AxonValue.num(rows));
        if (re.dryRun()) {
            out.put("characters", AxonValue.num(characters));
            out.put("estimatedTokens", AxonValue.num((characters + 3) / 4));
        } else {
            out.put("updated", AxonValue.num(updated));
        }
        return AxonValue.object(out);
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
        authorizeUserScope(du.scope(), ns, database);
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
        ds.pruneUserControl(session, du.name());
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
        authorizeUserScope(da.scope(), ns, database);
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
        authorizeLink(ns);
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
        authorizeLink(ns);
        ds.dropDatabaseLink(ns, ddl.name());
        ds.removeControl(session, ns, "", ControlCommand.Kind.DATABASE_LINK, ddl.name());
        return AxonValue.nul();
    }

    // ------------------------------------------------------------------
    // SAGA (stubs — implementação nas fases 2-3)
    // ------------------------------------------------------------------

    /** O link vale para o namespace inteiro, então exige escopo que o cubra. */
    private void authorizeLink(String ns) {
        authorizeScope(Statement.AuthScope.NAMESPACE, ns, null, "auth_scope_forbidden");
    }

    /** A definição de saga é global ao servidor, então criar, substituir ou apagar exige escopo ROOT. */
    private void authorizeSagaDefinition() {
        authorizeScope(Statement.AuthScope.ROOT, null, null, "auth_scope_forbidden");
    }

    private AxonValue runCreateSaga(CreateSaga cs) {
        authorizeSagaDefinition();
        var def = new Datastore.SagaDef(cs.name(), cs.links());
        ds.defineSaga(cs.name(), def);
        ds.applyControl(session, ControlCommand.of(ControlCommand.Kind.SAGA, "", "", cs.name(),
            com.axonbase.parser.Render.stmt(cs)));
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
        Datastore.SagaDef definition = ds.saga(bs.name());
        String linkNamespace = session.namespace() == null ? "" : session.namespace();
        sagaLedger.beginSaga(bs.name(), bs.correlationId(), definition == null ? List.of() : definition.links(),
            linkNamespace);
        coordinateSagaParticipants(bs.name(), bs.correlationId(), "BEGIN", false, session.namespace());
        return AxonValue.object(Map.of(
            "saga", AxonValue.str(bs.name()),
            "correlation_id", AxonValue.str(bs.correlationId()),
            "status", AxonValue.str("RUNNING")
        ));
    }

    /**
     * A transação precisa estar ativa, ter sido aberta com este nome e ainda ter a definição que usou no BEGIN.
     *
     * @param resumable aceita também a transação parada em {@code COMPENSATING}, para o CANCEL terminar o serviço
     * @return o estado encontrado
     */
    private String requireSaga(String name, String correlationId, boolean resumable) {
        Map<String, AxonValue> record = sagaLedger.transaction(correlationId);
        AxonValue value = record == null ? null : record.get("status");
        String status = value != null && value.isString() ? value.asString() : "";
        if (!"RUNNING".equals(status) && !(resumable && "COMPENSATING".equals(status))) {
            throw errorStmt(Messages.get("stmt_saga_inactive", correlationId));
        }
        AxonValue opened = record.get("name");
        if (opened != null && opened.isString() && !opened.asString().equals(name)) {
            throw errorStmt(Messages.get("stmt_saga_other_name", name, correlationId, opened.asString()));
        }
        AxonValue links = record.get("links");
        // sem a definição, os participantes não seriam avisados e o comando informaria um sucesso falso
        if ("RUNNING".equals(status) && links != null && links.isArray() && !links.asArray().isEmpty()
            && ds.saga(name) == null) {
            throw errorStmt(Messages.get("stmt_saga_missing", name));
        }
        return status;
    }

    private AxonValue runCommitSaga(CommitSaga cs) {
        requireSaga(cs.name(), cs.correlationId(), false);
        coordinateSagaParticipants(cs.name(), cs.correlationId(), "COMMIT", false,
            linkNamespace(cs.correlationId()));
        sagaLedger.commitSaga(cs.name(), cs.correlationId());
        return AxonValue.object(Map.of("status", AxonValue.str("COMMITTED")));
    }

    private AxonValue runCancelSaga(CancelSaga cs) {
        // em COMPENSATING os participantes já responderam: falta só terminar a compensação local
        if ("RUNNING".equals(requireSaga(cs.name(), cs.correlationId(), true))) {
            coordinateSagaParticipants(cs.name(), cs.correlationId(), "CANCEL", true,
                linkNamespace(cs.correlationId()));
        }
        sagaLedger.cancelSaga(cs.name(), cs.correlationId());
        return AxonValue.object(Map.of("status", AxonValue.str("FAILED")));
    }

    private AxonValue runJoinSaga(JoinSaga js) {
        requireSaga(js.name(), js.correlationId(), false);
        session.sagaBegin(js.name(), js.correlationId());
        session.vars().set("saga_corr", AxonValue.str(js.correlationId()));
        return AxonValue.object(Map.of("status", AxonValue.str("RUNNING")));
    }

    private AxonValue runLeaveSaga() {
        session.sagaEnd();
        session.vars().set("saga_corr", AxonValue.nul());
        return AxonValue.object(Map.of("ok", AxonValue.str("1")));
    }

    /**
     * Namespace onde procurar as ligações dos participantes. A API de gestão executa em {@code system}: nesse caso
     * vale o namespace de quem abriu a SAGA. Nas demais sessões vale o da própria sessão.
     */
    private String linkNamespace(String correlationId) {
        if (!"system".equals(session.namespace())) {
            return session.namespace();
        }
        Map<String, AxonValue> record = sagaLedger.transaction(correlationId);
        AxonValue opened = record == null ? null : record.get("link_namespace");
        return opened != null && opened.isString() && !opened.asString().isEmpty()
            ? opened.asString() : session.namespace();
    }

    private void coordinateSagaParticipants(String sagaName, String correlationId, String action, boolean reverse,
                                            String linkNamespace) {
        Datastore.SagaDef definition = ds.saga(sagaName);
        if (definition == null) {
            return;
        }
        List<String> links = new ArrayList<>(definition.links());
        if (reverse) {
            java.util.Collections.reverse(links);
        }
        String quotedName = com.axonbase.parser.Render.sagaName(sagaName);
        String statement = action + " SAGA " + quotedName + " WITH CORRELATION '"
            + SagaLedger.esc(correlationId) + "'";
        for (String linkName : links) {
            Datastore.DatabaseLinkDef link = ds.databaseLink(linkNamespace, linkName);
            if (link == null) {
                throw errorStmt(Messages.get("stmt_saga_link_missing", linkName));
            }
            try {
                // Participant cancellation owns its local steps. Mirror them first as
                // reporting-only records so the orchestrator never compensates them again.
                if (!"BEGIN".equals(action)) {
                    List<AxonValue> transaction = linkClient.query(link,
                        "SHOW SAGA TRANSACTION " + quotedName + " '" + SagaLedger.esc(correlationId) + "'");
                    if (!transaction.isEmpty() && transaction.get(0).isObject()) {
                        AxonValue steps = transaction.get(0).asObject().get("steps");
                        if (steps != null && steps.isArray()) {
                            sagaLedger.reportParticipantSteps(correlationId, linkNamespace, linkName, steps.asArray());
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
        ds.removeDataRuleControl(session, db.ns(), db.db(), cd.name());
        // nome próprio no plano de controle: o nome puro é o da definição da tabela
        control(db, ControlCommand.Kind.TABLE, "datarule:" + cd.name(), com.axonbase.parser.Render.stmt(cd));
        return AxonValue.nul();
    }

    private AxonValue runDropDataRule(Statement.DropDataRule dr) {
        Database db = db();
        if (!db.catalog().removeDataRule(dr.name())) {
            throw errorStmt(Messages.get("stmt_data_rule_missing", dr.name()));
        }
        ds.removeDataRuleControl(session, db.ns(), db.db(), dr.name());
        return AxonValue.nul();
    }

    private AxonValue runRemoveTable(Statement.RemoveTable rt) {
        Database db = db();
        String table = rt.name();
        boolean dropped;
        // sem construção nem escrita no meio: nada pode regravar entradas GEO depois da limpeza
        java.util.concurrent.locks.Lock write = exclusiveIndexLock(db);
        write.lock();
        try {
            Catalog.TableDef removed = db.catalog().table(table);
            // tabela particionada sai de uma vez, por faixa, antes de o catálogo esquecer que ela é particionada
            dropped = removed != null && partitionRunner(db).dropTable(table);
            if (!db.catalog().removeTable(table)) {
                throw errorStmt(Messages.get("stmt_table_undefined", table));
            }
            if (removed != null) {
                for (Catalog.IndexDef index : removed.indexes().values()) {
                    if (index.geo()) {
                        purgeIndexEntries(db, table, index);
                        records(db).delete(buildMarkerKey(db, table, index.name()));
                    }
                }
            }
        } finally {
            write.unlock();
        }
        if (!dropped) {
            String prefix = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
            for (String key : records(db).keysWithPrefix(prefix)) {
                records(db).delete(key);
            }
        }
        // se a tabela era um tipo de aresta (RELATE), as arestas dele saem junto
        removeEdgesOfKind(db, table);
        records(db).delete(primaryKeyMarker(db, table));
        ds.removeTableControl(session, db.ns(), db.db(), table);
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

    // ------------------------------------------------------------------
    // New SQL-standard run methods
    // ------------------------------------------------------------------

    private AxonValue runAlterTable(Statement.AlterTable at) {
        Database db = db();
        Catalog.TableDef def = ensureTable(db, at.name());
        // no replay um ALTER gravado pela rc1 no lugar da tabela fala de colunas que já não estão no catálogo
        if (!ds.isReplaying()) {
            checkAlterColumns(def, at);
        }
        for (Statement.AlterOp op : at.operations()) {
            switch (op) {
                case Statement.AddColumn ac -> {
                    def.fields().put(ac.name(), new Catalog.FieldDef(ac.name(), ac.type(), false,
                        ac.checkExpr(), ac.defaultExpr(), ac.references())
                        .constrained(ac.notNull(), ac.primaryKey()));
                    forgetFieldDefinition(db, at.name(), ac.name());
                }
                case Statement.DropColumn dc -> {
                    def.fields().remove(dc.name());
                    forgetFieldDefinition(db, at.name(), dc.name());
                }
                case Statement.ModifyColumn mc -> {
                    Catalog.FieldDef existing = def.fields().get(mc.name());
                    def.fields().put(mc.name(), new Catalog.FieldDef(mc.name(), mc.type(),
                        existing != null && existing.readonly(),
                        mc.checkExpr() != null || existing == null ? mc.checkExpr() : existing.assertExpr(),
                        mc.defaultExpr() != null || existing == null ? mc.defaultExpr() : existing.defaultExpr(),
                        existing == null ? null : existing.references())
                        .constrained(mc.notNull() || existing != null && existing.notNull(),
                            existing != null && existing.primaryKey()));
                    forgetFieldDefinition(db, at.name(), mc.name());
                }
            }
        }
        persistTable(db, at.name());
        refreshPrimaryKey(db, at.name());
        return AxonValue.nul();
    }

    /** Confere o ALTER inteiro antes de mudar o catálogo: ou todas as operações valem, ou nenhuma. */
    private void checkAlterColumns(Catalog.TableDef def, Statement.AlterTable at) {
        java.util.Set<String> columns = new java.util.HashSet<>(def.fields().keySet());
        for (Statement.AlterOp op : at.operations()) {
            String missing = switch (op) {
                case Statement.AddColumn ac -> {
                    columns.add(ac.name());
                    yield null;
                }
                case Statement.DropColumn dc -> columns.remove(dc.name()) ? null : dc.name();
                case Statement.ModifyColumn mc -> columns.contains(mc.name()) ? null : mc.name();
            };
            if (missing != null) {
                throw errorStmt(Messages.get("stmt_field_missing", missing, at.name()));
            }
        }
    }

    private AxonValue runDropIndex(Statement.DropIndex di) {
        Database db = db();
        for (Catalog.TableDef def : db.catalog().tables()) {
            java.util.concurrent.locks.Lock write = db.indexLock().writeLock();
            write.lock();
            Catalog.IndexDef removed;
            try {
                removed = def.indexes().remove(di.name());
                if (removed != null) {
                    purgeIndexEntries(db, def.name(), removed);
                    records(db).delete(buildMarkerKey(db, def.name(), removed.name()));
                    bumpIndexEpoch(db);
                }
            } finally {
                write.unlock();
            }
            if (removed != null) {
                // o plano de controle só sabe substituir: a definição sai dele, senão o reinício a recriaria
                ds.removeControl(session, db.ns(), db.db(), ControlCommand.Kind.INDEX,
                    def.name() + ":" + removed.name());
                return AxonValue.nul();
            }
        }
        throw errorStmt(Messages.get("stmt_index_missing", di.name()));
    }

    private AxonValue runDropEvent(Statement.DropEvent de) {
        Database db = db();
        for (Catalog.TableDef def : db.catalog().tables()) {
            if (def.events().remove(de.name()) != null) {
                ds.removeControl(session, db.ns(), db.db(), ControlCommand.Kind.EVENT, def.name() + ":" + de.name());
                return AxonValue.nul();
            }
        }
        throw errorStmt(Messages.get("stmt_event_missing", de.name()));
    }

    private AxonValue runDropAnalyzer(Statement.DropAnalyzer da) {
        Database db = db();
        if (!db.catalog().removeAnalyzer(da.name())) {
            throw errorStmt(Messages.get("stmt_analyzer_missing", da.name()));
        }
        ds.removeControl(session, db.ns(), db.db(), ControlCommand.Kind.ANALYZER, da.name());
        return AxonValue.nul();
    }

    private AxonValue runDropUser(Statement.DropUser du) {
        String ns = session.namespace();
        String database = session.database();
        if (ds.isReplaying()) {
            replayDropUser(du.name(), ns, database);
            return AxonValue.nul();
        }
        db();
        AuthCatalog catalog = this.ds.authCatalog();
        // vale o escopo da identidade encontrada, que pode ser maior que o da sessão
        AuthCatalog.User target = catalog.user(du.name(), ns, database);
        if (target == null) {
            return AxonValue.nul();
        }
        authorizeScope(Statement.AuthScope.valueOf(target.scope().name()), target.namespace(), target.database(),
            "auth_scope_forbidden");
        if (catalog.isInitialRoot(target.name(), target.scope())) {
            if (com.axonbase.core.security.Authz.authenticated(session.auth())) {
                throw new com.axonbase.common.AxonError(-32002, Messages.get("auth_initial_root_protected"));
            }
            // modo embutido ou servidor sem autenticação: não remove e não falha
            return AxonValue.nul();
        }
        if (catalog.removeUser(du.name(), ns, database)) {
            ds.pruneUserControl(session, du.name());
        }
        return AxonValue.nul();
    }

    /**
     * Um {@code DROP USER} gravado por versão anterior. O gravado pela API para escopo de namespace ou root não tem
     * banco, e exigir um impedia o servidor de subir; sem banco, só o escopo exato é removido.
     */
    private void replayDropUser(String name, String ns, String database) {
        if (database != null) {
            ds.authCatalog().removeUser(name, ns, database);
        } else {
            ds.authCatalog().removeUserExact(name, ns == null
                ? com.axonbase.core.security.AuthCatalog.Scope.ROOT
                : com.axonbase.core.security.AuthCatalog.Scope.NAMESPACE, ns, null);
        }
        System.err.println(Messages.get("user_drop_legacy", name));
    }

    private AxonValue runRevokeAccess(Statement.RevokeAccess ra) {
        String ns = session.namespace();
        String database = session.database();
        AuthCatalog.Access target = this.ds.authCatalog().access(ra.name(), ns, database);
        if (target != null) {
            authorizeScope(Statement.AuthScope.valueOf(target.scope().name()), target.namespace(), target.database(),
                "auth_scope_forbidden");
            this.ds.authCatalog().removeAccess(ra.name(), ns, database);
        }
        return AxonValue.nul();
    }

    private AxonValue runDropSaga(Statement.DropSaga d) {
        authorizeSagaDefinition();
        if (ds.saga(d.name()) == null) {
            throw errorStmt(Messages.get("stmt_saga_missing", d.name()));
        }
        ds.dropSaga(d.name());
        ds.removeControl(session, "", "", ControlCommand.Kind.SAGA, d.name());
        return AxonValue.nul();
    }

    /** Coleta os predicados das Data Rules associadas ao usuário da sessão. */
    private List<Expr> dataRulePredicates() {
        AxonValue auth = session.auth();
        if (auth == null || !auth.isObject()) return List.of();
        Database db = db();
        List<Expr> predicates = new ArrayList<>();
        for (String name : dataRuleNames(auth)) {
            Catalog.DataRuleDef def = db.catalog().dataRule(name);
            // regra que não existe neste banco nega: liberar tornaria um nome errado em acesso total
            if (def == null) return DENY_ALL;
            if (lacksParameterValue(def.predicate(), name2 -> true)) return DENY_ALL;
            predicates.add(def.predicate());
        }
        return predicates;
    }

    /** Ligado enquanto o motor avalia regras e permissões: ali a travessia de um link lê o registro como ele é. */
    private final ThreadLocal<Boolean> evaluatingPolicy = new ThreadLocal<>();

    private AxonValue asPolicy(java.util.function.Supplier<AxonValue> work) {
        Boolean previous = evaluatingPolicy.get();
        evaluatingPolicy.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                evaluatingPolicy.remove();
            } else {
                evaluatingPolicy.set(previous);
            }
        }
    }

    /** Predicado que não deixa passar linha alguma. */
    private static final List<Expr> DENY_ALL = List.of(new Expr.Literal(AxonValue.bool(false)));

    /** Os parâmetros que trazem dados do token da sessão. */
    private static final java.util.function.Predicate<String> TOKEN_PARAMETERS =
        name -> "auth".equals(name) || "token".equals(name);

    /**
     * Algum {@code $parametro} conferido da regra (ou o campo pedido a ele, como {@code $auth.tenant}) está sem valor.
     * Comparar a falta com um campo ausente daria verdadeiro, então a condição inteira passa a negar.
     */
    private boolean lacksParameterValue(Expr e, java.util.function.Predicate<String> checked) {
        java.util.function.Predicate<Expr> lacks = part -> lacksParameterValue(part, checked);
        return switch (e) {
            case null -> false;
            case Expr.Param p -> !"parent".equals(p.name()) && checked.test(p.name()) && isMissing(eval(p));
            case Expr.Idiom i -> idiomLacksValue(i, checked);
            case Expr.Binary b -> lacks.test(b.left()) || lacks.test(b.right());
            case Expr.Unary u -> lacks.test(u.operand());
            case Expr.Cast c -> lacks.test(c.operand());
            case Expr.Alias a -> lacks.test(a.expr());
            case Expr.Call c -> c.args().stream().anyMatch(lacks);
            case Expr.ArrayLit a -> a.items().stream().anyMatch(lacks);
            case Expr.SetLit s -> s.items().stream().anyMatch(lacks);
            case Expr.ObjectLit o -> o.entries().stream().anyMatch(en -> lacks.test(en.value()));
            case Expr.RecordId r -> lacks.test(r.key());
            case Expr.Range r -> lacks.test(r.start()) || lacks.test(r.end());
            case Expr.SubQuery sq -> sq.query().statements().stream()
                .anyMatch(st -> st instanceof Statement.Select sel && lacks.test(sel.cond()));
            case Expr.Literal l -> false;
            case Expr.Ident id -> false;
            case Expr.Qualified q -> false;
            case Expr.Path p -> false;
            case Expr.Block b -> false;
        };
    }

    /** Confere o trecho {@code $parametro.campo.campo} de um idiom; o que vier depois é percorrido como expressão. */
    private boolean idiomLacksValue(Expr.Idiom i, java.util.function.Predicate<String> checked) {
        if (!(i.base() instanceof Expr.Param p)) {
            return lacksParameterValue(i.base(), checked);
        }
        if ("parent".equals(p.name()) || !checked.test(p.name())) {
            return false;
        }
        List<Expr.Part> fields = i.parts().stream().takeWhile(part -> part instanceof Expr.Part.Field).toList();
        Expr value = fields.isEmpty() ? p : new Expr.Idiom(p, fields);
        // como avaliação de política: se o parâmetro for um record id, a leitura não volta a aplicar as regras
        return isMissing(asPolicy(() -> eval(value)));
    }

    private static boolean isMissing(AxonValue v) {
        return v == null || v.isNone() || v.isNull();
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
            AxonValue value = asPolicy(() -> eval(entry.getValue()));
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

    /** O registro devolvido por uma escrita, com as máscaras do usuário. */
    private AxonValue visible(AxonValue record) {
        return masksColumns() ? applyDataRuleMask(record) : record;
    }

    private Document masked(Document d) {
        return new Document(d.id(), applyDataRuleMask(d.current()));
    }

    /** CREATE e UPSERT gravam sobre a chave: um registro que a regra esconde não pode ser tocado. */
    private void refuseHiddenRecord(Database db, RecordId rid) {
        if (!com.axonbase.core.security.Authz.hasDataRules(session.auth())) {
            return;
        }
        Document existing = loadDoc(db, rid);
        if (existing != null && !dataRuleFilter(existing)) {
            throw errorStmt("permission denied");
        }
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
        docs.removeIf(doc -> !truthy(asPolicy(() -> evalCond(combined, doc))));
    }

    /** Retorna true se o documento passa pelo filtro das Data Rules. */
    private boolean dataRuleFilter(Document doc) {
        List<Expr> predicates = dataRulePredicates();
        if (predicates.isEmpty()) return true;
        Expr combined = predicates.size() == 1 ? predicates.get(0)
            : Expr.and(predicates);
        return truthy(asPolicy(() -> evalOnDocument(combined, doc)));
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

    /**
     * Alvos de UPDATE/DELETE. Em tabela inteira com {@code WHERE campo = literal}, usa os
     * mesmos índices (UNIQUE e columnar) que o SELECT em vez de listar todas as chaves.
     * O WHERE continua sendo reavaliado sobre cada documento pelo chamador.
     */
    private List<RecordId> resolveMutationTargets(Database db, Expr target, Expr cond) {
        if (cond != null && target instanceof Expr.Ident) {
            Statement.Select probe = new Statement.Select(List.of(), false, false, target, cond,
                List.of(), List.of(), null, null, List.of(), List.of(), null);
            List<Document> indexed = uniqueLookupSourceDocs(db, probe,
                () -> columnarSourceDocs(db, probe, () -> null));
            if (indexed != null) {
                List<RecordId> out = new ArrayList<>(indexed.size());
                for (Document doc : indexed) {
                    out.add(doc.id());
                }
                return out;
            }
        }
        return resolveTargets(target);
    }

    private List<RecordId> resolveTargets(Expr target) {
        Database db = db();
        if (target instanceof Expr.RecordId rid) {
            return List.of(configRecordId(rid));
        }
        // não registra a tabela: um DELETE de um nome errado não pode criá-la
        if (target instanceof Expr.Ident ident) {
            String table = ident.name();
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
            AxonValue v = parameter(p.name());
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
                AxonValue v = parameter(p.name());
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

    /** Resolvedor único de parâmetros, ou {@code null} quando não há valor. */
    private AxonValue parameter(String name) {
        Session source = identitySession != null && Session.isIdentityVariable(name) ? identitySession : session;
        return source.parameter(name);
    }

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
            case Expr.Param p -> {
                AxonValue v = parameter(p.name());
                yield v == null ? AxonValue.none() : v;
            }
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
        // ->aresta.campo chega como idiom dentro de idiom: achatado, o passo de grafo fica ao lado do campo
        List<Expr.Part> parts = new ArrayList<>(i.parts());
        Expr root = i.base();
        while (root instanceof Expr.Idiom nested) {
            parts.addAll(0, nested.parts());
            root = nested.base();
        }
        AxonValue base = idiomBase(root, doc);
        for (int at = 0; at < parts.size(); at++) {
            AxonValue from = base;
            base = applyPart(base, parts.get(at));
            if (at + 1 < parts.size() && parts.get(at) instanceof Expr.Part.Graph graph
                && graph.lookup() instanceof Expr.Ident edge
                && parts.get(at + 1) instanceof Expr.Part.Field field) {
                // ->aresta.campo: vale o campo do registro alcançado; sem ele, a propriedade da aresta
                AxonValue read = fieldAccess(base, field.name());
                boolean missing = read.isNull() || read.isNone() || read.isArray() && read.asArray().isEmpty();
                base = missing ? edgeProperty(from, graph.direction(), edge.name(), field.name(), read) : read;
                at++;
            }
        }
        return base;
    }

    /** A propriedade {@code name} das arestas {@code kind} que saem (ou chegam) nos registros de {@code from}. */
    private AxonValue edgeProperty(AxonValue from, Expr.Direction direction, String kind, String name,
                                   AxonValue absent) {
        Database db = db();
        List<String> dirs = direction == Expr.Direction.BOTH
            ? List.of("out", "in")
            : List.of(direction == Expr.Direction.IN ? "in" : "out");
        List<AxonValue> sources = from.isArray() ? from.asArray() : List.of(from);
        List<AxonValue> found = new ArrayList<>();
        for (AxonValue source : sources) {
            AxonValue id = source.isObject() ? source.asObject().get("id") : source;
            if (id == null || !id.isRecordId()) {
                continue;
            }
            for (String dir : dirs) {
                String prefix = edgePrefix(db, dir, id.asRecordId()) + kind + "|";
                for (String key : records(db).keysWithPrefix(prefix)) {
                    AxonValue value = edgeProperties(db, key).get(name);
                    if (value != null && !value.isNull() && !value.isNone()) {
                        found.add(value);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            return absent;
        }
        return found.size() == 1 ? found.get(0) : AxonValue.array(found);
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
            case Expr.Part.Field f -> fieldAccess(v, f.name());
            case Expr.Part.Index ix -> {
                int idx = (int) eval(ix.index()).asLong();
                yield v.isArray() && idx >= 0 && idx < v.asArray().size() ? v.asArray().get(idx) : AxonValue.nul();
            }
            case Expr.Part.Graph g -> graphTraverse(v, g);
            default -> v;
        };
    }

    /**
     * {@code valor.campo}: campo de um objeto; de um registro referenciado (record id, lendo o registro); ou, numa
     * lista, o campo de cada item (o que dá {@code ->comprou->produto.nome}). Itens sem o campo ficam de fora.
     */
    private AxonValue fieldAccess(AxonValue v, String name) {
        if (v.isObject()) {
            return v.asObject().getOrDefault(name, AxonValue.nul());
        }
        if (v.isRecordId()) {
            AxonValue record = fetchOne(v.asRecordId());
            return record == null ? AxonValue.nul() : fieldAccess(record, name);
        }
        if (v.isArray()) {
            List<AxonValue> out = new ArrayList<>();
            for (AxonValue item : v.asArray()) {
                AxonValue value = fieldAccess(item, name);
                if (!value.isNull() && !value.isNone()) {
                    out.add(value);
                }
            }
            return AxonValue.array(out);
        }
        return AxonValue.nul();
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
        if (f instanceof Expr.SubQuery || f instanceof Expr.Call || f instanceof Expr.Binary
            || f instanceof Expr.ObjectLit || f instanceof Expr.ArrayLit) {
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
        // Literais montados dentro de uma linha enxergam os campos dela: {type: 'Point', coordinates: [lon, lat]}
        // usa as colunas lon e lat do registro. Sem isso os nomes eram lidos como tabelas.
        if (e instanceof Expr.ObjectLit o) {
            Map<String, AxonValue> m = new LinkedHashMap<>();
            for (Expr.ObjectLit.Entry entry : o.entries()) {
                m.put(entry.key(), evalExprInDoc(entry.value(), doc));
            }
            return AxonValue.object(m);
        }
        if (e instanceof Expr.ArrayLit a) {
            List<AxonValue> list = new ArrayList<>();
            for (Expr item : a.items()) {
                list.add(evalExprInDoc(item, doc));
            }
            return AxonValue.array(list);
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
            case MUL -> Arith.mul(l, r);
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
        if (haystack.isRecordId()) {
            // resultado único de uma travessia chega como valor, não como lista
            return equal(haystack, needle);
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
        if (arrayVector(text) && arrayVector(query)) {
            // campo_vetorial @@ $consulta é um predicado de vizinhança: a ordem e o corte (ORDER BY ... LIMIT) vêm do
            // índice HNSW ou da distância, então a condição em si deixa passar todo vetor
            return true;
        }
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
        // o id da linha chega como texto ("pessoa:ana") e o literal como id de registro: são o mesmo registro
        if (a.isRecordId() && b.isString()) {
            return a.asRecordId().toString().equals(b.asString());
        }
        if (a.isString() && b.isRecordId()) {
            return b.asRecordId().toString().equals(a.asString());
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
            return Arith.concat(l, r);
        }
        return Arith.add(l, r);
    }

    private AxonValue sub(AxonValue l, AxonValue r) {
        if (l.isDatetime() && r.isDuration()) {
            return AxonValue.datetime(l.asInstant().minusMillis(r.asDuration().millis()));
        }
        return Arith.sub(l, r);
    }

    private AxonValue divide(AxonValue l, AxonValue r) {
        return Arith.div(l, r);
    }

    private AxonValue mod(AxonValue l, AxonValue r) {
        return Arith.mod(l, r);
    }

    private AxonValue pow(AxonValue l, AxonValue r) {
        return Arith.pow(l, r);
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
            case NEG -> Arith.neg(operand);
            case POS -> operand;
        };
    }

    private AxonValue evalUnaryDoc(Expr.Unary u, Document doc) {
        AxonValue operand = evalExprInDoc(u.operand(), doc);
        return switch (u.op()) {
            case NOT -> AxonValue.bool(!truthy(operand));
            case NEG -> Arith.neg(operand);
            case POS -> operand;
        };
    }

    private AxonValue evalCast(Expr.Cast c) {
        AxonValue v = eval(c.operand());
        return switch (c.type().toLowerCase()) {
            case "int" -> Functions.call("type::int", List.of(v));
            case "float", "number" -> AxonValue.num(v.asDouble());
            case "decimal" -> Functions.call("type::decimal", List.of(v));
            case "string" -> v.isNull() || v.isNone() ? v : AxonValue.str(Functions.text(v));
            case "bool" -> Functions.call("type::bool", List.of(v));
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
        if (name.equals("vector::embed") || name.equals("vector::fromstr")) {
            return invokeEmbed(args);
        }
        AxonValue result = Functions.call(name, args);
        if (result != null) {
            return result;
        }
        AxonValue user = ds.programs().tryCall(session, c.name(), args);
        if (user != null) {
            return user;
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
     * Funções de grafo: {@code graph::connected}, {@code graph::path} e {@code graph::trail}, todas com
     * {@code (origem, destino [, tipo_de_aresta, saltos_maximos, sentido])}. O sentido é {@code 'out'}
     * (padrão, segue as setas), {@code 'in'} (contra as setas) ou {@code 'both'}.
     * {@code connected} devolve verdadeiro/falso, {@code path} a lista de ids do menor caminho e
     * {@code trail} uma linha por nó do caminho, pronta para a aba Graph do Workspace.
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
        List<String> dirs = graphDirections(args.size() > 4 ? args.get(4) : null);

        return switch (name.substring(7)) {
            case "connected" -> AxonValue.bool(!findHops(db, from, to, edge, maxDepth, dirs).isEmpty());
            case "path" -> {
                List<AxonValue> ids = new ArrayList<>();
                for (Hop h : findHops(db, from, to, edge, maxDepth, dirs)) {
                    ids.add(AxonValue.record(h.node().table(), h.node().key()));
                }
                yield AxonValue.array(ids);
            }
            case "trail" -> graphTrail(db, findHops(db, from, to, edge, maxDepth, dirs));
            default -> throw AxonError.internal(Messages.get("stmt_graph_function_unknown", name));
        };
    }

    /** Sentido da busca: out (padrão), in ou both. */
    private static List<String> graphDirections(AxonValue arg) {
        String d = arg != null && arg.isString() ? arg.asString().trim().toLowerCase() : "out";
        return switch (d) {
            case "in" -> List.of("in");
            case "both" -> List.of("out", "in");
            default -> List.of("out");
        };
    }

    /** Um passo do caminho: o nó alcançado, o tipo da aresta usada e se ela foi percorrida a favor da seta. */
    private record Hop(AxonValue.RecordId node, String kind, boolean forward) { }

    /** Vértice vizinho com o tipo da aresta que o liga. */
    private record GraphLink(AxonValue.RecordId node, String kind, boolean forward) { }

    private List<GraphLink> graphLinks(Database db, AxonValue.RecordId from, List<String> dirs, String want) {
        List<GraphLink> out = new ArrayList<>();
        for (String dir : dirs) {
            String prefix = edgePrefix(db, dir, from);
            for (String k : records(db).keysWithPrefix(prefix)) {
                String[] parts = k.substring(prefix.length()).split(java.util.regex.Pattern.quote("|"));
                if (parts.length < 3) {
                    continue;
                }
                if (!want.equals("*") && !want.equals(parts[0])) {
                    continue;
                }
                out.add(new GraphLink(new AxonValue.RecordId(parts[1], parseKeyVal(parts[2])), parts[0], dir.equals("out")));
            }
        }
        return out;
    }

    /**
     * Menor caminho entre dois registros (busca em largura), com no máximo {@code maxDepth} arestas.
     * O primeiro passo é a origem (sem aresta). Lista vazia quando não há caminho.
     */
    private List<Hop> findHops(Database db, AxonValue.RecordId from, AxonValue.RecordId to,
                               String edge, int maxDepth, List<String> dirs) {
        if (from.equals(to)) {
            return List.of(new Hop(from, null, true));
        }
        Map<AxonValue.RecordId, Hop> arrived = new java.util.HashMap<>();
        Map<AxonValue.RecordId, AxonValue.RecordId> parent = new java.util.HashMap<>();
        java.util.ArrayDeque<AxonValue.RecordId> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        queue.addLast(from);
        depths.addLast(0);
        arrived.put(from, new Hop(from, null, true));
        while (!queue.isEmpty()) {
            AxonValue.RecordId current = queue.removeFirst();
            int depth = depths.removeFirst();
            if (depth >= maxDepth) {
                continue;
            }
            for (GraphLink n : graphLinks(db, current, dirs, edge)) {
                if (arrived.containsKey(n.node())) {
                    continue;
                }
                arrived.put(n.node(), new Hop(n.node(), n.kind(), n.forward()));
                parent.put(n.node(), current);
                if (n.node().equals(to)) {
                    java.util.LinkedList<Hop> path = new java.util.LinkedList<>();
                    for (AxonValue.RecordId at = to; at != null; at = parent.get(at)) {
                        path.addFirst(arrived.get(at));
                    }
                    return new ArrayList<>(path);
                }
                queue.addLast(n.node());
                depths.addLast(depth + 1);
            }
        }
        return List.of();
    }

    /**
     * Uma linha por nó do caminho (campos do registro) e, em cada linha, uma coluna com o nome do tipo de
     * aresta apontando para o nó seguinte. Quando a aresta foi percorrida contra a seta, a coluna fica na linha
     * do nó seguinte e aponta para o anterior, para que a aba Graph desenhe o sentido real.
     */
    private AxonValue graphTrail(Database db, List<Hop> hops) {
        List<Map<String, AxonValue>> rows = new ArrayList<>();
        for (Hop h : hops) {
            Map<String, AxonValue> row = new LinkedHashMap<>();
            Document doc = loadDoc(db, new RecordId(h.node().table(), h.node().key() instanceof AxonValue kv ? kv : keyOf(h.node().key())));
            if (doc != null && doc.current() != null && doc.current().isObject()) {
                row.putAll(doc.current().asObject());
            }
            row.put("id", AxonValue.record(h.node().table(), h.node().key()));
            rows.add(row);
        }
        for (int i = 1; i < hops.size(); i++) {
            Hop h = hops.get(i);
            int owner = h.forward() ? i - 1 : i;
            AxonValue target = AxonValue.record(
                hops.get(h.forward() ? i : i - 1).node().table(), hops.get(h.forward() ? i : i - 1).node().key());
            Map<String, AxonValue> row = rows.get(owner);
            AxonValue existing = row.get(h.kind());
            if (existing == null || !(existing.isRecordId() || existing.isArray())) {
                row.put(h.kind(), target);
            } else {
                List<AxonValue> both = new ArrayList<>();
                if (existing.isArray()) {
                    both.addAll(existing.asArray());
                } else {
                    both.add(existing);
                }
                both.add(target);
                row.put(h.kind(), AxonValue.array(both));
            }
        }
        List<AxonValue> out = new ArrayList<>();
        for (Map<String, AxonValue> r : rows) {
            out.add(AxonValue.object(r));
        }
        return AxonValue.array(out);
    }

    /** Envolve cada termo da busca em tags HTML (HyperText Markup Language) configuráveis. */
    private AxonValue highlight(List<AxonValue> args, List<String> terms) {
        if (args.isEmpty() || !args.get(0).isString()) {
            return AxonValue.nul();
        }
        String start = args.size() > 1 && args.get(1).isString() ? args.get(1).asString() : "<em>";
        String end = args.size() > 2 && args.get(2).isString() ? args.get(2).asString() : "</em>";
        String text = args.get(0).asString();
        List<String> usable = terms.stream().filter(t -> !t.isBlank())
            .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        if (usable.isEmpty()) {
            return AxonValue.str(text);
        }
        // os termos da consulta são radicais (stemming): marca a palavra inteira que contém o radical
        String alternatives = usable.stream().map(java.util.regex.Pattern::quote)
            .collect(java.util.stream.Collectors.joining("|"));
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?iu)[\\p{L}\\p{N}_]*(?:" + alternatives + ")[\\p{L}\\p{N}_]*").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(start + m.group() + end));
        }
        m.appendTail(out);
        return AxonValue.str(out.toString());
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
        if (!isCacheableSubQuery(sq)) {
            return runSubQuery(sq, outer);
        }
        AxonValue cached = subqueryCache.get(sq);
        if (cached == null) {
            cached = runSubQuery(sq, outer);
            subqueryCache.put(sq, cached);
        }
        return cached;
    }

    /** Resultado das subqueries independentes da linha, válido até o próximo statement deste executor. */
    private final Map<Expr.SubQuery, AxonValue> subqueryCache = new java.util.IdentityHashMap<>();
    private final Map<Expr.SubQuery, Boolean> subqueryCacheable = new java.util.IdentityHashMap<>();

    /**
     * Subquery que não depende da linha de fora e não muda entre uma avaliação e outra: o mesmo texto serve para
     * todas as linhas, então o resultado é guardado. Sem {@code $} (nem {@code $parent}, nem variáveis que um
     * evento ou bloco possa trocar) e sem funções que variam (rand, now), e só leitura.
     */
    private boolean isCacheableSubQuery(Expr.SubQuery sq) {
        return subqueryCacheable.computeIfAbsent(sq, q -> {
            String text = AxonQl.render(q.query());
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            return lower.startsWith("select") && text.indexOf('$') < 0
                && !lower.contains("rand") && !lower.contains("now");
        });
    }

    private AxonValue runSubQuery(Expr.SubQuery sq, Document outer) {
        AxonValue previousParent = session.vars().get("parent");
        if (outer != null) {
            session.vars().set("parent", outer.current());
        }
        try {
            Executor sub = new Executor(ds);
            sub.identitySession = identitySession;
            // as variáveis já estão na sessão: nada a repassar
            return sub.execute(sq.query(), session, null);
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

    private Document docOf(String table, String prefix, RawRecord raw) {
        if (raw.value().length == 0) {
            return null;
        }
        return new Document(new RecordId(table, parseKey(raw.key().substring(prefix.length()))), decode(raw.value()));
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

    // ------------------------------------------------------------------
    // Restrições de tabela: NOT NULL, PRIMARY KEY e CHECK
    // ------------------------------------------------------------------

    /**
     * NOT NULL e CHECK de tabela valem nas escritas novas; o que já estava gravado não é conferido, e quem repõe dados
     * (importação, saga, movimentação de shard) não passa por aqui. Como o CHECK de coluna, o de tabela não é avaliado
     * quando um campo citado está ausente.
     */
    private void checkConstraints(Database db, RecordId rid, Map<String, AxonValue> record) {
        Catalog.TableDef def = db.catalog().table(rid.table());
        if (def == null || session.restoring()) {
            return;
        }
        for (Catalog.FieldDef field : def.fields().values()) {
            if (!field.notNull() && !field.primaryKey()) {
                continue;
            }
            AxonValue value = record.get(field.name());
            if (value == null || value.isNull() || value.isNone()) {
                throw errorStmt(Messages.get("stmt_not_null_violation", field.name(), rid.table()));
            }
        }
        for (Expr check : def.checks()) {
            java.util.Set<String> cited = new java.util.HashSet<>();
            citedFields(check, cited);
            // a restrição é do esquema: avaliada como política, lê um registro ligado como ele é, sem a regra de quem grava
            if (record.keySet().containsAll(cited)
                && !truthy(asPolicy(() -> evalExprInDoc(check, new Document(rid, AxonValue.object(record)))))) {
                throw errorStmt(Messages.get("stmt_table_check_violation", rid.table(),
                    com.axonbase.parser.Render.expr(check)));
            }
        }
    }

    private static void citedFields(Expr expr, java.util.Set<String> out) {
        switch (expr) {
            case Expr.Ident ident -> out.add(ident.name());
            case Expr.Binary binary -> {
                citedFields(binary.left(), out);
                citedFields(binary.right(), out);
            }
            case Expr.Unary unary -> citedFields(unary.operand(), out);
            case Expr.Cast cast -> citedFields(cast.operand(), out);
            case Expr.Call call -> call.args().forEach(argument -> citedFields(argument, out));
            case Expr.ArrayLit array -> array.items().forEach(item -> citedFields(item, out));
            case Expr.Idiom idiom -> citedFields(idiom.base(), out);
            default -> { }
        }
    }

    /**
     * Mantém as entradas da chave primária ({@code !uidx\0!pk\0colunas\0valores\0chave}) e recusa um valor que outro
     * registro já tem. Uma escrita que não muda o valor passa: dados antigos repetidos continuam regraváveis.
     */
    private void enforcePrimaryKey(Database db, RecordId rid, AxonValue before, AxonValue after) {
        Catalog.TableDef def = db.catalog().table(rid.table());
        List<String> columns = def == null ? List.of() : def.primaryKey();
        if (columns.isEmpty()) {
            return;
        }
        String previous = primaryKeySlot(db, rid.table(), columns, before);
        String next = primaryKeySlot(db, rid.table(), columns, after);
        if (next != null && next.equals(previous)) {
            return;
        }
        String own = rid.key().keyString();
        // a marca disputada faz duas escritas simultâneas do mesmo valor conflitarem; numa tabela particionada a
        // entrada mora na partição da linha e a marca não existe
        boolean contended = db.catalog().partitioning(rid.table()) == null;
        if (next != null) {
            if (contended && rawRecords(db) instanceof com.axonbase.core.storage.StatementBatch pending) {
                pending.expect(next);
            }
            if (!session.restoring() && records(db).keysWithPrefix(next).stream()
                .anyMatch(key -> !key.equals(next) && !key.equals(next + own))) {
                throw errorStmt(Messages.get("stmt_primary_key_violation", rid.table(), String.join(", ", columns)));
            }
        }
        if (previous != null) {
            records(db).delete(previous + own);
            if (contended && records(db).keysWithPrefix(previous).stream().allMatch(previous::equals)) {
                records(db).delete(previous);
            }
        }
        if (next != null) {
            if (contended) {
                records(db).put(next, new byte[0]);
            }
            records(db).put(next + own, new byte[0]);
        }
    }

    private static String primaryKeyPrefix(Database db, String table) {
        return db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000!uidx\u0000!pk\u0000";
    }

    private static String primaryKeyMarker(Database db, String table) {
        return "PKB|" + db.ns() + "|" + db.db() + "|" + table;
    }

    /** O prefixo das entradas de um valor, ou {@code null} quando o registro não tem a chave inteira. */
    private static String primaryKeySlot(Database db, String table, List<String> columns, AxonValue record) {
        if (record == null || !record.isObject()) {
            return null;
        }
        List<AxonValue> values = new ArrayList<>();
        for (String column : columns) {
            AxonValue value = record.asObject().get(column);
            if (value == null || value.isNull() || value.isNone()) {
                return null;
            }
            // 1 e 1.0 são o mesmo valor; 1 e '1' não
            values.add(AxonValue.str(value.keyString()));
        }
        return primaryKeyPrefix(db, table) + AxonJson.write(AxonValue.array(columns.stream().map(AxonValue::str)
            .toList())) + "\u0000" + AxonJson.write(AxonValue.array(values)) + "\u0000";
    }

    /** Depois de um DDL, ou ao abrir uma base de versão anterior: refaz as entradas se a chave primária mudou. */
    private void refreshPrimaryKey(Database db, String table) {
        if (ds.isReplaying()) {
            return;
        }
        java.util.concurrent.locks.Lock write = exclusiveIndexLock(db);
        write.lock();
        try {
            rebuildPrimaryKey(db, table);
        } finally {
            write.unlock();
        }
    }

    /** Grava as entradas das linhas que já existem, sem conferir se elas se repetem. */
    void rebuildPrimaryKey(Database db, String table) {
        Catalog.TableDef def = db.catalog().table(table);
        List<String> columns = def == null ? List.of() : def.primaryKey();
        String marker = primaryKeyMarker(db, table);
        String wanted = String.join(",", columns);
        String built = records(db).get(marker)
            .map(raw -> new String(raw, java.nio.charset.StandardCharsets.UTF_8)).orElse("");
        if (built.equals(wanted)) {
            return;
        }
        deleteAll(db, primaryKeyPrefix(db, table));
        if (columns.isEmpty()) {
            records(db).delete(marker);
        } else {
            boolean contended = db.catalog().partitioning(table) == null;
            for (RecordId rid : resolveTargets(new Expr.Ident(table))) {
                Document doc = loadDoc(db, rid);
                String slot = doc == null ? null : primaryKeySlot(db, table, columns, doc.current());
                if (slot != null) {
                    if (contended) {
                        records(db).put(slot, new byte[0]);
                    }
                    records(db).put(slot + rid.key().keyString(), new byte[0]);
                }
            }
            records(db).put(marker, wanted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        // uma transação aberta antes disso montou as escritas sem estas entradas
        bumpIndexEpoch(db);
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
                String ownEntry = nextPrefix + rid.key().keyString();
                var existing = records(db).keysWithPrefix(nextPrefix);
                if (existing.stream().anyMatch(key -> !key.equals(ownEntry))) {
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
            if (!index.search() || (onlyIndex != null && index != onlyIndex)) {
                continue;
            }
            Catalog.AnalyzerDef analyzer = db.catalog().analyzer(index.searchAnalyzer());
            if (analyzer == null) {
                continue;
            }
            // em construção, uma linha que a varredura ainda não indexou não tem o que retirar
            if (before != null && (index.ready() || records(db).get(docLenKey(db, rid.table(), index.name(), rid)).isPresent())) {
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
            if ((!index.geo() && !index.vector()) || (onlyIndex != null && index != onlyIndex)) {
                continue;
            }
            if (index.geo()) {
                AxonValue geometry = after != null && after.isObject() && !index.columns().isEmpty()
                    ? after.asObject().get(index.columns().get(0)) : null;
                geoIndex(db, rid.table(), index).update(records(db), rid.key().keyString(), geometry);
                continue;
            }
            String vectorKey = "VX|" + db.ns() + "|" + db.db() + "|" + rid.table() + "|"
                + index.name() + "|" + rid.key().keyString();
            records(db).delete(vectorKey);
            unlinkHnsw(db, rid, index);
            if (after == null || !after.isObject() || index.columns().isEmpty()) {
                continue;
            }
            AxonValue value = after.asObject().get(index.columns().get(0));
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
            if (!index.columnar() || (onlyIndex != null && index != onlyIndex)) continue;
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
                    records(db).put(newKey, com.axonbase.core.storage.ColumnarIndex.entryValue(
                        rid.key().keyString(), newVal));
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

    // ---- agregação só pelo índice columnar ----

    /** Colunas citadas por uma expressão, ou {@code false} se há algo que não dá para analisar com segurança. */
    private static boolean columnRefs(Expr e, java.util.Set<String> out) {
        return switch (e) {
            case null -> true;
            case Expr.Literal l -> true;
            case Expr.Param p -> true;
            case Expr.Ident i -> out.add(i.name()) || true;
            case Expr.Alias a -> columnRefs(a.expr(), out);
            case Expr.Unary u -> columnRefs(u.operand(), out);
            case Expr.Cast c -> columnRefs(c.operand(), out);
            case Expr.Binary b -> columnRefs(b.left(), out) && columnRefs(b.right(), out);
            case Expr.Call c -> c.args().stream().allMatch(a -> columnRefs(a, out));
            case Expr.ArrayLit a -> a.items().stream().allMatch(x -> columnRefs(x, out));
            case Expr.ObjectLit o -> o.entries().stream().allMatch(en -> columnRefs(en.value(), out));
            default -> false;
        };
    }

    /**
     * Índices COLUMNAR que cobrem todas as colunas de um SELECT de agregação, na ordem das colunas, ou
     * {@code null} se a consulta não pode ser respondida só pelo índice: precisa ser agregação sobre uma tabela
     * simples, sem JOIN, FETCH, viagem no tempo, permissões nem Data Rules, citar apenas colunas com índice
     * columnar já no formato com valor e não usar {@code id} ou {@code *}.
     */
    private List<Catalog.IndexDef> columnarCoveringIndexes(Database db, Statement.Select sel) {
        if (!(sel.from() instanceof Expr.Ident t) || !sel.joins().isEmpty() || !sel.fetch().isEmpty()
            || sel.timeTravel() != null || !isStreamableAggregate(sel)) {
            return null;
        }
        Catalog.TableDef def = db.catalog().table(t.name());
        if (def == null || (def.permissions() != null && def.permissions().select() != null)
            || !dataRulePredicates().isEmpty()) {
            return null;
        }
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        boolean ok = sel.fields().stream().allMatch(f -> columnRefs(f, refs))
            && columnRefs(sel.cond(), refs) && columnRefs(sel.having(), refs)
            && sel.group().stream().allMatch(g -> columnRefs(g, refs))
            && sel.orders().stream().allMatch(o -> columnRefs(o.field(), refs));
        if (!ok || refs.contains("id") || refs.contains("*")) {
            return null;
        }
        List<Catalog.IndexDef> columnar = def.indexes().values().stream().filter(Catalog.IndexDef::ready).filter(Catalog.IndexDef::columnar)
            .filter(i -> records(db).get(com.axonbase.core.storage.ColumnarIndex.readyKey(
                db.ns(), db.db(), t.name(), i.name())).isPresent()).toList();
        if (columnar.isEmpty()) {
            return null;
        }
        if (refs.isEmpty()) {
            // count() puro: qualquer coluna indexada tem uma entrada por linha
            return List.of(columnar.get(0));
        }
        List<Catalog.IndexDef> out = new ArrayList<>();
        for (String column : refs) {
            Catalog.IndexDef ix = columnar.stream().filter(i -> i.columns().contains(column)).findFirst().orElse(null);
            if (ix == null) {
                return null;
            }
            out.add(ix);
        }
        return out;
    }

    /**
     * Agrega só com as entradas do índice columnar (sem carregar documentos). Cada linha vira um documento
     * sintético com as colunas indexadas citadas; WHERE, GROUP BY, HAVING e agregados seguem as regras de sempre.
     * {@code null} quando a consulta não é coberta pelo índice.
     */
    private GroupAggregation columnarCoveredAggregate(Database db, Statement.Select sel) {
        List<Catalog.IndexDef> indexes = columnarCoveringIndexes(db, sel);
        if (indexes == null) {
            return null;
        }
        String table = ((Expr.Ident) sel.from()).name();
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        sel.fields().forEach(f -> columnRefs(f, refs));
        columnRefs(sel.cond(), refs);
        columnRefs(sel.having(), refs);
        sel.group().forEach(g -> columnRefs(g, refs));
        sel.orders().forEach(o -> columnRefs(o.field(), refs));
        List<String> columns = refs.isEmpty() ? List.of(indexes.get(0).columns().get(0)) : new ArrayList<>(refs);
        GroupAggregation aggregation = new GroupAggregation(sel, groupRowEval(), this::fieldName);
        boolean[] complete = {true};
        if (columns.size() == 1) {
            // uma coluna só: cada entrada já é uma linha, sem juntar por chave
            String column = columns.get(0);
            String prefix = com.axonbase.core.storage.ColumnarIndex.columnPrefix(
                db.ns(), db.db(), table, indexes.get(0).name(), column);
            records(db).scanEntries(prefix, (key, bytes) -> {
                com.axonbase.core.storage.ColumnarIndex.Entry entry =
                    com.axonbase.core.storage.ColumnarIndex.decodeEntry(bytes);
                if (entry == null) {
                    complete[0] = false;
                    return false;
                }
                acceptCovered(aggregation, sel, table, entry.rowKey(), Map.of(column, entry.value()));
                return true;
            });
            return complete[0] ? aggregation : null;
        }
        Map<String, Map<String, AxonValue>> rows = new LinkedHashMap<>();
        for (int c = 0; c < columns.size() && complete[0]; c++) {
            String column = columns.get(c);
            String prefix = com.axonbase.core.storage.ColumnarIndex.columnPrefix(
                db.ns(), db.db(), table, indexes.get(c).name(), column);
            int[] seen = {0};
            records(db).scanEntries(prefix, (key, bytes) -> {
                com.axonbase.core.storage.ColumnarIndex.Entry entry =
                    com.axonbase.core.storage.ColumnarIndex.decodeEntry(bytes);
                if (entry == null) {
                    complete[0] = false;
                    return false;
                }
                rows.computeIfAbsent(entry.rowKey(), k -> new LinkedHashMap<>()).put(column, entry.value());
                seen[0]++;
                return true;
            });
            if (complete[0] && c > 0 && seen[0] != rows.size()) {
                complete[0] = false;
            }
        }
        if (!complete[0]) {
            return null;
        }
        for (Map.Entry<String, Map<String, AxonValue>> row : rows.entrySet()) {
            acceptCovered(aggregation, sel, table, row.getKey(), row.getValue());
        }
        return aggregation;
    }

    /** Soma uma linha reconstruída do índice no agregador, depois do WHERE. */
    private void acceptCovered(GroupAggregation aggregation, Statement.Select sel, String table, String rowKey,
                               Map<String, AxonValue> columns) {
        Document doc = new Document(new RecordId(table, parseKey(rowKey)), AxonValue.object(columns));
        if (sel.cond() != null && !truthy(evalCond(sel.cond(), doc))) {
            return;
        }
        aggregation.accept(doc);
    }

    private List<Document> columnarSourceDocs(Database db, Statement.Select sel) {
        return columnarSourceDocs(db, sel, List::of);
    }

    private List<Document> columnarSourceDocs(Database db, Statement.Select sel, java.util.function.Supplier<List<Document>> fallback) {
        return columnarSourceDocs(db, sel, null, fallback);
    }

    /**
     * Igualdade em coluna com índice COLUMNAR. Com {@code window} a leitura das entradas para ao completar
     * START+LIMIT linhas que passam no filtro; sem ele devolve todos os candidatos, como sempre.
     */
    private List<Document> columnarSourceDocs(Database db, Statement.Select sel, Window window,
                                              java.util.function.Supplier<List<Document>> fallback) {
        String table = tableName(sel.from());
        Catalog.IndexDef index = columnarIndexFor(db, table, sel);
        if (index == null) return fallback.get();

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
            int[] candidates = {0};
            records(db).scanPrefix(exactPrefix, key -> {
                byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int encodedLen = com.axonbase.core.storage.ColumnarIndex.encodedDataLength(keyBytes, prefixLen);
                String rowKey = com.axonbase.core.storage.ColumnarIndex.extractRowKey(key, prefixLen, encodedLen);
                Document doc = loadDoc(db, new RecordId(table, parseKey(rowKey)));
                if (doc == null) {
                    return true;
                }
                candidates[0]++;
                if (window == null) {
                    docs.add(doc);
                    return true;
                }
                if (window.accepts().test(doc)) {
                    docs.add(doc);
                }
                return docs.size() < window.wanted();
            });
            // sem nenhuma entrada no índice a consulta cai na varredura; entradas que o WHERE descarta valem como resposta vazia
            return candidates[0] == 0 ? fallback.get() : docs;
        }

        return fallback.get();
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
     * Insere o vetor no grafo HNSW multicamado. O nó entra só do nível 0 até o nível sorteado para ele, de modo que
     * os níveis de cima ficam esparsos e levam a busca depressa para perto do alvo. Em cada nível em que entra, ele é
     * ligado aos vizinhos mais próximos (limite {@code M}).
     */
    private void linkHnsw(Database db, RecordId rid, Catalog.IndexDef index, AxonValue vector) {
        String key = rid.key().keyString();
        int maxLinks = Math.max(4, index.hnswM());
        int efConstruction = Math.max(Math.max(16, index.hnswEfc()), maxLinks * 2);
        int nodeTop = randomLevel(maxLinks);
        // a entrada do nível mais alto é lida antes de o nó novo aparecer no grafo, para que ele não se ache a si mesmo
        GraphTop current = graphTop(db, rid.table(), index);
        int topExisting = current.level();
        for (int level = 0; level <= nodeTop; level++) {
            records(db).put(vhPresenceKey(db, rid.table(), index.name(), level, key), new byte[0]);
        }
        if (nodeTop > topExisting) {
            records(db).put(vhTopKey(db, rid.table(), index.name()),
                Integer.toString(nodeTop).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        // Inserção HNSW: desce do nível mais alto seguindo a busca no grafo e liga o nó aos mais próximos de cada
        // nível. O custo depende de efConstruction e M, não do número de nós do índice.
        hnswDistances = new java.util.HashMap<>();
        try {
            linkLevels(db, rid, index, vector, key, nodeTop, topExisting, current.entry(), maxLinks, efConstruction);
        } finally {
            hnswDistances = null;
        }
    }

    private void linkLevels(Database db, RecordId rid, Catalog.IndexDef index, AxonValue vector, String key,
                            int nodeTop, int topExisting, String entry, int maxLinks, int efConstruction) {
        // acima do nível do nó a busca só aproxima o ponto de entrada
        for (int level = topExisting; level > nodeTop; level--) {
            List<Neighbor> near = searchLevel(db, rid.table(), index, level, List.of(entry), vector, 1);
            if (!near.isEmpty()) {
                entry = near.get(0).key();
            }
        }
        // o nível de baixo parte de todos os candidatos achados no de cima, não só do mais próximo: assim a busca
        // cobre regiões vizinhas que ainda não têm caminho curto entre si, e o nó novo passa a ligá-las
        List<String> entries = entry == null ? List.of() : List.of(entry);
        for (int level = nodeTop; level >= 0; level--) {
            List<Neighbor> nearest = List.of();
            if (!entries.isEmpty() && level <= topExisting) {
                nearest = searchLevel(db, rid.table(), index, level, entries, vector, efConstruction).stream()
                    .filter(nb -> !nb.key().equals(key) && nb.distance() != Double.MAX_VALUE).toList();
            }
            int linkLimit = level == 0 ? maxLinks : Math.max(2, maxLinks / 2);
            for (Neighbor nb : nearest.subList(0, Math.min(linkLimit, nearest.size()))) {
                records(db).put(vhNodePrefix(db, rid.table(), index.name(), level, key) + nb.key(), new byte[0]);
                records(db).put(vhNodePrefix(db, rid.table(), index.name(), level, nb.key()) + key, new byte[0]);
            }
            if (!nearest.isEmpty()) {
                entries = nearest.stream().map(Neighbor::key).toList();
            }
            String entryKey = vhEntryKey(db, rid.table(), index.name(), level);
            if (records(db).get(entryKey).isEmpty()) {
                records(db).put(entryKey, key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Tira um nó do grafo HNSW sem varrer o grafo: a presença do nó em cada nível ({@code @N|chave}) diz em quais
     * níveis ele está, e as arestas saem do prefixo do próprio nó; a aresta inversa fica no prefixo do vizinho. Os
     * índices criados antes já têm esses marcadores, então não há o que migrar.
     */
    private void unlinkHnsw(Database db, RecordId rid, Catalog.IndexDef index) {
        String key = rid.key().keyString();
        boolean removed = false;
        for (int level = 0; level < 64; level++) {
            String presence = vhPresenceKey(db, rid.table(), index.name(), level, key);
            boolean present = records(db).get(presence).isPresent();
            List<String> edges = records(db).keysWithPrefix(vhNodePrefix(db, rid.table(), index.name(), level, key));
            if (!present && edges.isEmpty()) {
                break;
            }
            List<String> neighbors = new ArrayList<>(edges.size());
            for (String edge : edges) {
                String neighbor = edge.substring(edge.lastIndexOf('|') + 1);
                records(db).delete(edge);
                records(db).delete(vhNodePrefix(db, rid.table(), index.name(), level, neighbor) + key);
                neighbors.add(neighbor);
            }
            if (present) {
                records(db).delete(presence);
            }
            reconnectNeighbors(db, rid.table(), index, level, neighbors);
            removed = true;
        }
        if (removed) {
            graphTop(db, rid.table(), index);
        }
    }

    /**
     * Mantém ligados entre si os vizinhos de um nó retirado. Sem isso, o nó que fazia a ponte levaria junto o único
     * caminho até parte do grafo, e nem a busca nem as inserções voltariam a chegar lá. Cada vizinho precisa alcançar
     * o seguinte por uma aresta direta ou por um vizinho em comum; só quando não alcança os dois são ligados.
     */
    private void reconnectNeighbors(Database db, String table, Catalog.IndexDef index, int level,
                                    List<String> neighbors) {
        if (neighbors.size() < 2) {
            return;
        }
        Set<String> previousLinks = linksOf(db, table, index, level, neighbors.get(0));
        for (int i = 1; i < neighbors.size(); i++) {
            String previous = neighbors.get(i - 1);
            String node = neighbors.get(i);
            Set<String> links = linksOf(db, table, index, level, node);
            if (!links.contains(previous) && java.util.Collections.disjoint(links, previousLinks)) {
                records(db).put(vhNodePrefix(db, table, index.name(), level, node) + previous, new byte[0]);
                records(db).put(vhNodePrefix(db, table, index.name(), level, previous) + node, new byte[0]);
                links.add(previous);
            }
            previousLinks = links;
        }
    }

    /** Vizinhos de um nó num nível. */
    private Set<String> linksOf(Database db, String table, Catalog.IndexDef index, int level, String node) {
        Set<String> links = new HashSet<>();
        for (String edge : records(db).keysWithPrefix(vhNodePrefix(db, table, index.name(), level, node))) {
            links.add(edge.substring(edge.lastIndexOf('|') + 1));
        }
        return links;
    }

    /** Maior nível do grafo HNSW que ainda tem nó, com a entrada dele; nível -1 e entrada nula no grafo vazio. */
    private record GraphTop(int level, String entry) {
    }

    /**
     * O nível mais alto só guarda os nós criados desde que ele surgiu. Quando todos saem, o contador desce aqui:
     * partindo de um nível vazio a busca não acharia nada, e os nós inseridos depois ficariam sem aresta alguma.
     */
    private GraphTop graphTop(Database db, String table, Catalog.IndexDef index) {
        int stored = maxLevel(db, table, index);
        for (int level = stored; level >= 0; level--) {
            String entry = entryAt(db, table, index.name(), level);
            if (entry != null) {
                if (level < stored) {
                    records(db).put(vhTopKey(db, table, index.name()),
                        Integer.toString(level).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return new GraphTop(level, entry);
            }
        }
        if (stored >= 0) {
            records(db).delete(vhTopKey(db, table, index.name()));
        }
        return new GraphTop(-1, null);
    }

    /** Sorteia o nível topo de um nó novo: de cada nível para o seguinte sobe cerca de 1 em cada {@code M} nós. */
    private int randomLevel(int links) {
        double draw = 1d - java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        return (int) Math.min(24d, -Math.log(draw) / Math.log(links));
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

    /** Cabeçalho (comentário AxonQL) com o que o texto do comando não carrega: regras geradas, data e autor. */
    private static final String AUDIT_HEADER = "-- aiaudit ";

    private AxonValue runDefineAiAudit(Statement.DefineAiAudit aa) {
        if (ds.isReplaying()) {
            restoreAiAudit(aa);
            return AxonValue.nul();
        }
        if (ds.aiProvider() == null || !ds.aiProvider().isConfigured()) {
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
        Map<String, AxonValue> stored = new LinkedHashMap<>();
        stored.put("rules", AxonValue.str(rulesJson));
        stored.put("created_at", AxonValue.num(def.createdAt()));
        stored.put("created_by", AxonValue.str(def.createdBy() == null ? "" : def.createdBy()));
        String header = AUDIT_HEADER
            + com.axonbase.value.AxonJson.write(AxonValue.object(stored)).replace('\n', ' ') + "\n";
        control(db, ControlCommand.Kind.AUDIT, aa.name(), header + com.axonbase.parser.Render.stmt(aa));
        return AxonValue.object(java.util.Map.of(
            "name", AxonValue.str(aa.name()),
            "rules", AxonValue.str(rulesJson),
            "status", AxonValue.str("CREATED")));
    }

    /**
     * Recria a auditoria na releitura do catálogo sem chamar o provedor de IA, que ainda não está configurado nesse
     * momento. Definições gravadas por versões anteriores não têm as regras: voltam sem nenhuma, com um aviso.
     */
    private void restoreAiAudit(Statement.DefineAiAudit aa) {
        Database db = db();
        java.util.List<com.axonbase.core.audit.AiAuditRule> warningRules = new java.util.ArrayList<>();
        java.util.List<com.axonbase.core.audit.AiAuditRule> dangerRules = new java.util.ArrayList<>();
        long createdAt = 0;
        String createdBy = "";
        boolean restored = false;
        for (ControlCommand command : ds.controlSnapshot().commands()) {
            String text = command.definition();
            if (command.kind() != ControlCommand.Kind.AUDIT || !command.namespace().equals(db.ns())
                || !command.database().equals(db.db()) || !command.name().equals(aa.name())
                || !text.startsWith(AUDIT_HEADER)) {
                continue;
            }
            try {
                Map<String, AxonValue> stored = com.axonbase.value.AxonJson.parseDocument(
                    text.substring(AUDIT_HEADER.length(), text.indexOf('\n'))).asObject();
                parseRules(stored.get("rules").asString(), warningRules, dangerRules);
                createdAt = stored.get("created_at").asLong();
                createdBy = stored.get("created_by").asString();
                restored = true;
            } catch (RuntimeException unreadable) {
                System.err.println(Messages.get("audit_parse_error", unreadable.getMessage()));
            }
        }
        if (!restored) {
            System.err.println(Messages.get("audit_rules_not_stored", aa.name()));
        }
        db.catalog().defineAudit(new com.axonbase.core.audit.AiAuditDef(
            aa.name(), aa.warningWhen(), aa.dangerWhen(), warningRules, dangerRules, createdAt, createdBy));
    }

    private AxonValue runDropAiAudit(Statement.DropAiAudit da) {
        Database db = db();
        if (!db.catalog().removeAudit(da.name())) {
            // versões anteriores gravavam o próprio DROP no plano de controle
            if (ds.isReplaying()) {
                return AxonValue.nul();
            }
            throw errorStmt(Messages.get("audit_not_found", da.name()));
        }
        ds.removeControl(session, db.ns(), db.db(), ControlCommand.Kind.AUDIT, da.name());
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
        if (com.axonbase.core.security.Authz.audited(session.auth())
            && !sessionUserId().equals(auditCase.userId())) {
            throw refusedToAudited();
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
        // Quem está bloqueado por este caso não pode decidi-lo (nem outro caso sobre si).
        if (userId.equals(auditCase.userId()) && cat.isBlocked(userId)) {
            throw new com.axonbase.common.AxonError(-32002, Messages.get("audit_self_decision"));
        }
        // quem pediu não decide, bloqueado ou não, e um auditado não desbloqueia ninguém
        boolean unblocks = "AUTHORIZED".equals(sac.status()) && "DANGER".equals(auditCase.classification());
        if (userId.equals(auditCase.userId())
            || unblocks && com.axonbase.core.security.Authz.audited(session.auth())) {
            throw refusedToAudited();
        }
        // montada antes de decidir: sem a identidade do autor o caso continua pendente
        Session author = "AUTHORIZED".equals(sac.status()) ? authorSession(auditCase) : null;
        auditCase.resolve(sac.status(), userId, sac.reason());
        cat.addEvent(sac.hash(), new com.axonbase.core.audit.AuditCaseEvent(
            sac.status().equals("AUTHORIZED") ? "AUTHORIZED" : "DENIED", userId, sac.reason(), System.currentTimeMillis()));

        // If DANGER case was authorized, unblock user
        if ("AUTHORIZED".equals(sac.status()) && "DANGER".equals(auditCase.classification())) {
            cat.unblockUser(auditCase.userId());
        }

        // O comando aprovado roda com a identidade e o poder de quem o pediu
        if (author != null) {
            try {
                String originalSql = auditCase.sql();
                // Execute the original SQL that was blocked - bypass audit to avoid recursion
                var exec = new Executor(ds);
                exec.bypassAudit = true;
                exec.rawSql = originalSql;
                Query q = com.axonbase.parser.AxonQl.parse(originalSql);
                boolean shared = session.inTransaction();
                if (shared) {
                    // a escrita aprovada vai na mesma transação da decisão (é o que a replica no cluster)
                    author.tx(session.tx());
                    author.txControlVersion(session.txControlVersion());
                    author.txIndexEpoch(session.txIndexEpoch());
                }
                AxonValue result = exec.execute(q, author, auditCase.author().vars());
                if (shared) {
                    // as notificações de live query saem no COMMIT de quem abriu a transação
                    session.queueLive(author::flushLive);
                }
                return result;
            } catch (Exception e) {
                throw errorStmt(Messages.get("audit_exec_error", e.getMessage()));
            }
        }

        return AxonValue.object(java.util.Map.of(
            "hash", AxonValue.str(sac.hash()),
            "status", AxonValue.str(sac.status())));
    }

    /**
     * Sessão com a identidade do autor do caso. Para quem entrou por signin valem os papéis e as Data Rules de hoje,
     * sem passar do papel que o autor tinha ao pedir o comando; autor removido não executa nada.
     */
    private Session authorSession(com.axonbase.core.audit.AuditCase auditCase) {
        com.axonbase.core.audit.AuditAuthor author = auditCase.author();
        if (author == null) {
            throw errorStmt(Messages.get("audit_no_author"));
        }
        Map<String, AxonValue> claims = new LinkedHashMap<>(author.claims().asObject());
        // o comando já passou pela auditoria
        claims.remove("audit");
        AxonValue effective = AxonValue.object(claims);
        AxonValue type = claims.get("typ");
        if (type != null && type.isString() && "access".equals(type.asString())) {
            AuthCatalog.User current = currentIdentity(claims);
            if (current == null) {
                throw errorStmt(Messages.get("audit_author_gone"));
            }
            putOrRemove(claims, "roles", current.roles());
            putOrRemove(claims, "data_rules", current.dataRules());
            effective = com.axonbase.core.security.Authz.cap(AxonValue.object(claims),
                com.axonbase.core.security.Authz.role(author.claims()));
        }
        Session result = new Session(session.namespace(), session.database());
        result.auth(effective);
        if (author.readOnly()) {
            result.roleCap(com.axonbase.core.security.Authz.Role.VIEWER);
        }
        return result;
    }

    private AuthCatalog.User currentIdentity(Map<String, AxonValue> claims) {
        return ds.authCatalog().users().stream()
            .filter(u -> claimEquals(claims, "id", u.name()) && claimEquals(claims, "scope", u.scope().name())
                && claimEquals(claims, "ns", u.namespace()) && claimEquals(claims, "db", u.database()))
            .findFirst().orElse(null);
    }

    private static boolean claimEquals(Map<String, AxonValue> claims, String key, String expected) {
        AxonValue v = claims.get(key);
        return java.util.Objects.equals(v != null && v.isString() ? v.asString() : null, expected);
    }

    private static void putOrRemove(Map<String, AxonValue> claims, String key, List<String> values) {
        if (values.isEmpty()) {
            claims.remove(key);
        } else {
            claims.put(key, AxonValue.str(String.join(",", values)));
        }
    }

    /** O que a execução futura de um comando retido precisa saber sobre quem o pediu. */
    private com.axonbase.core.audit.AuditAuthor currentAuthor() {
        Map<String, AxonValue> claims = new LinkedHashMap<>(session.auth().asObject());
        claims.remove("jti");
        claims.remove("exp");
        Map<String, AxonValue> vars = new LinkedHashMap<>(session.vars().copy());
        vars.keySet().removeIf(Session::isIdentityVariable);
        vars.values().removeIf(java.util.Objects::isNull);
        return new com.axonbase.core.audit.AuditAuthor(AxonValue.object(claims), vars, session.readOnly());
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
        // sem isto, quem está num banco onde a auditoria não existe não conseguiria sair dele
        if (s instanceof Statement.Use) return null;
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
        // como a Data Rule: auditoria que não existe neste banco nega, senão um nome errado liberaria tudo
        if (def == null) {
            throw new com.axonbase.common.AxonError(-32002, Messages.get("audit_missing", auditName));
        }

        String classification = def.classify(sql);
        if ("SAFE".equals(classification)) return null;

        // Generate hash
        String hash = sha256Hex(sql + System.currentTimeMillis() + userId);
        var auditCase = new com.axonbase.core.audit.AuditCase(hash, userId, sql, auditName, classification,
            System.currentTimeMillis(), currentAuthor());

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
