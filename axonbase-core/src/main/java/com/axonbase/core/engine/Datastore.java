package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.catalog.RecordId;
import com.axonbase.core.cluster.AppliedBatchListener;
import com.axonbase.core.cluster.CommitCoordinator;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.control.ControlSnapshot;
import com.axonbase.core.control.ControlStateMachine;
import com.axonbase.core.control.ControlStore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.Transaction;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Query;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Núcleo de dados: namespaces, bancos, catálogos e registros.
 *
 * <p>O catálogo e as identidades vivem em memória, mas a fonte de verdade é o
 * plano de controle: uma lista de {@link ControlCommand} com o texto AxonQL de
 * cada definição, gravada numa única chave do KV. Reexecutar essa lista é o que
 * reconstrói o estado, seja num restart, seja num seguidor que acabou de receber
 * o batch replicado. Definição e replay compartilham o mesmo caminho de código,
 * o que impede que os dois divirjam.</p>
 */
public final class Datastore {

    private final KvBackend backend;
    private final ConcurrentMap<String, ConcurrentMap<String, Database>> namespaces =
        new ConcurrentHashMap<>();
    private final LiveBus liveBus = new LiveBus();
    private final AuthCatalog authCatalog = new AuthCatalog();
    private final ControlStateMachine controlState = new ControlStateMachine();
    private final ControlStore controlStore;
    private final Object controlLock = new Object();
    private volatile CommitCoordinator commitCoordinator;
    private volatile String clusterNodeId;
    /**
     * Marca de reexecução, por thread. Um flag global faria a thread do consenso, ao
     * reconstruir o catálogo, engolir silenciosamente uma definição que um cliente
     * estivesse aplicando ao mesmo tempo.
     */
    private final ThreadLocal<Boolean> replaying = ThreadLocal.withInitial(() -> false);
    /**
     * Transações cuja notificação já saiu pela sessão que as originou. O batch volta
     * pelo listener do consenso e não deve gerar um segundo evento.
     */
    private final Deque<String> locallyOriginated = new ArrayDeque<>();
    private static final int ORIGINATED_MEMORY = 256;

    public Datastore(KvBackend backend) {
        this.backend = backend != null ? backend : new MemoryBackend();
        this.controlStore = new ControlStore(this.backend);
        synchronized (controlLock) {
            controlState.restore(controlStore.load());
            replayControl();
        }
    }

    public static Datastore memory() {
        return new Datastore(new MemoryBackend());
    }

    // ------------------------------------------------------------------
    // namespaces e databases
    // ------------------------------------------------------------------

    public Database ensureDatabase(String ns, String db) {
        return ensureDatabase(null, ns, db);
    }

    /**
     * Garante o banco e registra a criação no plano de controle.
     *
     * <p>Com uma transação aberta na sessão, o registro entra no batch replicado.
     * Fora dela, vai direto ao storage: o próximo DDL ou escrita no banco carrega o
     * snapshot completo para os seguidores de qualquer forma.</p>
     */
    public Database ensureDatabase(Session session, String ns, String db) {
        if (ns == null || ns.isBlank() || db == null || db.isBlank()) {
            throw AxonError.internal("ns e db são obrigatórios para acessar a base");
        }
        ConcurrentMap<String, Database> dbs = namespaces.computeIfAbsent(ns,
            k -> new ConcurrentHashMap<>());
        boolean created = !dbs.containsKey(db);
        Database database = dbs.computeIfAbsent(db, k -> new Database(ns, db, backend));
        if (created && !replaying.get()) {
            applyControl(session, ControlCommand.database(ns, db));
        }
        return database;
    }

    public void createDatabase(String ns, String db) {
        ensureDatabase(ns, db);
    }

    public boolean hasDatabase(String ns, String db) {
        ConcurrentMap<String, Database> dbs = namespaces.get(ns);
        return dbs != null && dbs.containsKey(db);
    }

    public Database requireDatabase(Session session) {
        String ns = session.namespace();
        String db = session.database();
        if (ns == null || db == null) {
            throw AxonError.internal("nenhum namespace/database selecionado");
        }
        return ensureDatabase(session, ns, db);
    }

    /** Barramento de live queries compartilhado por todas as sessões. */
    public LiveBus liveBus() {
        return liveBus;
    }

    /** Catálogo de usuários e access methods deste nó. */
    public AuthCatalog authCatalog() {
        return authCatalog;
    }

    public List<String> namespaces() {
        return List.copyOf(namespaces.keySet());
    }

    public List<String> databases(String ns) {
        ConcurrentMap<String, Database> m = namespaces.get(ns);
        return m == null ? List.of() : List.copyOf(m.keySet());
    }

    // ------------------------------------------------------------------
    // plano de controle
    // ------------------------------------------------------------------

    /**
     * Registra uma definição no plano de controle e grava o snapshot resultante.
     *
     * <p>Com uma transação aberta, o snapshot vai para o buffer dela. É o que faz o
     * DDL viajar no mesmo batch replicado das escritas de dados, chegando ao
     * seguidor de forma atômica e nunca antes do quórum.</p>
     *
     * @param session sessão que originou a definição, ou {@code null} fora de sessão
     * @param command definição já renderizada em AxonQL
     */
    public void applyControl(Session session, ControlCommand command) {
        if (replaying.get()) {
            return;
        }
        synchronized (controlLock) {
            controlState.apply(command);
            KvBackend target = session != null && session.inTransaction() ? session.tx() : backend;
            controlStore.save(controlState, target);
            if (target == backend) {
                backend.flush();
            }
        }
    }

    /** Registra uma definição fora de qualquer sessão. */
    public void applyControl(ControlCommand command) {
        applyControl(null, command);
    }

    public ControlSnapshot controlSnapshot() {
        synchronized (controlLock) {
            return controlState.snapshot();
        }
    }

    /** Ativa confirmação de quórum antes dos commits locais. */
    public void commitCoordinator(CommitCoordinator coordinator, String nodeId) {
        this.commitCoordinator = coordinator;
        this.clusterNodeId = nodeId;
    }

    /**
     * Reconstrói catálogo e identidades reexecutando o plano de controle.
     *
     * <p>Os comandos vêm ordenados por dependência, então um índice com analyzer
     * encontra o analyzer, e um campo encontra a tabela. O flag {@code replaying}
     * evita que a reexecução grave de novo o que acabou de ler.</p>
     */
    private void replayControl() {
        ControlSnapshot snapshot = controlState.snapshot();
        replaying.set(true);
        try {
            namespaces.clear();
            authCatalog.clear();
            for (ControlCommand command : snapshot.commands()) {
                replay(command);
            }
        } finally {
            replaying.set(false);
        }
    }

    private void replay(ControlCommand command) {
        if (command.kind() == ControlCommand.Kind.DATABASE) {
            ensureDatabase(command.namespace(), command.database());
            return;
        }
        Session session = new Session(emptyToNull(command.namespace()),
            emptyToNull(command.database()));
        Query query = AxonQl.parse(command.definition());
        new Executor(this).execute(query, session, null);
    }

    /**
     * Aplica no estado em memória um batch que o consenso já confirmou.
     *
     * <p>Chamado em todos os membros, inclusive no líder. Duas coisas acontecem: o
     * catálogo é reconstruído quando o snapshot de controle mudou, e as live
     * queries deste nó recebem as mudanças de registro. É por aqui que uma
     * subscription num nó que não é líder enxerga uma escrita confirmada.</p>
     */
    public void onReplicatedBatch(AppliedBatchListener.AppliedBatch applied) {
        // O líder já aplicou o DDL em memória e já vai entregar as notificações pela
        // sessão que originou a escrita; reprocessar aqui duplicaria os dois efeitos.
        if (consumeLocalOrigin(applied.batch().transactionId())) {
            return;
        }
        byte[] snapshot = applied.batch().puts().get(ControlStore.KEY);
        if (snapshot != null) {
            // O batch só carrega a chave de controle quando alguma definição mudou, então
            // reconstruir aqui não é desperdício. Reconstruir sempre também é o que faz o
            // acoplamento tardio do listener recuperar o catálogo de um log já aplicado.
            synchronized (controlLock) {
                controlState.restore(ControlStore.decode(snapshot));
                replayControl();
            }
        }
        fanout(applied);
    }

    /** Listener pronto para acoplar a um runtime de consenso. */
    public AppliedBatchListener appliedBatchListener() {
        return this::onReplicatedBatch;
    }

    private void markLocalOrigin(String transactionId) {
        synchronized (locallyOriginated) {
            locallyOriginated.addLast(transactionId);
            while (locallyOriginated.size() > ORIGINATED_MEMORY) {
                locallyOriginated.removeFirst();
            }
        }
    }

    private boolean consumeLocalOrigin(String transactionId) {
        synchronized (locallyOriginated) {
            return locallyOriginated.remove(transactionId);
        }
    }

    // ------------------------------------------------------------------
    // fanout de live queries pós-quórum
    // ------------------------------------------------------------------

    /**
     * Traduz as chaves de registro do batch em notificações de live query.
     *
     * <p>Só chaves de registro entram: as de índice, aresta e controle não
     * representam documentos. O estado anterior vem do batch aplicado, porque o
     * storage já foi sobrescrito quando chegamos aqui.</p>
     */
    private void fanout(AppliedBatchListener.AppliedBatch applied) {
        if (liveBus.isEmpty()) {
            return;
        }
        Executor executor = new Executor(this);
        Session carrier = Session.create();
        for (Map.Entry<String, byte[]> put : applied.batch().puts().entrySet()) {
            RecordRef ref = RecordRef.parse(put.getKey());
            if (ref == null) {
                continue;
            }
            AxonValue after = decodeRecord(put.getValue());
            AxonValue before = decodeRecord(applied.previous().get(put.getKey()));
            if (after == null) {
                continue;
            }
            String action = before == null ? LiveBus.CREATE : LiveBus.UPDATE;
            publish(executor, carrier, ref, action, before, after);
        }
        for (String key : applied.batch().deletes()) {
            RecordRef ref = RecordRef.parse(key);
            AxonValue before = decodeRecord(applied.previous().get(key));
            if (ref == null || before == null) {
                continue;
            }
            publish(executor, carrier, ref, LiveBus.DELETE, before, null);
        }
    }

    private void publish(Executor executor, Session carrier, RecordRef ref, String action,
                         AxonValue before, AxonValue after) {
        ConcurrentMap<String, Database> dbs = namespaces.get(ref.ns());
        Database database = dbs == null ? null : dbs.get(ref.db());
        if (database == null) {
            return;
        }
        AxonValue identity = (after != null ? after : before).asObject().get("id");
        if (identity == null) {
            return;
        }
        RecordId rid = new RecordId(ref.table(), identity);
        executor.publishConfirmed(carrier, database, rid, action, before, after);
    }

    private static AxonValue decodeRecord(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            AxonValue value = AxonJson.decode(raw);
            return value.isObject() && value.asObject().containsKey("id") ? value : null;
        } catch (RuntimeException notARecord) {
            return null;
        }
    }

    /**
     * Referência a um registro extraída da chave física
     * {@code <ns>\0<db>\0<tabela>\0<chave>}. Chaves de índice têm mais segmentos e
     * chaves de controle não têm nenhum, então ambas são descartadas.
     */
    private record RecordRef(String ns, String db, String table) {

        static RecordRef parse(String key) {
            String[] parts = key.split("\u0000", -1);
            if (parts.length != 4 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                return null;
            }
            return new RecordRef(parts[0], parts[1], parts[2]);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    // ------------------------------------------------------------------
    // Execução
    // ------------------------------------------------------------------

    public AxonValue execute(String sql, Session session, Map<String, AxonValue> vars) {
        boolean implicitClusterTransaction = commitCoordinator != null && !session.inTransaction()
            && isMutation(sql);
        if (implicitClusterTransaction) {
            beginSession(session);
        }
        try {
            Query query = AxonQl.parse(sql);
            AxonValue result = new Executor(this).execute(query, session, vars);
            if (implicitClusterTransaction) {
                commitSession(session);
            } else {
                backend.flush();
            }
            return result;
        } catch (RuntimeException e) {
            if (implicitClusterTransaction && session.inTransaction()) {
                cancelSession(session);
            }
            throw e;
        }
    }

    /**
     * O texto muda estado e portanto precisa passar pelo líder.
     *
     * <p>Público porque o transporte usa a mesma regra para decidir se responde
     * NOT_LEADER antes de executar qualquer coisa.</p>
     */
    public static boolean isMutation(String sql) {
        String normalized = sql == null ? "" : sql.stripLeading().toUpperCase(Locale.ROOT);
        return !(normalized.startsWith("SELECT") || normalized.startsWith("INFO")
            || normalized.startsWith("RETURN") || normalized.startsWith("USE ")
            || normalized.startsWith("BEGIN") || normalized.startsWith("COMMIT")
            || normalized.startsWith("CANCEL") || normalized.startsWith("LIVE")
            || normalized.startsWith("KILL"));
    }

    // ------------------------------------------------------------------
    // Export / Import (dump AxonQL)
    // ------------------------------------------------------------------

    /**
     * Gera um dump AxonQL da base: DEFINEs do catálogo seguidos de um CREATE por
     * registro, para restauração.
     */
    public String exportDatabase(String ns, String db) {
        Database database = ensureDatabase(ns, db);
        StringBuilder sb = new StringBuilder();
        for (Catalog.TableDef def : database.catalog().tables()) {
            sb.append("DEFINE TABLE ").append(def.name());
            if (def.schemafull()) {
                sb.append(" SCHEMAFULL");
            } else {
                sb.append(" SCHEMALESS");
            }
            if (def.drop()) {
                sb.append(" DROP");
            }
            sb.append(";\n");
            for (Catalog.FieldDef fd : def.fields().values()) {
                sb.append("DEFINE FIELD ").append(fd.name()).append(" ON TABLE ").append(def.name());
                if (fd.type() != null) {
                    sb.append(" TYPE ").append(fd.type());
                }
                if (fd.assertExpr() != null) {
                    sb.append(" ASSERT ").append(com.axonbase.parser.Render.expr(fd.assertExpr()));
                }
                if (fd.defaultExpr() != null) {
                    sb.append(" DEFAULT ").append(com.axonbase.parser.Render.expr(fd.defaultExpr()));
                }
                sb.append(";\n");
            }
            for (Catalog.IndexDef ix : def.indexes().values()) {
                sb.append("DEFINE INDEX ").append(ix.name()).append(" ON TABLE ").append(def.name())
                    .append(" COLUMNS ").append(String.join(", ", ix.columns()));
                if (ix.unique()) {
                    sb.append(" UNIQUE");
                }
                sb.append(";\n");
            }
        }
        String prefix = ns + "\u0000" + db + "\u0000";
        for (String key : database.records().keysWithPrefix(prefix)) {
            Optional<byte[]> raw = database.records().get(key);
            if (raw.isEmpty()) {
                continue;
            }
            AxonValue record = AxonJson.decode(raw.get());
            if (!record.isObject() || !record.asObject().containsKey("id")) {
                continue;
            }
            // A chave física termina em \0<tabela>\0<chaveString>; extrai tabela e chave
            String body = key.substring(prefix.length());
            int last = body.lastIndexOf('\u0000');
            if (last < 0) {
                continue;
            }
            String table = body.substring(0, last);
            String keyStr = body.substring(last + 1);
            // remove o campo id serializado (string) e emite CREATE <tabela>:<literal>
            Map<String, AxonValue> m = new java.util.LinkedHashMap<>(record.asObject());
            m.remove("id");
            String content = AxonJson.write(AxonValue.object(m));
            sb.append("CREATE ").append(table).append(":").append(literalKey(keyStr))
                .append(" CONTENT ").append(content).append(";\n");
        }
        return sb.toString();
    }

    /** Converte a keyString de storage ('n1', 'sAna') em literal AxonQL. */
    private static String literalKey(String keyStr) {
        if (keyStr.startsWith("n")) {
            return keyStr.substring(1);
        }
        if (keyStr.startsWith("s")) {
            String s = keyStr.substring(1);
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return keyStr;
    }

    /** Restaura um dump AxonQL executando-o. */
    public void importDatabase(String ns, String db, String dump) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        execute(dump, s, null);
    }

    // ------------------------------------------------------------------
    // Transações de sessão (BEGIN / COMMIT / CANCEL)
    // ------------------------------------------------------------------

    public void beginSession(Session session) {
        if (session.inTransaction()) {
            throw AxonError.internal("já existe uma transação ativa");
        }
        // Uma definição de escopo ROOT não tem banco selecionado, mas ainda precisa
        // de um buffer para que o snapshot de controle entre no batch replicado.
        KvBackend base = session.namespace() == null || session.database() == null
            ? backend : requireDatabase(session).records();
        session.tx(new Transaction(base));
    }

    public void commitSession(Session session) {
        Transaction tx = session.tx();
        if (tx == null || !tx.isOpen()) {
            throw AxonError.internal("não há transação ativa");
        }
        CommitCoordinator coordinator = commitCoordinator;
        if (coordinator == null) {
            tx.commit();
        } else {
            // Precondições primeiro, replicação depois, aplicação por último. Validar
            // após o consenso acusaria conflito com a própria escrita que acabou de ser
            // aplicada ao state machine.
            tx.validate();
            String transactionId = java.util.UUID.randomUUID().toString();
            // Registrar antes de confirmar: o listener do consenso é chamado de dentro
            // de confirm() e precisa reconhecer o batch como local para não duplicar
            // as notificações que esta sessão já vai entregar no flushLive.
            markLocalOrigin(transactionId);
            try {
                coordinator.confirm(clusterNodeId, new CommittedBatch(transactionId,
                    tx.stagedWrites(), tx.stagedDeletes()));
            } catch (RuntimeException notConfirmed) {
                consumeLocalOrigin(transactionId);
                // Sem confirmação a transação morre aqui. Deixá-la aberta faria a própria
                // sessão continuar lendo escritas que o cluster nunca aceitou.
                cancelSession(session);
                throw notConfirmed;
            }
            tx.commitValidated();
        }
        session.tx(null);
        backend.flush();
        // As notificações retidas durante a transação só saem agora, depois do quórum.
        session.flushLive();
    }

    public void cancelSession(Session session) {
        Transaction tx = session.tx();
        if (tx != null) {
            tx.cancel();
            session.tx(null);
        }
        session.discardLive();
        // DDL e auth foram aplicados em memória para a própria sessão; se o batch não
        // confirmou, o plano de controle volta ao que está durável no storage.
        synchronized (controlLock) {
            controlState.restore(controlStore.load());
            replayControl();
        }
    }
}
