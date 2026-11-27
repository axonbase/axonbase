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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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

    private static final System.Logger LOG = System.getLogger(Datastore.class.getName());

    private final KvBackend backend;
    private final ConcurrentMap<String, ConcurrentMap<String, Database>> namespaces =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, DatabaseLinkDef>> databaseLinks =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SagaDef> sagas = new ConcurrentHashMap<>();
    private final LiveBus liveBus = new LiveBus();
    private final AuthCatalog authCatalog = new AuthCatalog();
    private final ControlStateMachine controlState = new ControlStateMachine();
    private volatile com.axonbase.core.embedding.EmbeddingClient embeddingClient =
        new com.axonbase.core.embedding.EmbeddingClient();
    private final ControlStore controlStore;
    private final Object controlLock = new Object();
    private final com.axonbase.core.partition.ColdTier coldTier;
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
    /** Lotes confirmados por este nó e ainda não entregues pelo consenso. */
    private final java.util.LinkedHashSet<String> locallyOriginated = new java.util.LinkedHashSet<>();
    /** Só protege um coordenador que nunca entrega; com group commit centenas ficam em voo ao mesmo tempo. */
    private static final int ORIGINATED_MEMORY = 100_000;

    // Operation audit configuration
    private boolean auditEnabled;
    private String auditUserFrom = "auth";
    private boolean auditSelect;
    private int auditMaxBody = 65536;

    public Datastore(KvBackend backend) {
        this(backend, com.axonbase.core.partition.ColdTier.NONE);
    }

    /** A camada fria entra antes do replay do catálogo: construções de índice retomadas já leem partições frias. */
    public Datastore(KvBackend backend, com.axonbase.core.partition.ColdTier coldTier) {
        this.coldTier = coldTier;
        this.backend = backend != null ? backend : new MemoryBackend();
        this.controlStore = new ControlStore(this.backend);
        synchronized (controlLock) {
            controlState.restore(controlStore.load());
            repairControl();
            replayControl();
            registerLegacyEdgeKinds();
            buildPrimaryKeys();
        }
    }

    private final PrimaryKeyEntries primaryKeyEntries = new PrimaryKeyEntries(this);

    PrimaryKeyEntries primaryKeyEntries() {
        return primaryKeyEntries;
    }

    CommitCoordinator commitCoordinator() {
        return commitCoordinator;
    }

    /**
     * Depois de repor ou trocar a base: o marcador que veio junto não garante as entradas (o backup pode ser de um nó
     * em que elas não batiam com os registros), então tudo é refeito.
     */
    private void rebuildRestoredPrimaryKeys() {
        backend.keysWithPrefix("PKB|").forEach(backend::delete);
        primaryKeyEntries.forget();
        buildPrimaryKeys();
    }

    /** Grava as entradas de chave primária que faltam: bases da rc1 declaravam a chave sem mantê-las. */
    private void buildPrimaryKeys() {
        namespaces.forEach((ns, databases) -> databases.forEach((name, database) -> {
            for (com.axonbase.core.catalog.Catalog.TableDef table : database.catalog().tables()) {
                if (!table.primaryKey().isEmpty()) {
                    try {
                        new Executor(this).forBuild(new Session(ns, name)).rebuildPrimaryKey(database, table.name());
                        backend.flush();
                    } catch (RuntimeException e) {
                        // sem o marcador, a próxima abertura tenta de novo
                        LOG.log(System.Logger.Level.ERROR, "chave primária de " + ns + "/" + name + "."
                            + table.name() + " não foi refeita", e);
                    }
                }
            }
        }));
    }

    /**
     * Ao abrir a base, tira do plano de controle o que versões anteriores gravaram e que não pode ser reexecutado
     * (ver {@link LegacyIndexDrops}), e grava o resultado.
     */
    private void repairControl() {
        if (removeLegacyIndexDrops()) {
            controlStore.save(controlState, backend);
            backend.flush();
        }
    }

    private boolean removeLegacyIndexDrops() {
        List<ControlCommand> stored = controlState.snapshot().commands();
        List<ControlCommand> kept = LegacyIndexDrops.clean(stored, backend);
        if (kept.size() == stored.size()) {
            return false;
        }
        controlState.restore(new ControlSnapshot(kept));
        return true;
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
     * Remove um usuário do escopo exato e tira a definição dele do plano de controle.
     *
     * @return {@code false} quando o usuário não existe nesse escopo
     */
    public boolean dropUser(Session session, String name, AuthCatalog.Scope scope, String ns, String db) {
        if (authCatalog.isInitialRoot(name, scope)) {
            throw new com.axonbase.common.AxonError(-32002,
                com.axonbase.common.Messages.get("auth_initial_root_protected"));
        }
        if (!authCatalog.removeUserExact(name, scope, ns, db)) {
            return false;
        }
        pruneUserControl(session, name);
        return true;
    }

    /**
     * Deixa o plano de controle igual às identidades em memória para este nome: sai a definição de quem não existe
     * mais e também o {@code DROP USER} que versões anteriores gravavam, porque a releitura o aplicava fora de ordem
     * (o usuário voltava, ou um usuário recriado sumia).
     */
    void pruneUserControl(Session session, String name) {
        if (replaying.get()) {
            return;
        }
        synchronized (controlLock) {
            int removed = controlState.removeWhere(c -> c.kind() == ControlCommand.Kind.USER
                && (c.name().equals(name) && c.definition().startsWith("DROP USER ") || definesMissingUser(c, name)));
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

    /**
     * Tira do plano de controle uma Data Rule, gravada sob {@code datarule:nome} ou, por versões anteriores, sob o
     * próprio nome (o mesmo lugar da definição de uma tabela: por isso o texto também é conferido).
     */
    void removeDataRuleControl(Session session, String ns, String db, String name) {
        if (replaying.get()) {
            return;
        }
        synchronized (controlLock) {
            int removed = controlState.removeWhere(c -> c.kind() == ControlCommand.Kind.TABLE
                && c.namespace().equals(ns) && c.database().equals(db)
                && (c.name().equals(name) || c.name().equals("datarule:" + name))
                && c.definition().startsWith("CREATE DATA RULE "));
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

    private boolean definesMissingUser(ControlCommand command, String name) {
        for (AuthCatalog.Scope scope : AuthCatalog.Scope.values()) {
            // mesma forma do nome que o Executor dá à definição: nome:ESCOPO:ns:db
            String identity = name + ":" + scope + ":" + command.namespace() + ":" + command.database();
            if (command.name().equals(identity)) {
                return !authCatalog.hasUserExact(name, scope, command.namespace(), command.database());
            }
        }
        return false;
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
            String counterKey = "!audit_seq|" + ns + "|" + dbName;
            long id = nextAuditId(prefix, counterKey);
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
            persistAuditCounter(prefix, counterKey, id);
        } catch (Exception e) {
            System.err.println("{\"event\":\"audit_log_failed\",\"error\":\"" +
                e.getMessage() + "\"}");
        }
    }

    /** Último id de auditoria usado por prefixo ({@code !audit_op|ns|db|}), para não listar o log a cada DML. */
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicLong> auditCounters = new ConcurrentHashMap<>();

    /**
     * Próximo id da trilha. O contador fica em memória e persistido em {@code !audit_seq|ns|db}; o custo por escrita
     * não depende do tamanho do log. Banco antigo, sem contador, é varrido uma única vez (migração preguiçosa). Se
     * outro nó gravar na frente, o {@code putIfAbsent} do chamador acerta o id.
     */
    private long nextAuditId(String prefix, String counterKey) {
        java.util.concurrent.atomic.AtomicLong counter = auditCounters.computeIfAbsent(prefix, k -> {
            long last = 0;
            java.util.Optional<byte[]> stored = backend.get(counterKey);
            if (stored.isPresent()) {
                try {
                    last = Long.parseLong(new String(stored.get(), java.nio.charset.StandardCharsets.UTF_8));
                } catch (NumberFormatException ignored) {
                    // contador ilegível: recalcula pela varredura
                }
            }
            if (last == 0) {
                for (String key : backend.keysWithPrefix(prefix)) {
                    try {
                        last = Math.max(last, Long.parseLong(key.substring(prefix.length())));
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed keys outside the audit sequence format.
                    }
                }
            }
            return new java.util.concurrent.atomic.AtomicLong(last);
        });
        return counter.incrementAndGet();
    }

    private void persistAuditCounter(String prefix, String counterKey, long id) {
        java.util.concurrent.atomic.AtomicLong counter = auditCounters.get(prefix);
        if (counter != null) {
            counter.accumulateAndGet(id, Math::max);
        }
        backend.put(counterKey, Long.toString(id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            : AxonValue.str(rid.table() + ":" + (rid.key() instanceof AxonValue kv ? (kv.isString() ? kv.asString() : kv.toString()) : String.valueOf(rid.key())));
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

    /** Travas e épocas por banco, que sobrevivem à releitura do catálogo. */
    private final ConcurrentMap<String, Database.Coordination> databaseCoordination = new ConcurrentHashMap<>();

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
            Database fresh = new Database(ns, db, backend,
                databaseCoordination.computeIfAbsent(ns + '\u0000' + db, key -> new Database.Coordination()));
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

    /** Definições de SAGA cujas ligações existem todas neste namespace. As ligações (com credenciais) ficam de fora. */
    private void appendSagas(StringBuilder sb, String ns) {
        sagas.values().stream()
            .filter(saga -> saga.links().stream().allMatch(link -> databaseLink(ns, link) != null))
            .sorted(java.util.Comparator.comparing(SagaDef::name))
            .forEach(saga -> sb.append(com.axonbase.parser.Render.stmt(
                new Statement.CreateSaga(saga.name(), saga.links()))).append(";\n"));
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

    /** A camada fria das partições. */
    public com.axonbase.core.partition.ColdTier coldTier() {
        return coldTier;
    }

    /** Cliente dos provedores de embedding (um por servidor: cache, limite de concorrência e métricas). */
    public com.axonbase.core.embedding.EmbeddingClient embeddings() {
        return embeddingClient;
    }

    /** Troca o cliente de embeddings (testes e configuração do servidor). */
    public void useEmbeddingClient(com.axonbase.core.embedding.EmbeddingClient client) {
        this.embeddingClient = client;
    }

    /**
     * Tira do plano de controle uma definição (o DROP de embedding, por exemplo). O plano só sabe substituir, então
     * sem isso o replay do reinício a recriaria.
     */
    public void removeControl(Session session, String ns, String db, ControlCommand.Kind kind, String name) {
        if (replaying.get()) {
            return;
        }
        synchronized (controlLock) {
            int removed = controlState.removeWhere(c -> c.kind() == kind && c.namespace().equals(ns)
                && c.database().equals(db) && c.name().equals(name));
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
            case PARTITIONING:
            case TTL:
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

    /** {@code true} quando este nó pode confirmar escritas: fora de cluster, ou sendo o líder. */
    public boolean acceptsWrites() {
        CommitCoordinator coordinator = commitCoordinator;
        return coordinator == null || coordinator.acceptsWrites(clusterNodeId);
    }

    /** {@code true} quando os commits passam pelo consenso. */
    public boolean clustered() {
        return commitCoordinator != null;
    }

    /** Ativa confirmação de quórum antes dos commits locais. */
    public void commitCoordinator(CommitCoordinator coordinator, String nodeId) {
        this.commitCoordinator = coordinator;
        this.clusterNodeId = nodeId;
        primaryKeyEntries.forget();
    }

    private static volatile java.util.function.Consumer<ControlCommand> replayHook;

    /** Gancho chamado depois de cada comando reexecutado do plano de controle. Só para testes. */
    public static void replayHook(java.util.function.Consumer<ControlCommand> hook) {
        replayHook = hook;
    }

    /**
     * Reconstrói catálogo e identidades reexecutando o plano de controle.
     *
     * <p>Os comandos vêm ordenados por dependência, então um índice com analyzer
     * encontra o analyzer, e um campo encontra a tabela. O flag {@code replaying}
     * evita que a reexecução grave de novo o que acabou de ler.</p>
     *
     * <p>Um comando que falha não interrompe a reconstrução: os demais são reexecutados, os usuários de arranque
     * voltam e só então a primeira falha é lançada. Sem isso um CANCEL deixava o nó sem ninguém para autenticar.</p>
     */
    private void replayControl() {
        // um restore ou um lote replicado também podem trazer um plano de controle gravado por uma versão anterior
        removeLegacyIndexDrops();
        ControlSnapshot snapshot = controlState.snapshot();
        catalogRebuilds.incrementAndGet();
        replaying.set(true);
        RuntimeException failure = null;
        try {
            namespaces.clear();
            databaseLinks.clear();
            sagas.clear();
            authCatalog.clear();
            for (ControlCommand command : snapshot.commands()) {
                try {
                    replay(command);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "falha ao reexecutar a definição " + command.kind() + " '"
                        + command.name() + "' de " + command.namespace() + "/" + command.database(), e);
                    if (failure == null) {
                        failure = e;
                    } else if (failure != e) {
                        failure.addSuppressed(e);
                    }
                }
                java.util.function.Consumer<ControlCommand> hook = replayHook;
                if (hook != null) {
                    hook.accept(command);
                }
            }
        } finally {
            authCatalog.restoreBootstrap();
            replaying.set(false);
        }
        if (failure != null) {
            throw failure;
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
        com.axonbase.core.pl.ProgramMeta.beginReplay(command.definition());
        try {
            new Executor(this).execute(query, session, null);
        } finally {
            com.axonbase.core.pl.ProgramMeta.endReplay();
        }
    }

    private final com.axonbase.core.pl.ProgramService programs = new com.axonbase.core.pl.ProgramService(this);

    /** Funções, procedures e packages AxonPL (criação, chamada, métricas). */
    public com.axonbase.core.pl.ProgramService programs() {
        return programs;
    }

    /** Banco existente ou {@code null} (não cria). */
    public Database findDatabase(String ns, String db) {
        ConcurrentMap<String, Database> dbs = namespaces.get(ns);
        return dbs == null ? null : dbs.get(db);
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
        GeoMigration.onReplicated(this, applied.batch().puts().keySet());
        fanout(applied);
    }

    /** Listener pronto para acoplar a um runtime de consenso. */
    public AppliedBatchListener appliedBatchListener() {
        return this::onReplicatedBatch;
    }

    private void markLocalOrigin(String transactionId) {
        synchronized (locallyOriginated) {
            locallyOriginated.add(transactionId);
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
            // registro de tabela particionada: ns, banco, tabela, "!p", início da partição, chave
            boolean partitioned = parts.length == 6 && parts[3].equals("!p")
                && com.axonbase.core.partition.PartitionKeys.isRecordTail(parts[5]);
            if (parts.length != 4 && !partitioned
                || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
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

    /** Liga a prioridade por tipo de carga (QoS); {@code null} desliga. */
    public void workloadGovernor(com.axonbase.core.qos.WorkloadGovernor governor) {
        this.workloadGovernor = governor;
    }

    private volatile com.axonbase.core.qos.WorkloadGovernor workloadGovernor;

    public AxonValue execute(String sql, Session session, Map<String, AxonValue> vars) {
        return execute(sql, session, vars, UnaryOperator.identity());
    }

    /** @param rewrite ajuste da árvore antes da execução; o texto continua sendo o que se classifica e audita */
    private AxonValue execute(String sql, Session session, Map<String, AxonValue> vars,
                              UnaryOperator<Query> rewrite) {
        var governor = workloadGovernor;
        if (governor == null) {
            return executeAdmitted(sql, session, vars, rewrite);
        }
        try (var admitted = governor.admit(sql, session)) {
            return executeAdmitted(sql, session, vars, rewrite);
        }
    }

    private AxonValue executeAdmitted(String sql, Session session, Map<String, AxonValue> vars,
                                      UnaryOperator<Query> rewrite) {
        if (commitCoordinator != null && !session.inTransaction() && isMutation(sql)) {
            return autocommit(session, () -> {
                var exec = new Executor(this);
                exec.rawSql = sql;
                return exec.execute(rewrite.apply(AxonQl.parse(sql)), session, vars);
            });
        }
        Query query = rewrite.apply(AxonQl.parse(sql));
        var exec = new Executor(this);
        exec.rawSql = sql;
        AxonValue result = exec.execute(query, session, vars);
        if (!query.statements().stream().allMatch(st -> st instanceof Statement.Select
            || st instanceof Statement.Explain || st instanceof Statement.Info)) {
            // só escrita precisa ir ao disco; um SELECT não pode pagar o flush do RocksDB (a escrita já está no WAL)
            backend.flush();
        }
        return result;
    }

    /**
     * A parte deste nó numa consulta espalhada entre shards (veja {@link ShardPartial}). Passa pela prioridade por tipo
     * de carga, porque disputa vaga e processador como a consulta inteira faria, e pela auditoria por IA.
     *
     * @param sql o texto recebido, que o governador classifica e a auditoria confere
     */
    public AxonValue executePartial(String sql, Statement.Select select, Session session,
                                    Map<String, AxonValue> vars) {
        var governor = workloadGovernor;
        if (governor == null) {
            return audited(sql, select, session, vars);
        }
        try (var admitted = governor.admit(sql, session)) {
            return audited(sql, select, session, vars);
        }
    }

    private AxonValue audited(String sql, Statement.Select select, Session session, Map<String, AxonValue> vars) {
        var exec = new Executor(this);
        exec.rawSql = sql;
        return exec.executePartial(select, session, vars);
    }

    /** Tentativas de um comando avulso no cluster quando outro confirma antes sobre as mesmas chaves. */
    private static final int AUTOCOMMIT_ATTEMPTS = 100;

    /**
     * Executa um comando avulso numa transação implícita de cluster. Um conflito na confirmação não é um erro do
     * cliente: nada foi gravado, então o comando é refeito sobre o estado novo, como no nó único, onde ele não
     * enxergaria conflito. Transações explícitas devolvem o conflito para quem as abriu, e também o comando que
     * fez uma chamada para fora do banco ({@link ExternalCalls}).
     */
    private <T> T autocommit(Session session, java.util.function.Supplier<T> work) {
        // durante uma transferência de liderança a escrita espera aqui, antes de executar qualquer coisa
        try (var admitted = commitCoordinator.admitWrite()) {
            return autocommitAdmitted(session, work);
        }
    }

    private <T> T autocommitAdmitted(Session session, java.util.function.Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            beginSession(session);
            boolean committing = false;
            long outbound = ExternalCalls.count();
            try {
                T result = work.get();
                committing = true;
                commitSession(session);
                return result;
            } catch (VersionConflictException conflict) {
                if (session.inTransaction()) {
                    cancelSession(session);
                }
                // Um comando que chamou para fora não é refeito: a chamada aconteceria de novo.
                if (!committing || attempt >= AUTOCOMMIT_ATTEMPTS || ExternalCalls.count() != outbound) {
                    throw conflict;
                }
                // Espera curta e sorteada para os concorrentes não voltarem todos juntos.
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.ThreadLocalRandom.current()
                    .nextLong(1_000_000L * Math.min(attempt, 10)));
            } catch (com.axonbase.core.cluster.NotLeaderException moved) {
                if (session.inTransaction()) {
                    cancelSession(session);
                }
                // O redirecionamento faria o cliente repetir, e a chamada para fora aconteceria de novo.
                if (ExternalCalls.count() != outbound) {
                    throw new AxonError(-32000, Messages.get("txn_external_not_retried", moved.getMessage()), moved);
                }
                throw moved;
            } catch (RuntimeException e) {
                if (session.inTransaction()) {
                    cancelSession(session);
                }
                throw e;
            }
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
        if (statement instanceof Statement.Partition partition) {
            return partition.command().readOnly();
        }
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
     * Gera um dump AxonQL do banco: o que {@link #importDatabase} recebe recria analyzers, embeddings, tabelas,
     * índices, partições, programas, registros, arestas, chave-valor, campos, eventos, PERMISSIONS e Data Rules.
     *
     * <p>Campos e eventos vêm depois dos dados: a importação repõe as linhas como estavam, sem DEFAULT, ASSERT nem
     * REFERENCES sobre o que já existia, e nenhum evento dispara de novo. PERMISSIONS e Data Rules também vêm depois
     * dos dados, para não barrarem a própria importação. As definições de SAGA cujas ligações existem vão no fim.
     * Usuários, senhas, tokens, acessos, auditorias e database links ficam fora do dump.</p>
     */
    public String exportDatabase(String ns, String db) {
        ConcurrentMap<String, Database> databases = namespaces.get(ns);
        Database database = databases == null ? null : databases.get(db);
        if (database == null) {
            throw AxonError.internal(Messages.get("stmt_database_export_missing", ns, db));
        }
        StringBuilder sb = new StringBuilder();
        // antes das tabelas: um índice SEARCH só é aceito com o analyzer já definido
        for (Catalog.AnalyzerDef analyzer : database.catalog().analyzers()) {
            sb.append(com.axonbase.parser.Render.stmt(new Statement.DefineAnalyzer(analyzer.name(),
                analyzer.lowercase(), analyzer.stopwords(), analyzer.stemming()))).append(";\n");
        }
        for (Catalog.EmbeddingDef embedding : database.catalog().embeddings()) {
            sb.append(com.axonbase.parser.Render.stmt(new Statement.DefineEmbedding(embedding.name(), "http",
                embedding.url(), embedding.model(), embedding.dimension(), embedding.keyEnv(),
                embedding.timeoutSeconds(), embedding.batch(), embedding.documentPrefix(), embedding.queryPrefix(),
                embedding.sendDimension(), embedding.authHeader()))).append(";\n");
        }
        for (Catalog.TableDef def : database.catalog().tables()) {
            appendTable(sb, def);
            for (Catalog.IndexDef ix : def.indexes().values()) {
                sb.append(com.axonbase.parser.Render.stmt(new Statement.DefineIndex(ix.name(), def.name(),
                    ix.columns(), ix.unique(), ix.count(), ix.searchAnalyzer(), ix.geo(), ix.columnar(),
                    ix.vectorDimension(), ix.vectorDistance(), ix.hnswM(), ix.hnswEfc(), ix.hnswEfs())))
                    .append(";\n");
            }
            appendTimeData(sb, database, def);
        }
        for (com.axonbase.core.pl.ProgramDef program : database.programs().all()) {
            if (!program.broken()) {
                // sem o cabeçalho de metadados: a importação cria o objeto como novo
                String text = program.definition();
                sb.append(text.startsWith("-- axonpl ") ? text.substring(text.indexOf('\n') + 1) : text).append(";\n");
            }
        }
        String prefix = ns + "\u0000" + db + "\u0000";
        for (String key : database.records().keysWithPrefix(prefix)) {
            // registros de tabelas comuns e de partições locais; índices e demais chaves ficam de fora
            com.axonbase.core.partition.PartitionKeys.StoredRecord stored =
                com.axonbase.core.partition.PartitionKeys.record(key.substring(prefix.length()));
            if (stored != null) {
                database.records().get(key)
                    .ifPresent(raw -> appendCreate(sb, stored.table(), stored.recordKey(), raw));
            }
        }
        exportColdRecords(sb, database);
        appendEdges(sb, database);
        appendKeyValues(sb, ns, db);
        appendFields(sb, database);
        appendEvents(sb, database);
        appendPermissions(sb, database);
        appendDataRules(sb, database);
        appendSagas(sb, ns);
        return sb.toString();
    }

    /** Um RELATE por aresta, lido da chave de saída ({@code E|ns|db|out|tabela|chave|tipo|tabela|chave}). */
    private static void appendEdges(StringBuilder sb, Database database) {
        String prefix = "E|" + database.ns() + "|" + database.db() + "|out|";
        for (String key : database.records().keysWithPrefix(prefix)) {
            String[] parts = key.substring(prefix.length()).split("\\|", -1);
            if (parts.length != 5) {
                // uma chave com '|' não deixa saber onde a aresta começa e termina: melhor falhar que perdê-la
                throw AxonError.internal(Messages.get("stmt_export_edge_unreadable", key));
            }
            sb.append("RELATE ").append(endpoint(key, parts[0], parts[1])).append("->").append(name(parts[2]))
                .append("->").append(endpoint(key, parts[3], parts[4]));
            appendEdgeProperties(sb, key, database.records().get(key).orElse(null));
            sb.append(";\n");
        }
    }

    /** O valor da chave da aresta é o objeto de propriedades dela (vazio quando não há): volta no SET do RELATE. */
    private static void appendEdgeProperties(StringBuilder sb, String edge, byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        AxonValue properties = AxonJson.decode(value);
        if (!properties.isObject()) {
            throw AxonError.internal(Messages.get("stmt_export_edge_unreadable", edge));
        }
        String separator = " SET ";
        for (Map.Entry<String, AxonValue> property : properties.asObject().entrySet()) {
            if (!PLAIN_NAME.matcher(property.getKey()).matches()) {
                throw AxonError.internal(Messages.get("stmt_export_edge_unreadable", edge));
            }
            sb.append(separator).append(property.getKey()).append(" = ")
                .append(com.axonbase.parser.Render.value(property.getValue()));
            separator = ", ";
        }
    }

    private static String endpoint(String edge, String table, String token) {
        if (token.startsWith("s")) {
            return name(table) + ":" + AxonJson.write(AxonValue.str(token.substring(1)));
        }
        if (token.startsWith("n")) {
            return name(table) + ":" + com.axonbase.parser.Render.numberKey(
                numberKeyValue(new java.math.BigDecimal(token.substring(1)), ""));
        }
        throw AxonError.internal(Messages.get("stmt_export_edge_unreadable", edge));
    }

    static String name(String name) {
        return PLAIN_NAME.matcher(name).matches() ? name : AxonJson.write(AxonValue.str(name));
    }

    /** As entradas do chave-valor, com o prazo que ainda resta; as vencidas ficam de fora. */
    private void appendKeyValues(StringBuilder sb, String ns, String db) {
        String prefix = kvPrefix(ns, db);
        long now = Instant.now().getEpochSecond();
        for (String key : backend.keysWithPrefix(prefix)) {
            String name = key.substring(prefix.length());
            Optional<byte[]> raw = name.startsWith("ttl|") ? Optional.empty() : backend.get(key);
            if (raw.isEmpty()) {
                continue;
            }
            Long remaining = backend.get(kvTtlKey(ns, db, name)).map(expiry -> remainingSeconds(expiry, now))
                .orElse(null);
            if (remaining != null && remaining <= 0) {
                continue;
            }
            sb.append("RETURN kv::set(").append(AxonJson.write(AxonValue.str(name))).append(", ")
                .append(com.axonbase.parser.Render.value(decodeKv(raw.get())));
            if (remaining != null) {
                sb.append(", ").append(remaining);
            }
            sb.append(");\n");
        }
    }

    private static Long remainingSeconds(byte[] expiry, long now) {
        try {
            return Long.parseLong(new String(expiry, StandardCharsets.UTF_8)) - now;
        } catch (NumberFormatException corrupted) {
            return null; // como na leitura: metadado corrompido não vence a entrada
        }
    }

    /**
     * Um DEFINE TABLE com as PERMISSIONS, para cada tabela que as tem. Redefinir a tabela mantém campos, índices,
     * restrições e eventos.
     */
    private static void appendPermissions(StringBuilder sb, Database database) {
        for (Catalog.TableDef def : database.catalog().tables()) {
            Statement.Permissions perms = def.permissions();
            if (perms == null || perms.select() == null && perms.create() == null && perms.update() == null
                && perms.delete() == null) {
                continue;
            }
            String text = com.axonbase.parser.Render.stmt(new Statement.DefineTable(def.name(), def.schemafull(),
                false, perms, def.shardBy()));
            if (def.drop()) {
                // o parser lê DROP antes do tipo de esquema
                String head = "DEFINE TABLE " + def.name();
                text = head + " DROP" + text.substring(head.length());
            }
            sb.append(text).append(";\n");
        }
    }

    /** As Data Rules do banco. Importá-las exige administrador, como criá-las. */
    private static void appendDataRules(StringBuilder sb, Database database) {
        for (Catalog.DataRuleDef rule : database.catalog().dataRules()) {
            sb.append(com.axonbase.parser.Render.stmt(new Statement.CreateDataRule(rule.name(), rule.predicate(),
                rule.maskPatterns()))).append(";\n");
        }
    }

    /** A tabela com as opções dela; as PERMISSIONS vão depois dos dados. */
    private static void appendTable(StringBuilder sb, Catalog.TableDef def) {
        sb.append("DEFINE TABLE ").append(def.name()).append(def.schemafull() ? " SCHEMAFULL" : " SCHEMALESS");
        if (def.drop()) {
            sb.append(" DROP");
        }
        if (def.shardBy() != null) {
            sb.append(" SHARD BY ").append(def.shardBy());
        }
        sb.append(";\n");
    }

    private static void appendFields(StringBuilder sb, Database database) {
        for (Catalog.TableDef def : database.catalog().tables()) {
            appendConstraints(sb, def);
            for (Catalog.FieldDef field : def.fields().values()) {
                if (constrained(field)) {
                    continue;
                }
                sb.append(com.axonbase.parser.Render.stmt(new Statement.DefineField(field.name(), def.name(),
                    field.type(), field.assertExpr(), field.readonly(), null, field.defaultExpr(), field.references(),
                    field.embeddingName(), field.embeddingFrom()))).append(";\n");
            }
        }
    }

    private static boolean constrained(Catalog.FieldDef field) {
        return field.notNull() || field.primaryKey();
    }

    /**
     * NOT NULL, PRIMARY KEY e CHECK de tabela só se escrevem num CREATE TABLE. Vem depois dos dados; o DEFINE TABLE em
     * seguida repõe as opções que o CREATE TABLE não leva.
     */
    private static void appendConstraints(StringBuilder sb, Catalog.TableDef def) {
        List<Statement.ColumnDef> columns = new ArrayList<>();
        if (def.keyedById()) {
            columns.add(new Statement.ColumnDef("id", "string", true, false, null, null, null));
        }
        def.fields().values().stream().filter(Datastore::constrained)
            .sorted(java.util.Comparator.comparing(Catalog.FieldDef::name))
            .forEach(field -> columns.add(new Statement.ColumnDef(field.name(),
                field.type() == null ? "any" : field.type(), field.primaryKey(), field.notNull(), field.defaultExpr(),
                field.assertExpr(), field.references())));
        if (columns.isEmpty() && def.checks().isEmpty()) {
            return;
        }
        sb.append(com.axonbase.parser.Render.stmt(new Statement.CreateTable(def.name(), false, def.schemafull(),
            columns, def.checks()))).append(";\n");
        appendTable(sb, def);
    }

    private static void appendEvents(StringBuilder sb, Database database) {
        for (Catalog.TableDef def : database.catalog().tables()) {
            for (Catalog.EventDef event : def.events().values()) {
                com.axonbase.parser.ast.Expr when = event.whenExpr() != null ? event.whenExpr()
                    : new com.axonbase.parser.ast.Expr.Literal(AxonValue.bool(true));
                sb.append(com.axonbase.parser.Render.stmt(new Statement.DefineEvent(event.name(), def.name(), when,
                    event.then()))).append(";\n");
            }
        }
    }

    /** As linhas das partições que estão na camada fria entram no dump lidas do objeto. */
    private void exportColdRecords(StringBuilder sb, Database database) {
        for (String table : database.catalog().partitionedTables().keySet()) {
            for (var partition : com.axonbase.core.partition.PartitionStore.list(database, database.records(), table)) {
                if (partition.coldData()) {
                    coldTier.records().scan(database, table, partition, "", (recordKey, raw) -> {
                        appendCreate(sb, table, recordKey, raw);
                        return true;
                    });
                }
            }
        }
    }

    private void appendCreate(StringBuilder sb, String table, String keyStr, byte[] raw) {
        AxonValue record = AxonJson.decode(raw);
        if (!record.isObject()) {
            return;
        }
        // remove o campo id serializado (string) e emite CREATE <tabela>:<literal>. A identidade vem da chave física,
        // então um registro que perdeu o campo id (rc1, UPDATE CONTENT) também sai no dump
        Map<String, AxonValue> m = new java.util.LinkedHashMap<>(record.asObject());
        AxonValue id = m.remove("id");
        sb.append("CREATE ").append(table).append(":").append(literalKey(table, keyStr, id))
            .append(" CONTENT ").append(com.axonbase.parser.Render.value(AxonValue.object(m))).append(";\n");
    }

    /** A definição de partição e de TTL da tabela, depois dos campos e índices dela e antes dos dados. */
    private static void appendTimeData(StringBuilder sb, Database database, Catalog.TableDef def) {
        var partition = database.catalog().partitioning(def.name());
        if (partition != null) {
            sb.append(com.axonbase.parser.Render.stmt(new Statement.Partition(
                new com.axonbase.parser.ast.PartitionCommand.Define(def.name(), partition.field(),
                    partition.intervalMillis(), partition.retentionMillis(), partition.coldAfterMillis()))))
                .append(";\n");
        }
        var ttl = database.catalog().ttl(def.name());
        if (ttl != null) {
            sb.append(com.axonbase.parser.Render.stmt(new Statement.Partition(
                new com.axonbase.parser.ast.PartitionCommand.DefineTtl(def.name(), ttl.field(), ttl.afterMillis()))))
                .append(";\n");
        }
    }

    /** Converte a keyString de storage ('n1', 'sAna') em literal AxonQL. */
    private static String literalKey(String table, String keyStr, AxonValue id) {
        if (keyStr.startsWith("s")) {
            return AxonJson.write(AxonValue.str(keyStr.substring(1)));
        }
        if (keyStr.startsWith("n")) {
            String prefix = table + ":";
            String written = id != null && id.isString() && id.asString().startsWith(prefix)
                ? id.asString().substring(prefix.length()) : "";
            return com.axonbase.parser.Render.numberKey(
                numberKeyValue(new java.math.BigDecimal(keyStr.substring(1)), written));
        }
        return keyStr;
    }

    /**
     * A chave física só guarda o valor do número. O tipo (inteiro, real ou decimal) sai do texto do id, que a
     * importação precisa reproduzir: {@code t:3} e {@code t:3.0} são o mesmo registro com ids escritos de modo
     * diferente.
     */
    private static AxonValue numberKeyValue(java.math.BigDecimal number, String written) {
        try {
            long whole = Long.parseLong(written);
            if (java.math.BigDecimal.valueOf(whole).compareTo(number) == 0) {
                return AxonValue.num(whole);
            }
        } catch (NumberFormatException notWhole) {
            // segue para as outras formas
        }
        try {
            double real = Double.parseDouble(written);
            if (Double.toString(real).equals(written)) {
                // o id escreve como real também o decimal: se o real não guarda o valor exato, era um decimal
                return java.math.BigDecimal.valueOf(real).compareTo(number) == 0
                    ? AxonValue.num(real) : AxonValue.num(number);
            }
        } catch (NumberFormatException notReal) {
            // segue para as outras formas
        }
        try {
            return AxonValue.num(number.longValueExact());
        } catch (ArithmeticException notLong) {
            return AxonValue.num(number);
        }
    }

    /** Restaura um dump AxonQL executando-o. Uso embutido: sem identidade, nada é restringido. */
    public void importDatabase(String ns, String db, String dump) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        importDatabase(s, dump);
    }

    /**
     * Restaura um dump no namespace/banco da sessão, com a identidade e o teto de papel dela: papel, escopo e Data
     * Rules valem comando a comando, como no {@code /sql}. Quem atende requisições deve usar esta forma. A sessão
     * recebida não é alterada.
     *
     * <p>Tudo roda numa transação só: ou entra inteiro, ou o banco fica como estava (dados, definições, arestas e
     * chave-valor), também quando um comando é recusado por falta de permissão. As linhas voltam como foram escritas:
     * um registro existente é substituído e as restrições de tabela não são conferidas.</p>
     *
     * <p>O limite é o teto de uma entrada do log Raft ({@code AXON_IMPORT_MAX_MB} o reduz, ou o aumenta num nó fora de
     * cluster). Vale para o arquivo e para o que a transação acumula, índices incluídos.</p>
     */
    public void importDatabase(Session session, String dump) {
        long limit = importLimitBytes();
        requireImportSize(dump.getBytes(StandardCharsets.UTF_8).length, limit);
        Session s = session.restoringCopy();
        Supplier<AxonValue> work = () -> {
            // a cada tentativa: no cluster um conflito refaz a importação inteira
            s.trackImportedTables();
            AxonValue result = execute(dump, s, null, query -> {
                refuseTransactionControl(query);
                return LegacyDump.restoreTypes(query);
            });
            requireImportSize(s.tx().stagedBytes(), limit);
            return result;
        };
        if (commitCoordinator != null) {
            autocommit(s, work);
            return;
        }
        beginSession(s);
        try {
            work.get();
            commitSession(s);
            backend.flush();
        } finally {
            if (s.inTransaction()) {
                cancelSession(s);
            }
        }
    }

    private long importLimitBytes() {
        long cluster = coldTier.settings().clusterBytes();
        String configured = System.getenv("AXON_IMPORT_MAX_MB");
        if (configured == null || configured.isBlank()) {
            return cluster;
        }
        long wanted = Long.parseLong(configured.trim()) << 20;
        return commitCoordinator != null ? Math.min(wanted, cluster) : wanted;
    }

    private static void requireImportSize(long bytes, long limit) {
        if (bytes > limit) {
            throw AxonError.internal(Messages.get("stmt_import_too_large", bytes, limit));
        }
    }

    /** Um BEGIN, COMMIT ou CANCEL no meio do dump partiria a transação que torna a importação atômica. */
    private static void refuseTransactionControl(Query query) {
        for (Statement statement : query.statements()) {
            if (statement instanceof Statement.Begin || statement instanceof Statement.Commit
                || statement instanceof Statement.Cancel) {
                throw AxonError.internal(Messages.get("stmt_import_transaction_control",
                    statement.getClass().getSimpleName().toUpperCase(Locale.ROOT)));
            }
        }
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
        var coldHold = com.axonbase.core.partition.ColdBackups.beginLogical(this);
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
        coldHold.complete();
        return sb.toString();
    }

    /** Presente só enquanto {@link #restoreCluster} aplica um dump: se sobrar, a base ficou pela metade. */
    private static final String RESTORE_PENDING_KEY = "__axon/restore/pending";

    /** Uma restauração de dump parou no meio nesta base (erro ou queda), e ela está incompleta. */
    public boolean restoreInterrupted() {
        return backend.get(RESTORE_PENDING_KEY).isPresent();
    }

    /** Restaura um {@link #backupCluster()}: limpa tudo e reconstrói. */
    public void restoreCluster(String dump) {
        try {
            requireColdObjects(new java.io.BufferedReader(new java.io.StringReader(dump)));
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
        clearAll();
        backend.put(RESTORE_PENDING_KEY, new byte[] {1});
        backend.flush();
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
            rebuildRestoredPrimaryKeys();
        }
        backend.flush();
        backend.delete(RESTORE_PENDING_KEY);
        backend.flush();
        return applied;
    }

    /** Resultado de {@link #restoreFile}: chaves aplicadas, segmentos e marca d'água final. */
    public record RestoreResult(long restoredKeys, int segments, long version, String type) {
    }

    /** Chamado por {@link #backupTo} depois de definir tipo e marca d'água e antes do primeiro byte. */
    @FunctionalInterface
    public interface BackupStart {
        void started(boolean incremental, long version);
    }

    /** Escreve um backup v2 sem identificação de nó (testes e uso embutido). */
    public boolean backupTo(java.io.OutputStream out, long since) throws java.io.IOException {
        return backupTo(out, since, "local", null);
    }

    /**
     * Escreve o backup em streaming no formato {@code .axbak} v2 (um segmento com manifesto, contagens e
     * SHA-256), com memória constante. Num backend {@link VersionedKvBackend} a varredura vem de
     * {@link VersionedKvBackend#snapshotScan}: um único ponto no tempo, valores lidos do próprio iterador.
     * A marca d'água gravada no manifesto é lida ANTES da varredura, então o próximo incremental
     * ({@code since} igual a ela) nunca perde um lote.
     *
     * @param since versão a partir da qual incluir chaves alteradas ({@code <= 0} faz backup completo);
     *              backends sem versão sempre fazem backup completo
     * @return {@code true} se o backup escrito é incremental
     * @throws BackupFormatException {@code BACKUP_SINCE_AHEAD} (409) se {@code since} passa da marca atual
     */
    public boolean backupTo(java.io.OutputStream out, long since, String nodeId, BackupStart start)
        throws java.io.IOException {
        VersionedKvBackend versioned = backend instanceof VersionedKvBackend v ? v : null;
        boolean incremental = since > 0 && versioned != null;
        long mark = versioned == null ? 0L : versioned.currentVersion();
        if (incremental && since > mark) {
            throw new BackupFormatException(BackupFormatException.SINCE_AHEAD, 409,
                Messages.get("backup_since_ahead", since, mark));
        }
        if (start != null) {
            start.started(incremental, mark);
        }
        String engine = engineVersion != null ? engineVersion : Datastore.class.getPackage().getImplementationVersion();
        BackupFormat.SegmentWriter w = new BackupFormat.SegmentWriter(out, !incremental, since, mark, nodeId,
            engine == null ? "dev" : engine);
        // os objetos frios não são copiados: o backup os referencia e os bloqueia
        var coldHold = com.axonbase.core.partition.ColdBackups.beginLogical(this);
        w.ctrl(com.axonbase.core.control.CatalogCodec.encode(controlState.snapshot())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (versioned != null) {
            versioned.snapshotScan(incremental ? since : 0, (key, value, version) -> {
                w.put(key, value);
                return true;
            }, incremental ? key -> {
                w.delete(key);
                return true;
            } : null);
        } else {
            java.io.IOException[] failure = new java.io.IOException[1];
            backend.scanEntries("", (key, value) -> {
                try {
                    w.put(key, value);
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
        coldHold.complete(); // antes do rodapé: sem bloqueio o arquivo não fica válido
        w.finish();
        return incremental;
    }

    /**
     * Restaura um arquivo de backup já gravado em disco. v2: passo 1 valida tudo (segmentos, checksums,
     * cadeia) sem tocar na base; só então o passo 2 limpa e aplica os segmentos em ordem, e o {@code ctrl}
     * do último segmento prevalece. v1 (legado, sem as proteções) segue o caminho antigo.
     */
    /** O backend RocksDB, quando o armazenamento é este: só nele existe backup físico. */
    public java.util.Optional<com.axonbase.core.storage.RocksDbBackend> rocksBackend() {
        return backend instanceof com.axonbase.core.storage.RocksDbBackend rocks
            ? java.util.Optional.of(rocks) : java.util.Optional.empty();
    }

    /**
     * Refaz o estado em memória (plano de controle, catálogos de bancos e de usuários) a partir do que há no
     * backend. Chamar depois de trocar a base inteira por um restore físico, como o construtor faz ao abrir.
     */
    public void reloadFromBackend() {
        synchronized (controlLock) {
            controlState.restore(controlStore.load());
            repairControl();
            replayControl();
            registerLegacyEdgeKinds();
            rebuildRestoredPrimaryKeys();
        }
    }

    public RestoreResult restoreFile(java.nio.file.Path file) throws java.io.IOException {
        if (!BackupFormat.isV2(file)) {
            try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(file,
                java.nio.charset.StandardCharsets.UTF_8)) {
                requireColdObjects(reader);
            }
            try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(file,
                java.nio.charset.StandardCharsets.UTF_8)) {
                return new RestoreResult(restoreCluster(reader), 1, 0L, "legacy");
            }
        }
        List<BackupFormat.Segment> segments = BackupFormat.validate(file);
        requireColdObjects(file);
        clearAll();
        // como no formato antigo: se a aplicação parar no meio, a base fica marcada
        backend.put(RESTORE_PENDING_KEY, new byte[] {1});
        backend.flush();
        byte[][] lastCtrl = new byte[1][];
        long[] applied = new long[1];
        BackupFormat.apply(file, new BackupFormat.LineSink() {
            @Override
            public void ctrl(byte[] snapshot) {
                lastCtrl[0] = snapshot;
            }

            @Override
            public void put(String key, byte[] value) {
                backend.put(key, value);
                applied[0]++;
            }

            @Override
            public void delete(String key) {
                backend.delete(key);
                applied[0]++;
            }
        });
        if (lastCtrl[0] != null) {
            controlState.restore(com.axonbase.core.control.CatalogCodec.decode(
                new String(lastCtrl[0], java.nio.charset.StandardCharsets.UTF_8)));
            replayControl();
            controlStore.save(controlState, backend);
            rebuildRestoredPrimaryKeys();
        }
        backend.flush();
        backend.delete(RESTORE_PENDING_KEY);
        backend.flush();
        BackupFormat.Segment last = segments.get(segments.size() - 1);
        return new RestoreResult(applied[0], segments.size(), last.version(),
            segments.size() == 1 && last.full() ? "full" : "chain");
    }

    /** O mesmo para o formato antigo, linha a linha, antes de {@link #restoreCluster} limpar a base. */
    private void requireColdObjects(java.io.BufferedReader dump) throws java.io.IOException {
        java.util.Base64.Decoder b64 = java.util.Base64.getDecoder();
        Map<String, String> cold = new java.util.HashMap<>();
        String line;
        while ((line = dump.readLine()) != null) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String key = line.substring(0, tab);
            if (key.startsWith(com.axonbase.core.partition.PartitionKeys.DESCRIPTORS)) {
                var partition = com.axonbase.core.partition.PartitionDescriptor
                    .decode(b64.decode(line.substring(tab + 1)));
                if (partition.coldData()) {
                    cold.put(key, partition.object());
                } else {
                    cold.remove(key);
                }
            } else if (DELETE_MARK.equals(key)) {
                cold.remove(new String(b64.decode(line.substring(tab + 1)),
                    java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        com.axonbase.core.partition.ColdBackups.requireObjects(this, cold.values());
    }

    /** Antes de apagar a base: os objetos frios que o backup referencia precisam existir. */
    private void requireColdObjects(java.nio.file.Path file) throws java.io.IOException {
        Map<String, String> cold = new java.util.HashMap<>();
        BackupFormat.apply(file, new BackupFormat.LineSink() {
            @Override
            public void ctrl(byte[] snapshot) {
            }

            @Override
            public void put(String key, byte[] value) {
                if (key.startsWith(com.axonbase.core.partition.PartitionKeys.DESCRIPTORS)) {
                    var partition = com.axonbase.core.partition.PartitionDescriptor.decode(value);
                    if (partition.coldData()) {
                        cold.put(key, partition.object());
                    } else {
                        cold.remove(key);
                    }
                }
            }

            @Override
            public void delete(String key) {
                cold.remove(key);
            }
        });
        com.axonbase.core.partition.ColdBackups.requireObjects(this, cold.values());
    }

    private void clearAll() {
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
        var coldHold = com.axonbase.core.partition.ColdBackups.beginLogical(this);
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
        coldHold.complete();
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
        session.txControlVersion(controlState.version());
        Database current = session.namespace() == null || session.database() == null ? null
            : findDatabase(session.namespace(), session.database());
        session.txIndexEpoch(current == null ? -1 : current.indexEpoch());
        session.tx(new Transaction(base));
    }

    public void commitSession(Session session) {
        Transaction tx = session.tx();
        if (tx == null || !tx.isOpen()) {
            throw AxonError.internal(Messages.get("txn_inactive"));
        }
        // As condições leem o que este nó já aplicou. Em cluster, a barreira vem antes delas, para que um líder recém
        // eleito (ou uma escrita sem desfecho) não as faça conferir um estado atrasado; a mesma marca vai à
        // confirmação, e qualquer mudança entre os dois passos vira conflito.
        CommitCoordinator coordinator = commitCoordinator;
        Long validated = coordinator != null && tx.hasGuards() ? beginValidation(coordinator, session) : null;
        Transaction.Held guards;
        try {
            guards = tx.enterGuards();
        } catch (VersionConflictException changed) {
            cancelSession(session); // a transação pode ter definido partições: o catálogo volta junto
            throw changed;
        }
        try (guards) {
            commitGuarded(session, tx, validated);
        } catch (VersionConflictException conflict) {
            session.writesDiscarded(); // um conflito não grava nada
            throw conflict;
        }
    }

    /** A barreira de validação do cluster; sem ela a transação não pode confirmar e é desfeita. */
    private long beginValidation(CommitCoordinator coordinator, Session session) {
        try {
            return coordinator.beginValidation();
        } catch (RuntimeException unavailable) {
            session.keepWrites(); // sem consenso, o ledger também não aceitaria a retirada dos passos
            cancelSession(session);
            throw unavailable;
        }
    }

    /** Descarta só a transação: o catálogo não mudou por causa dela, então não há o que reconstruir. */
    private void discardConflicted(Session session, Transaction tx) {
        tx.cancel();
        session.tx(null);
        session.txControlVersion(-1);
        session.discardLive();
    }

    private void commitGuarded(Session session, Transaction tx, Long validated) {
        Database current = session.namespace() == null || session.database() == null ? null
            : findDatabase(session.namespace(), session.database());
        if (current == null || session.txIndexEpoch() < 0
            || (tx.stagedWriteCount() == 0 && tx.stagedDeleteCount() == 0)) {
            commitLocked(session, tx, validated);
            return;
        }
        // o registro de índice novo toma o lado de escrita; segurar o de leitura até o fim do commit impede
        // que ele apareça entre a checagem e a gravação
        java.util.concurrent.locks.Lock read = current.indexLock().readLock();
        read.lock();
        try {
            if (current.indexEpoch() != session.txIndexEpoch()) {
                discardConflicted(session, tx);
                throw new com.axonbase.core.storage.VersionConflictException(Messages.get("txn_index_changed"));
            }
            commitLocked(session, tx, validated);
        } finally {
            read.unlock();
        }
    }

    private void commitLocked(Session session, Transaction tx, Long validated) {
        CommitCoordinator coordinator = commitCoordinator;
        if (coordinator == null) {
            tx.commit();
        } else {
            commitReplicated(coordinator, session, tx, validated);
        }
        session.tx(null);
        session.keepWrites();
        backend.flush();
        // As notificações retidas durante a transação só saem agora, depois do quórum.
        session.flushLive();
    }

    /**
     * Precondições primeiro, replicação depois, aplicação por último. Validar após o consenso acusaria conflito
     * com a própria escrita que acabou de ser aplicada ao state machine.
     *
     * <p>As travas por chave valem da validação até a aplicação: transações que tocam chaves diferentes correm
     * juntas (e dividem o fsync do log), e duas sobre a mesma chave se enfileiram, de modo que a segunda valida
     * contra o que a primeira escreveu.</p>
     */
    private void commitReplicated(CommitCoordinator coordinator, Session session, Transaction tx, Long validated) {
        try (KeyLocks.Held held = replicationLocks.lock(tx.touchedKeys())) {
            // sem condições, a barreira continua aqui, depois das travas por chave
            long validation = validated != null ? validated : beginValidation(coordinator, session);
            tx.validate();
            String transactionId = java.util.UUID.randomUUID().toString();
            // Registrar antes de confirmar: o listener do consenso precisa reconhecer o batch
            // como local para não duplicar as notificações que esta sessão já vai entregar no
            // flushLive.
            markLocalOrigin(transactionId);
            try {
                coordinator.confirm(clusterNodeId, new CommittedBatch(transactionId,
                    tx.stagedWrites(), tx.stagedDeletes()), validation);
            } catch (RuntimeException notConfirmed) {
                consumeLocalOrigin(transactionId);
                // Sem confirmação a transação morre aqui. Deixá-la aberta faria a própria
                // sessão continuar lendo escritas que o cluster nunca aceitou.
                if (!(notConfirmed instanceof VersionConflictException)) {
                    session.keepWrites(); // sem desfecho: a escrita ainda pode valer
                }
                // num conflito, commitSession executa o descarte depois de soltar as travas
                rollback(session);
                throw notConfirmed;
            }
            if (coordinator.appliesTo(backend)) {
                tx.finishApplied();
            } else {
                tx.commitValidated();
            }
        }
    }

    private final KeyLocks replicationLocks = new KeyLocks();

    private volatile String engineVersion;

    /** Versão do build que o servidor informa, gravada no manifesto dos backups. */
    public void engineVersion(String version) {
        this.engineVersion = version;
    }

    public void cancelSession(Session session) {
        try {
            rollback(session);
        } finally {
            session.writesDiscarded();
        }
    }

    /** Desfaz a transação sem executar as ações presas às escritas dela. */
    private void rollback(Session session) {
        Transaction tx = session.tx();
        long started = session.txControlVersion();
        if (tx != null) {
            tx.cancel();
            session.tx(null);
        }
        session.txControlVersion(-1);
        session.discardLive();
        // DDL e auth foram aplicados em memória para a própria sessão; se o batch não
        // confirmou, o plano de controle volta ao que está durável no storage. Reconstruir reexecuta todo o
        // plano de controle de todos os bancos, então só se faz quando houve DDL desde o começo da transação.
        synchronized (controlLock) {
            if (tx != null && started >= 0 && started == controlState.version()) {
                return;
            }
            controlState.restore(controlStore.load());
            replayControl();
        }
    }

    private final java.util.concurrent.atomic.AtomicLong catalogRebuilds = new java.util.concurrent.atomic.AtomicLong();

    /** Quantas vezes o catálogo foi reconstruído a partir do plano de controle (testes e diagnóstico). */
    public long catalogRebuilds() {
        return catalogRebuilds.get();
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
        if (commitCoordinator != null && !session.inTransaction()) {
            return autocommit(session, work);
        }
        T result = work.get();
        backend.flush();
        return result;
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
