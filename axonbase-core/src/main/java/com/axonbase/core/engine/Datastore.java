package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.Transaction;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Query;
import com.axonbase.value.AxonValue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Núcleo de datos: xestión de namespaces, bancos de datos, catálogos e records.
 * É o punto de entrada que leva o plan: {@code Datastore.execute(sql, session,
 * vars)}.
 */
public final class Datastore {

    private final KvBackend backend;
    private final ConcurrentMap<String, ConcurrentMap<String, Database>> namespaces = new ConcurrentHashMap<>();
    private final LiveBus liveBus = new LiveBus();
    private final AuthCatalog authCatalog = new AuthCatalog();
    private volatile com.axonbase.core.cluster.CommitCoordinator commitCoordinator;
    private volatile String clusterNodeId;

    public Datastore(KvBackend backend) {
        this.backend = backend != null ? backend : new MemoryBackend();
    }

    public static Datastore memory() {
        return new Datastore(new MemoryBackend());
    }

    // ------------------------------------------------------------------
    // xestión de namespaces / databases
    // ------------------------------------------------------------------

    public Database ensureDatabase(String ns, String db) {
        if (ns == null || ns.isBlank() || db == null || db.isBlank()) {
            throw AxonError.internal(
                "ecué ns e db non poden estar en banco para acceder á base");
        }
        ConcurrentMap<String, Database> dbs = namespaces.computeIfAbsent(ns, k -> new ConcurrentHashMap<>());
        return dbs.computeIfAbsent(db, k -> new Database(ns, db, backend));
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
            throw AxonError.internal("não se seleccionou un namespace/database de uso");
        }
        return ensureDatabase(ns, db);
    }

    /** Barramento de live queries partilhado por todas as sessões. */
    public LiveBus liveBus() {
        return liveBus;
    }

    /** Catálogo de usuários e access methods deste servidor. */
    public AuthCatalog authCatalog() {
        return authCatalog;
    }
    /** Ativa confirmação de quórum antes de commits locais. */
    public void commitCoordinator(com.axonbase.core.cluster.CommitCoordinator coordinator, String nodeId) {
        this.commitCoordinator = coordinator; this.clusterNodeId = nodeId;
    }

    public List<String> namespaces() {
        return List.copyOf(namespaces.keySet());
    }

    public List<String> databases(String ns) {
        ConcurrentMap<String, Database> m = namespaces.get(ns);
        return m == null ? List.of() : List.copyOf(m.keySet());
    }

    // ------------------------------------------------------------------
    // Execución
    // ------------------------------------------------------------------

    public AxonValue execute(String sql, Session session, Map<String, AxonValue> vars) {
        boolean implicitClusterTransaction = commitCoordinator != null && !session.inTransaction()
            && isMutation(sql);
        if (implicitClusterTransaction) {
            beginSession(session);
        }
        try {
        Query query = AxonQl.parse(sql);
        Executor ex = new Executor(this);
        AxonValue result = ex.execute(query, session, vars);
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

    private static boolean isMutation(String sql) {
        String normalized = sql == null ? "" : sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
        return !(normalized.startsWith("SELECT") || normalized.startsWith("INFO")
            || normalized.startsWith("RETURN") || normalized.startsWith("USE ")
            || normalized.startsWith("BEGIN") || normalized.startsWith("COMMIT")
            || normalized.startsWith("CANCEL"));
    }

    // ------------------------------------------------------------------
    // Export / Import (dump AxonQL)
    // ------------------------------------------------------------------

    /**
     * Gera um dump AxonQL da base: DEFINEs do catálogo seguidos de um CREATE
     * por registro, para restauração.
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
            java.util.Optional<byte[]> raw = database.records().get(key);
            if (raw.isEmpty()) {
                continue;
            }
            com.axonbase.value.AxonValue record = com.axonbase.value.AxonJson.decode(raw.get());
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
            java.util.Map<String, com.axonbase.value.AxonValue> m =
                new java.util.LinkedHashMap<>(record.asObject());
            m.remove("id");
            String content = com.axonbase.value.AxonJson.write(
                com.axonbase.value.AxonValue.object(m));
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
    // Transações de sesión (Begin/Commit/Cancel
    // ------------------------------------------------------------------

    public void beginSession(Session session) {
        if (session.inTransaction()) {
            throw AxonError.internal("xa existe unha transación activa");
        }
        session.tx(new Transaction(requireDatabase(session).records()));
    }

    public void commitSession(Session session) {
        Transaction tx = session.tx();
        if (tx == null || !tx.isOpen()) {
            throw AxonError.internal("non hay transación activa");
        }
        var coordinator = commitCoordinator;
        if (coordinator != null) {
            coordinator.confirm(clusterNodeId, new com.axonbase.core.cluster.CommittedBatch(
                java.util.UUID.randomUUID().toString(), tx.stagedWrites(), tx.stagedDeletes()));
        }
        tx.commit();
        session.tx(null);
        requireDatabase(session).records().flush();
        // as notificações retidas durante a transação só saem agora
        session.flushLive();
    }

    public void cancelSession(Session session) {
        Transaction tx = session.tx();
        if (tx != null) {
            tx.cancel();
            session.tx(null);
        }
        session.discardLive();
    }
}
