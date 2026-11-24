package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.core.audit.AiProviderClient;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.catalog.RecordId;
import com.axonbase.core.cluster.AppliedBatchListener;
import com.axonbase.core.cluster.CommitCoordinator;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.cluster.RaftNode;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.control.ControlSnapshot;
import com.axonbase.core.control.ControlStateMachine;
import com.axonbase.core.control.ControlStore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.Transaction;
import com.axonbase.core.storage.VersionConflictException;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

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
    private final ConcurrentMap<String, ConcurrentMap<String, DatabaseLinkDef>> databaseLinks =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SagaDef> sagas = new ConcurrentHashMap<>();
    private final LiveBus liveBus = new LiveBus();
    private final AuthCatalog authCatalog = new AuthCatalog();
    private final ControlStateMachine controlState = new ControlStateMachine();
    private final ControlStore controlStore;
    private final Object controlLock = new Object();
    private volatile CommitCoordinator commitCoordinator;
    private volatile String clusterNodeId;
    private volatile JksRegistrar jksRegistrar;
    private volatile AiProviderClient aiProvider;
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

    // Operation audit configuration
    private boolean auditEnabled;
    private String auditUserFrom = "auth";
    private boolean auditSelect;
    private int auditMaxBody = 65536;

    public Datastore(KvBackend backend) {
        this.backend = backend != null ? backend : new MemoryBackend();
        this.controlStore = new ControlStore(this.backend);
        synchronized (controlLock) {
            controlState.restore(controlStore.load());
            replayControl();
            registerLegacyEdgeKinds();
        }
    }

    /** Nome simples de tabela: o único que o RELATE registra e que a migração aceita (relê sem aspas). */
    static final java.util.regex.Pattern PLAIN_NAME = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Registra no catálogo os tipos de aresta criados por {@code RELATE} antes de o RELATE passar a registrá-los.
     * Cada banco é varrido uma única vez (chave {@code EKM|ns|db} marca o que já foi feito). Chave de aresta:
     * {@code E|ns|db|out|tabelaOrigem|chaveOrigem|tipo|tabelaDestino|chaveDestino}.
     */
    private void registerLegacyEdgeKinds() {
        for (Map.Entry<String, ConcurrentMap<String, Database>> nsEntry : namespaces.entrySet()) {
            for (Map.Entry<String, Database> dbEntry : nsEntry.getValue().entrySet()) {
                String ns = nsEntry.getKey();
                String db = dbEntry.getKey();
                String marker = "EKM|" + ns + "|" + db;
                if (backend.get(marker).isPresent()) {
                    continue;
                }
                java.util.Set<String> kinds = new java.util.TreeSet<>();
                String prefix = "E|" + ns + "|" + db + "|out|";
                backend.scanPrefix(prefix, key -> {
                    String[] parts = key.substring(prefix.length()).split("\\|", 4);
                    if (parts.length == 4 && PLAIN_NAME.matcher(parts[2]).matches()) {
                        kinds.add(parts[2]);
                    }
                    return true;
                });
                boolean registered = false;
                for (String kind : kinds) {
                    if (dbEntry.getValue().catalog().table(kind) == null) {
                        dbEntry.getValue().catalog().defineTable(new com.axonbase.core.catalog.Catalog.TableDef(kind, false, false));
                        controlState.apply(ControlCommand.of(ControlCommand.Kind.TABLE, ns, db, kind,
                            "DEFINE TABLE " + kind + " SCHEMALESS"));
                        registered = true;
                    }
                }
                if (registered) {
                    controlStore.save(controlState, backend);
                }
                backend.put(marker, new byte[] {1});
                backend.flush();
            }
        }
    }

    private volatile java.util.function.BiConsumer<String, Map<String, Object>> eventListener;

    /** Receptor de eventos do motor (auditoria). Chamado na thread que originou o evento. */
    public void eventListener(java.util.function.BiConsumer<String, Map<String, Object>> listener) {
        this.eventListener = listener;
    }

    private void emit(String type, Map<String, Object> data) {
        var l = eventListener;
        if (l != null) {
            l.accept(type, data);
        }
    }

    /** Grava um valor de metadados do servidor (tokens de serviço, histórico de backup). */
    public void metaPut(String key, byte[] value) {
        backend.put("!meta|" + key, value);
    }

    public java.util.Optional<byte[]> metaGet(String key) {
        return backend.get("!meta|" + key);
    }

    public void metaDelete(String key) {
        backend.delete("!meta|" + key);
    }

    /** Lista as chaves de metadados com o prefixo, sem o marcador interno. */
    public List<String> metaKeys(String prefix) {
        return backend.keysWithPrefix("!meta|" + prefix).stream()
            .map(k -> k.substring("!meta|".length())).toList();
    }

    /**
     * Remove um usuário do escopo exato e registra o DROP no plano de controle.
     *
     * @return {@code false} quando o usuário não existe nesse escopo
     */
    public boolean dropUser(Session session, String name, AuthCatalog.Scope scope, String ns, String db) {
        if (!authCatalog.removeUserExact(name, scope, ns, db)) {
            return false;
        }
        applyControl(session, ControlCommand.of(ControlCommand.Kind.USER, ns, db, name, "DROP USER " + name));
        return true;
    }

    /** Há algum registro de dados (chave de registro contém NUL)? Custo linear. */
    public boolean hasRecords() {
        boolean[] found = {false};
        backend.scanPrefix("", key -> {
            if (key.indexOf('\u0000') >= 0) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /** Total de chaves no armazenamento (registros, catálogo e metadados). Custo linear. */
    public long keyCount() {
        return backend.keysWithPrefix("").size();
    }

    /** Chaves de registros de um banco. Custo linear no tamanho do banco. */
    public long keyCount(String ns, String db) {
        return backend.keysWithPrefix(ns + "\u0000" + db + "\u0000").size();
    }

    /** Todos os bancos conhecidos, para varreduras administrativas. */
    public List<Database> allDatabases() {
        List<Database> out = new ArrayList<>();
        namespaces.values().forEach(m -> out.addAll(m.values()));
        return out;
    }

    public static Datastore memory() {
        return new Datastore(new MemoryBackend());
    }

    /** Installs the server-owned JKS registrar without coupling the core to TLS classes. */
    public void jksRegistrar(JksRegistrar registrar) {
        this.jksRegistrar = registrar;
        if (registrar != null) {
            reloadPersistedJks(registrar);
        }
    }

    /**
     * Recarrega os truststores registrados no plano de controle. A senha nunca é persistida: o JKS é
     * aberto sem ela (só certificados confiáveis, sem checagem de integridade), passando {@code null}.
     */
    private void reloadPersistedJks(JksRegistrar registrar) {
        for (ControlCommand c : controlSnapshot().commands()) {
            if (c.kind() != ControlCommand.Kind.JKS || "DROP".equals(c.definition())) {
                continue;
            }
            try {
                registrar.register(c.name(), c.definition(), null, "NONE", List.of());
            } catch (RuntimeException e) {
                System.err.println(Messages.get("jks_reload_failed", c.name(), String.valueOf(e.getMessage())));
            }
        }
    }

    /** Registra no plano de controle um truststore já carregado, para sobreviver a restart. */
    public void persistJks(String name, String path) {
        applyControl(ControlCommand.of(ControlCommand.Kind.JKS, "", "", name, path));
    }

    /** Marca o truststore como removido no plano de controle. */
    public void dropJks(String name) {
        applyControl(ControlCommand.of(ControlCommand.Kind.JKS, "", "", name, "DROP"));
    }

    public void aiProvider(AiProviderClient provider) {
        this.aiProvider = provider;
    }

    public AiProviderClient aiProvider() {
        return aiProvider;
    }

    public void auditConfig(boolean enabled, String userFrom, boolean select, int maxBody) {
        this.auditEnabled = enabled;
        this.auditUserFrom = userFrom != null ? userFrom : "auth";
        this.auditSelect = select;
        this.auditMaxBody = maxBody > 0 ? maxBody : 65536;
    }

    public boolean auditEnabled() { return auditEnabled; }
    public boolean auditSelect() { return auditSelect; }
    public int auditMaxBody() { return auditMaxBody; }
    public String auditUserFrom() { return auditUserFrom; }

    /** Retorna as entradas de auditoria para um namespace e banco. */
    public List<AxonValue> readAuditEntries(String ns, String db) {
        String prefix = "!audit_op|" + (ns == null ? "" : ns) + "|" + (db == null ? "" : db) + "|";
        List<AxonValue> entries = new ArrayList<>();
        for (String key : backend.keysWithPrefix(prefix)) {
            Optional<byte[]> raw = backend.get(key);
            if (raw.isPresent()) {
                try {
                    entries.add(AxonJson.decode(raw.get()));
                } catch (Exception e) {
                    // skip corrupted entries
                }
            }
        }
        return entries;
    }

    public void auditLog(Session session, String action, String table, RecordId rid,
                          AxonValue before, AxonValue after) {
        if (!auditEnabled) return;
        String ns = session.namespace();
        String dbName = session.database();
        if (ns == null || ns.isBlank() || dbName == null || dbName.isBlank()) return;
        String user;
        if ("auth".equals(auditUserFrom)) {
            user = sessionUserId(session).asString();
        } else if (auditUserFrom != null && auditUserFrom.startsWith("session_var:")) {
            String varName = auditUserFrom.substring(12);
            AxonValue v = session.vars().get(varName);
            user = v != null && v.isString() ? v.asString() : "unknown";
        } else {
            user = sessionUserId(session).asString();
        }
        if (user == null || user.isBlank()) user = "unknown";
        try {
            String prefix = "!audit_op|" + ns + "|" + dbName + "|";
            long id = nextAuditId(prefix);
            while (true) {
                Map<String, AxonValue> entry = new LinkedHashMap<>();
                entry.put("id", AxonValue.str(String.format("%010d", id)));
                entry.put("ns", AxonValue.str(ns));
                entry.put("db", AxonValue.str(dbName));
                entry.put("user", AxonValue.str(user));
                entry.put("action", AxonValue.str(action));
                entry.put("table", AxonValue.str(table));
                entry.put("rid", ridAsValue(rid));
                entry.put("ts", AxonValue.num(System.currentTimeMillis()));
                if (before != null) entry.put("before", truncatePayload(before, auditMaxBody));
                if (after != null) entry.put("after", truncatePayload(after, auditMaxBody));
                String key = prefix + String.format("%010d", id);
                if (backend.putIfAbsent(key, AxonJson.encode(AxonValue.object(entry)))) break;
                id++;
            }
        } catch (Exception e) {
            System.err.println("{\"event\":\"audit_log_failed\",\"error\":\"" +
                e.getMessage() + "\"}");
        }
    }

    private long nextAuditId(String prefix) {
        long lastId = 0;
        for (String key : backend.keysWithPrefix(prefix)) {
            try {
                lastId = Math.max(lastId, Long.parseLong(key.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // Ignore malformed keys outside the audit sequence format.
            }
        }
        return lastId + 1;
    }

    private static AxonValue sessionUserId(Session session) {
        AxonValue auth = session.auth();
        if (auth != null && auth.isObject()) {
            AxonValue user = auth.asObject().get("user");
            if (user != null && user.isString()) return user;
            AxonValue id = auth.asObject().get("id");
            if (id != null && id.isString()) return id;
        }
        return AxonValue.str("anonymous");
    }

    private static AxonValue ridAsValue(RecordId rid) {
        return rid == null ? AxonValue.nul()
            : AxonValue.str(rid.table() + ":" + (rid.key() instanceof AxonValue kv ? kv.asString() : String.valueOf(rid.key())));
    }

    private static AxonValue truncatePayload(AxonValue value, int maxBytes) {
        if (value == null) return AxonValue.nul();
        byte[] encoded = AxonJson.encode(value);
        if (encoded.length <= maxBytes) return value;
        return AxonValue.str(new String(encoded, 0, maxBytes, StandardCharsets.UTF_8) + "...");
    }

    /** Executes an administrative JKS registration without persisting its secret. */
    public void registerJks(String name, String path, String password, String collector, List<String> oids) {
        JksRegistrar registrar = jksRegistrar;
        if (registrar == null) {
            throw AxonError.internal(Messages.get("stmt_jks_server_only"));
        }
        char[] secret = password == null ? new char[0] : password.toCharArray();
        try {
            registrar.register(name, path, secret, collector, oids == null ? List.of() : List.copyOf(oids));
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
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
            throw AxonError.internal(Messages.get("stmt_ns_db_required"));
        }
        ConcurrentMap<String, Database> dbs = namespaces.computeIfAbsent(ns,
            k -> new ConcurrentHashMap<>());
        boolean created = !dbs.containsKey(db);
        Database database = dbs.computeIfAbsent(db, k -> {
            Database fresh = new Database(ns, db, backend);
            fresh.auditCatalog().listener((type, data) -> {
                Map<String, Object> enriched = new java.util.LinkedHashMap<>(data);
                enriched.put("ns", ns);
                enriched.put("db", db);
                emit(type, enriched);
            });
            return fresh;
        });
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
            throw AxonError.internal(Messages.get("stmt_scope_required"));
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
    // database links
    // ------------------------------------------------------------------

    public record DatabaseLinkDef(String name, String url, String ns, String db,
                                   String user, String password) {
    }

    public void defineDatabaseLink(String ns, String linkName, DatabaseLinkDef def) {
        databaseLinks.computeIfAbsent(ns, k -> new ConcurrentHashMap<>())
            .put(linkName, def);
    }

    public void dropDatabaseLink(String ns, String linkName) {
        ConcurrentMap<String, DatabaseLinkDef> links = databaseLinks.get(ns);
        if (links != null) {
            links.remove(linkName);
        }
    }

    public DatabaseLinkDef databaseLink(String ns, String linkName) {
        ConcurrentMap<String, DatabaseLinkDef> links = databaseLinks.get(ns);
        return links == null ? null : links.get(linkName);
    }

    public List<String> databaseLinkNames(String ns) {
        ConcurrentMap<String, DatabaseLinkDef> links = databaseLinks.get(ns);
        return links == null ? List.of() : List.copyOf(links.keySet());
    }

    // ------------------------------------------------------------------
    // saga resource definitions
    // ------------------------------------------------------------------

    public record SagaDef(String name, List<String> links) {
    }

    public void defineSaga(String sagaName, SagaDef def) {
        sagas.put(sagaName, def);
    }

    public List<String> sagaNames() {
        return List.copyOf(sagas.keySet());
    }

    public SagaDef saga(String sagaName) {
        return sagas.get(sagaName);
    }

    public void dropSaga(String sagaName) {
        sagas.remove(sagaName);
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
    /** {@code true} enquanto o plano de controle está sendo reexecutado nesta thread. */
    public boolean isReplaying() {
        return replaying.get();
    }

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

    /**
     * Tira do plano de controle a tabela e tudo que pertence a ela (campos, índices e eventos). Sem isso o
     * {@code DEFINE TABLE} continuava no replay e a tabela voltava a cada reinício; e como campo, índice e evento
     * recriam a tabela quando ela falta, eles também precisam sair.
     */
    public void removeTableControl(Session session, String ns, String db, String table) {
        if (replaying.get()) {
            return;
        }
        synchronized (controlLock) {
            int removed = controlState.removeWhere(c -> c.namespace().equals(ns) && c.database().equals(db)
                && table.equals(tableOfControl(c)));
            if (removed == 0) {
                return;
            }
            KvBackend target = session != null && session.inTransaction() ? session.tx() : backend;
            controlStore.save(controlState, target);
            if (target == backend) {
                backend.flush();
            }
        }
    }

    /** Tabela a que um comando de controle pertence, ou {@code null} (usuário, banco, analyzer...). */
    private static String tableOfControl(ControlCommand c) {
        switch (c.kind()) {
            case TABLE:
                return c.name();
            case FIELD: {
                int colon = c.name().indexOf(':');
                return colon < 0 ? null : c.name().substring(0, colon);
            }
            case INDEX:
            case EVENT:
                try {
                    for (com.axonbase.parser.ast.Statement st : AxonQl.parse(c.definition()).statements()) {
                        if (st instanceof com.axonbase.parser.ast.Statement.DefineIndex di) {
                            return di.table();
                        }
                        if (st instanceof com.axonbase.parser.ast.Statement.CreateIndex ci) {
                            return ci.table();
                        }
                        if (st instanceof com.axonbase.parser.ast.Statement.DefineEvent de) {
                            return de.table();
                        }
                        if (st instanceof com.axonbase.parser.ast.Statement.CreateEvent ce) {
                            return ce.table();
                        }
                    }
                } catch (RuntimeException e) {
                    return null;
                }
                return null;
            default:
                return null;
        }
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
        if (command.kind() == ControlCommand.Kind.JKS) {
            return; // recarregado quando o registrar do servidor é instalado
        }
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
     *
     * <p>O {@link RaftNode} entrega este callback depois de liberar o monitor que
     * protege o log e o state machine. Assim, o replay do catálogo pode executar
     * AxonQL sem bloquear o processamento de mensagens Raft seguintes, mas termina
     * antes de a resposta da mensagem aplicada ser devolvida.</p>
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
            var exec = new Executor(this);
            exec.rawSql = sql;
            AxonValue result = exec.execute(query, session, vars);
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
        Query query;
        try {
            query = AxonQl.parse(sql);
        } catch (RuntimeException e) {
            // Texto que não parseia não é provado como leitura; o erro de sintaxe sai na execução.
            return true;
        }
        return query.statements().isEmpty()
            || query.statements().stream().anyMatch(statement -> !isReadOnly(statement));
    }

    /** Instrução que só lê (classificação por AST), usada no roteamento e na imposição de somente leitura. */
    public static boolean isReadOnly(Statement statement) {
        return statement instanceof Statement.Select
            || statement instanceof Statement.Explain
            || statement instanceof Statement.Info
            || statement instanceof Statement.Return
            || statement instanceof Statement.Use
            || statement instanceof Statement.Begin
            || statement instanceof Statement.Commit
            || statement instanceof Statement.Cancel
            || statement instanceof Statement.Live
            || statement instanceof Statement.Kill
            || statement instanceof Statement.DescribeTable
            || statement instanceof Statement.DescribeSaga
            || statement instanceof Statement.ShowSagaTransaction
            || statement instanceof Statement.ShowDataRules;
    }

    // ------------------------------------------------------------------
    // Export / Import (dump AxonQL)
    // ------------------------------------------------------------------

/**
     * Genera un dump AxonQL de la base: DEFINEs del catálogo seguidos de un CREATE por
     * registro, para restauração.
     *
     * <p>O KV público queda fóra do dump: as chaves {@code KV|...} non entran no
     * varrido {@code ns\0db\0} que este método percorre, nin se restauran con
     * {@link #importDatabase}. Unha copia de seguridade que despexe a base non
     * inclúe, pois, as entradas do key-value.</p>
     */
    public String exportDatabase(String ns, String db) {
        ConcurrentMap<String, Database> databases = namespaces.get(ns);
        Database database = databases == null ? null : databases.get(db);
        if (database == null) {
            throw AxonError.internal(Messages.get("stmt_database_export_missing", ns, db));
        }
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
                if (fd.references() != null) {
                    sb.append(" REFERENCES ").append(fd.references());
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
    // Backup / restore de cluster
    // ------------------------------------------------------------------

    /**
     * Snapshot completo do estado replicado: chaves físicas do backend (registros,
     * arestas {@code E|}, key-value {@code KV|}, índices e reservas) mais o snapshot
     * do plano de controle (DEFINEs e identidades). Valores em Base64 por linha,
     * precedidos do cabeçalho {@code --axonbase-backup-v1--}.
     */
    public String backupCluster() {
        StringBuilder sb = new StringBuilder("--axonbase-backup-v1--\n");
        sb.append("ctrl\t")
            .append(java.util.Base64.getEncoder().encodeToString(
                com.axonbase.core.control.CatalogCodec.encode(controlState.snapshot())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .append('\n');
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        for (String key : backend.keysWithPrefix("")) {
            java.util.Optional<byte[]> raw = backend.get(key);
            if (raw.isPresent()) {
                sb.append(key).append('\t')
                    .append(b64.encodeToString(raw.get())).append('\n');
            }
        }
        return sb.toString();
    }

    /** Restaura um {@link #backupCluster()}: limpa tudo e reconstrói. */
    public void restoreCluster(String dump) {
        try {
            restoreCluster(new java.io.BufferedReader(new java.io.StringReader(dump)));
        } catch (java.io.IOException e) {
            throw AxonError.internal(Messages.get("stmt_cluster_backup_invalid"));
        }
    }

    /** Marca das linhas de deleção do backup incremental: {@code !del<TAB>base64(chave)}. */
    static final String DELETE_MARK = "!del";

    /**
     * Restaura um backup lendo linha a linha, sem materializar o dump. O cabeçalho é validado antes
     * de qualquer escrita; depois o armazenamento é limpo e as linhas são aplicadas em ordem
     * (inclusive as deleções de backups incrementais mesclados).
     *
     * @return quantas chaves foram aplicadas (gravações mais deleções), sem contar o plano de controle
     */
    public long restoreCluster(java.io.BufferedReader reader) throws java.io.IOException {
        String header = reader.readLine();
        if (!"--axonbase-backup-v1--".equals(header)) {
            throw AxonError.internal(Messages.get("stmt_cluster_backup_invalid"));
        }
        // limpa em lotes para não listar todas as chaves de uma vez
        List<String> batch = new ArrayList<>();
        do {
            batch.clear();
            backend.scanPrefix("", key -> {
                batch.add(key);
                return batch.size() < 10_000;
            });
            for (String key : batch) {
                backend.delete(key);
            }
        } while (!batch.isEmpty());
        java.util.Base64.Decoder b64 = java.util.Base64.getDecoder();
        boolean restoredControl = false;
        long applied = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String key = line.substring(0, tab);
            byte[] value = b64.decode(line.substring(tab + 1));
            if ("ctrl".equals(key)) {
                controlState.restore(com.axonbase.core.control.CatalogCodec.decode(
                    new String(value, java.nio.charset.StandardCharsets.UTF_8)));
                restoredControl = true;
            } else if (DELETE_MARK.equals(key)) {
                backend.delete(new String(value, java.nio.charset.StandardCharsets.UTF_8));
                applied++;
            } else {
                backend.put(key, value);
                applied++;
            }
        }
        if (restoredControl) {
            replayControl();
            controlStore.save(controlState, backend);
        }
        backend.flush();
        return applied;
    }

    /**
     * Escreve o backup em streaming no formato {@code .axbak} (texto, uma linha por chave, valores em
     * Base64), varrendo o backend com {@link KvBackend#scanPrefix}: a memória não cresce com o tamanho.
     *
     * @param since versão a partir da qual incluir chaves alteradas ({@code <= 0} faz backup completo);
     *              backends sem versão sempre fazem backup completo. Deleções posteriores a {@code since}
     *              saem como linhas {@code !del} quando o backend as reporta.
     * @return {@code true} se o backup escrito é incremental
     */
    public boolean backupTo(java.io.OutputStream out, long since) throws java.io.IOException {
        VersionedKvBackend versioned = backend instanceof VersionedKvBackend v ? v : null;
        boolean incremental = since > 0 && versioned != null;
        java.io.Writer w = new java.io.BufferedWriter(
            new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        w.write("--axonbase-backup-v1--\n");
        w.write("ctrl\t");
        w.write(b64.encodeToString(com.axonbase.core.control.CatalogCodec.encode(controlState.snapshot())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        w.write('\n');
        java.io.IOException[] failure = new java.io.IOException[1];
        backend.scanPrefix("", key -> {
            try {
                if (incremental && versioned.versionOf(key) <= since) {
                    return true;
                }
                java.util.Optional<byte[]> raw = backend.get(key);
                if (raw.isPresent()) {
                    w.write(key);
                    w.write('\t');
                    w.write(b64.encodeToString(raw.get()));
                    w.write('\n');
                }
                return true;
            } catch (java.io.IOException e) {
                failure[0] = e;
                return false;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        if (incremental) {
            versioned.scanDeletedSince(since, key -> {
                try {
                    w.write(DELETE_MARK);
                    w.write('\t');
                    w.write(b64.encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    w.write('\n');
                    return true;
                } catch (java.io.IOException e) {
                    failure[0] = e;
                    return false;
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
        }
        w.flush();
        return incremental;
    }

    /**
     * Snapshot incremental do estado replicado: exatamente {@link #backupCluster()},
     * mas incluindo apenas as chaves cuja versão (do {@link VersionedKvBackend#versionOf})
     * é maior que {@code lastIndex}. O snapshot de controle entra sempre, porque é a
     * âncora que {@link #restoreCluster} usa além das chaves.
     *
     * <p>Quando {@code lastIndex <= 0}, o backend não é {@link VersionedKvBackend} ou o
     * snapshot não pode ser calculado de forma incremental, cai no backup completo.</p>
     */
    public String backupIncremental(long lastIndex) {
        boolean versioned = backend instanceof VersionedKvBackend;
        if (lastIndex <= 0 || !versioned) {
            return backupCluster();
        }
        VersionedKvBackend versionedBackend = (VersionedKvBackend) backend;
        StringBuilder sb = new StringBuilder("--axonbase-backup-v1--\n");
        sb.append("ctrl\t")
            .append(java.util.Base64.getEncoder().encodeToString(
                com.axonbase.core.control.CatalogCodec.encode(controlState.snapshot())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .append('\n');
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        for (String key : backend.keysWithPrefix("")) {
            if (versionedBackend.versionOf(key) <= lastIndex) {
                continue;
            }
            java.util.Optional<byte[]> raw = backend.get(key);
            if (raw.isPresent()) {
                sb.append(key).append('\t')
                    .append(b64.encodeToString(raw.get())).append('\n');
            }
        }
        versionedBackend.scanDeletedSince(lastIndex, key -> {
            sb.append(DELETE_MARK).append('\t').append(b64.encodeToString(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8))).append('\n');
            return true;
        });
        return sb.toString();
    }

    /** Marca d'água de versão do backend, usada pelo backup incremental. 0 quando não há contador. */
    public long backupVersion() {
        return backend instanceof VersionedKvBackend versioned ? versioned.currentVersion() : 0L;
    }

    /** {@code true} quando o backend suporta snapshots incrementais por versão. */
    public boolean incrementalSupported() {
        return backend instanceof VersionedKvBackend;
    }

    // ------------------------------------------------------------------
    // Transações de sessão (BEGIN / COMMIT / CANCEL)
    // ------------------------------------------------------------------

    public void beginSession(Session session) {
        if (session.inTransaction()) {
            throw AxonError.internal(Messages.get("txn_active"));
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
            throw AxonError.internal(Messages.get("txn_inactive"));
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

    // ------------------------------------------------------------------
    // retry otimista
    // ------------------------------------------------------------------

    /**
     * Executa o corpo ata {@code maxAttempts} veces, rexerando unha espera pequena
     * cada vez que o corpo lanza {@link VersionConflictException} (conflito de versión
     * ou de rango). Se esgotan as tentativas, devolve {@code null}.
     *
     * <p>É un helper de capa de aplicación para commits otimistas simples; non se aplica
     * ao camiño de cluster, onde o propio consenso xa ordena as escritas.</p>
     */
    public <T> T withRetry(int maxAttempts, Supplier<T> body) {
        return withRetry(maxAttempts, body, 5L);
    }

    /** Igual que {@link #withRetry(int, Supplier)} pero coa espera configurable. */
    public <T> T withRetry(int maxAttempts, Supplier<T> body, long delayMs) {
        int failures = 0;
        while (true) {
            try {
                return body.get();
            } catch (VersionConflictException conflict) {
                failures++;
                if (failures >= maxAttempts) {
                    return null;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Key-Value store (KV)
    // ------------------------------------------------------------------

    /**
     * Prefixo dedicado das chaves KV: {@code KV|<ns>|<db>|<chave>}. Comparten
     * o mesmo backend dos registros, pero o prefixo propio evita colidir co
     * {@link #decodeRecord} (que esixe 4 segmentos cortados por {@code \0}),
     * coas arestas/índices ({@code E|}, {@code VH|}, {@code SP|}) e co export,
     * que varre {@code ns\0db\0}. O token {@code ttl} queda reservado no
     * primeiro segmento tras o db (metadado á beira): una chave que comece
     * textualmente por {@code ttl|} colidiría, e está documentada como límite.
     */
    private static final String KV_SCHEME = "KV";

    private static String kvValueKey(String ns, String db, String key) {
        return KV_SCHEME + "|" + ns + "|" + db + "|" + key;
    }

    private static String kvTtlKey(String ns, String db, String key) {
        return KV_SCHEME + "|" + ns + "|" + db + "|ttl|" + key;
    }

    private static String kvPrefix(String ns, String db) {
        return KV_SCHEME + "|" + ns + "|" + db + "|";
    }

    private KvBackend kvStore(Session session) {
        Transaction tx = session != null ? session.tx() : null;
        return tx != null && tx.isOpen() ? tx : backend;
    }

    private boolean kvExpired(KvBackend store, String ns, String db, String key) {
        Optional<byte[]> raw = store.get(kvTtlKey(ns, db, key));
        if (raw.isEmpty()) {
            return false;
        }
        try {
            long expire = Long.parseLong(new String(raw.get(), StandardCharsets.UTF_8));
            if (Instant.now().getEpochSecond() >= expire) {
                store.delete(kvValueKey(ns, db, key));
                store.delete(kvTtlKey(ns, db, key));
                return true;
            }
        } catch (NumberFormatException corrupted) {
            // metadata corrupto não deixa de entregar o valor; o próximo kvSet corrige
        }
        return false;
    }

    private static byte[] encodeKv(AxonValue value) {
        return AxonJson.encode(value);
    }

    private static AxonValue decodeKv(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return AxonValue.nul();
        }
        try {
            return AxonJson.decode(raw);
        } catch (RuntimeException corrupted) {
            return AxonValue.nul();
        }
    }

    /**
     * Executa unha operación de escritura KV participando da transacción de
     * sesión cando xa está aberta; se non, replica a política de
     * {@link #execute}: abre e confirma unha transacción implícita de cluster
     * nun nó con consenso, para que o write viaxe no log replicado.
     */
    private <T> T withKvWrite(Session session, java.util.function.Supplier<T> work) {
        boolean implicit = commitCoordinator != null && !session.inTransaction();
        if (implicit) {
            beginSession(session);
        }
        try {
            T result = work.get();
            if (implicit) {
                commitSession(session);
            } else {
                backend.flush();
            }
            return result;
        } catch (RuntimeException e) {
            if (implicit && session.inTransaction()) {
                cancelSession(session);
            }
            throw e;
        }
    }

    private static void requireWritable(Session s) {
        if (s.readOnly()) {
            throw new AxonError(-32002, Messages.get("session_read_only"));
        }
    }

    /** Lee un valor KV. Devolve {@code NONE} cando non existe ou expirou. */
    public AxonValue kvGet(Session session, String ns, String db, String key) {
        Session s = session != null ? session : Session.create();
        KvBackend store = kvStore(s);
        if (kvExpired(store, ns, db, key)) {
            return AxonValue.none();
        }
        Optional<byte[]> raw = store.get(kvValueKey(ns, db, key));
        return raw.isEmpty() ? AxonValue.none() : decodeKv(raw.get());
    }

    /**
     * Escribe (u sobrescribe) un valor KV, con TTL opcional en segundos.
     * Devolve o valor persistido. Un TTL {@code null} ou {@code <= 0} elimina
     * calquera expiración previa.
     */
    public AxonValue kvSet(Session session, String ns, String db, String key,
                           AxonValue value, Long ttlSeconds) {
        final Session s = session != null ? session : Session.create();
        requireWritable(s);
        if (ns == null || ns.isBlank() || db == null || db.isBlank()
            || key == null || key.isBlank()) {
            throw AxonError.internal(Messages.get("kv_scope_key_required"));
        }
        return withKvWrite(s, () -> {
            KvBackend store = kvStore(s);
            store.put(kvValueKey(ns, db, key), encodeKv(value));
            if (ttlSeconds != null && ttlSeconds > 0) {
                long expire = Instant.now().getEpochSecond() + ttlSeconds;
                store.put(kvTtlKey(ns, db, key),
                    Long.toString(expire).getBytes(StandardCharsets.UTF_8));
            } else {
                store.delete(kvTtlKey(ns, db, key));
            }
            return value;
        });
    }

    /** Borra un valor KV e a súa metadata de expiración. Devolve se existía. */
    public boolean kvDel(Session session, String ns, String db, String key) {
        Session s = session != null ? session : Session.create();
        requireWritable(s);
        return withKvWrite(s, () -> {
            KvBackend store = kvStore(s);
            boolean existed = store.delete(kvValueKey(ns, db, key));
            store.delete(kvTtlKey(ns, db, key));
            return existed;
        });
    }

    /**
     * Varré os valores KV cuxa chave comece por {@code prefix}. Cada entrada
     * devolvesse como {@code {key, value, ttl?}}, filtrando (e limpando
     * preguizosamente) as expiradas.
     */
    public AxonValue kvScan(Session session, String ns, String db, String prefix) {
        Session s = session != null ? session : Session.create();
        KvBackend store = kvStore(s);
        String base = kvPrefix(ns, db) + (prefix == null ? "" : prefix);
        List<AxonValue> out = new ArrayList<>();
        for (String k : store.keysWithPrefix(base)) {
            String rest = k.substring(kvPrefix(ns, db).length());
            if (rest.startsWith("ttl|")) {
                continue;
            }
            if (kvExpired(store, ns, db, rest)) {
                continue;
            }
            Optional<byte[]> raw = store.get(k);
            if (raw.isEmpty()) {
                continue;
            }
            Map<String, AxonValue> entry = new LinkedHashMap<>();
            entry.put("key", AxonValue.str(rest));
            entry.put("value", decodeKv(raw.get()));
            Optional<byte[]> ttlRaw = store.get(kvTtlKey(ns, db, rest));
            if (ttlRaw.isPresent()) {
                try {
                    long expire = Long.parseLong(new String(ttlRaw.get(), StandardCharsets.UTF_8));
                    entry.put("ttl",
                        AxonValue.num(Math.max(0L, expire - Instant.now().getEpochSecond())));
                } catch (NumberFormatException ignored) {
                    // metadado corrupto: a entrada non carga TTL
                }
            }
            out.add(AxonValue.object(entry));
        }
        return AxonValue.array(out);
    }
}
