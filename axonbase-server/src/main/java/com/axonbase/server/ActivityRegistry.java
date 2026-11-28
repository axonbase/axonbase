package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.QueryContext;
import com.axonbase.value.AxonValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * O que o servidor está fazendo agora e o que foi lento: consultas em execução (com cancelamento), consultas lentas
 * recentes, agregação por fingerprint e sessões WebSocket abertas. Tudo em memória, limitado e por nó.
 */
public final class ActivityRegistry {

    private static final double[] BUCKETS = {0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30};
    private static final int MAX_FINGERPRINTS = 500;
    private static final int EVICTION_BATCH = MAX_FINGERPRINTS / 10;

    private volatile ActivityConfig config;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, Stat> stats = new ConcurrentHashMap<>();
    private final ArrayDeque<SlowEntry> slow = new ArrayDeque<>();
    private final Map<String, WsSession> wsSessions = new ConcurrentHashMap<>();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong slowTotal = new AtomicLong();

    public ActivityRegistry(ActivityConfig config) {
        this.config = config;
    }

    /** Nome do usuário autenticado na sessão, ou {@code null} (anônimo). */
    public static String userOf(Session session) {
        AxonValue auth = session == null ? null : session.auth();
        if (auth != null && auth.isObject() && auth.asObject().get("id") != null) {
            return auth.asObject().get("id").asString();
        }
        return null;
    }

    public ActivityConfig config() {
        return config;
    }

    /** Troca os parâmetros em tempo de execução (testes e ajustes operacionais). */
    public void configure(ActivityConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------------
    // consultas
    // ------------------------------------------------------------------

    /** Uma instrução em execução. */
    public static final class Running {
        private final QueryContext.Handle handle;
        private final String user;
        private final String ns;
        private final String db;
        private final String sql;
        private final String fingerprint;
        private final String protocol;
        private final String remote;
        private final String sessionId;
        private final Object owner;

        Running(QueryContext.Handle handle, String user, String ns, String db, String sql, String fingerprint,
                String protocol, String remote, String sessionId, Object owner) {
            this.handle = handle;
            this.owner = owner;
            this.user = user;
            this.ns = ns;
            this.db = db;
            this.sql = sql;
            this.fingerprint = fingerprint;
            this.protocol = protocol;
            this.remote = remote;
            this.sessionId = sessionId;
        }

        public QueryContext.Handle handle() {
            return handle;
        }

        public String id() {
            return handle.id();
        }

        public String user() {
            return user;
        }

        public String ns() {
            return ns;
        }

        public String db() {
            return db;
        }

        /** Texto truncado, ou {@code null} quando a captura de SQL está desligada. */
        public String sql() {
            return sql;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public String protocol() {
            return protocol;
        }

        public String remote() {
            return remote;
        }

        public String sessionId() {
            return sessionId;
        }

        public long startedAtMs() {
            return handle.startedAtMs();
        }

        public long elapsedMs() {
            return handle.elapsedNanos() / 1_000_000L;
        }
    }

    /** Registra o início de uma instrução. A thread que vai executar deve chamar {@code QueryContext.attach(run.handle())}. */
    public Running begin(String user, String ns, String db, String sql, String protocol, String remote, String sessionId,
                         Object owner) {
        String text = config.captureSql() && sql != null
            ? (sql.length() > ActivityConfig.MAX_SQL_CHARS ? sql.substring(0, ActivityConfig.MAX_SQL_CHARS) : sql) : null;
        Running r = new Running(new QueryContext.Handle(FastIds.uuid()), user, ns, db, text,
            Fingerprint.of(sql), protocol, remote, sessionId, owner);
        running.put(r.id(), r);
        return r;
    }

    /** Fecha a instrução, atualiza as estatísticas e guarda as lentas. */
    public void end(Running r, boolean error, boolean wasCancelled) {
        running.remove(r.id());
        long nanos = r.handle().elapsedNanos();
        if (wasCancelled) {
            cancelled.incrementAndGet();
        }
        record(r, nanos, error);
        if (nanos / 1_000_000L >= config.slowQueryMs()) {
            slowTotal.incrementAndGet();
            SlowEntry entry = new SlowEntry(r.id(), System.currentTimeMillis(), nanos / 1_000_000L, r.user(), r.ns(), r.db(),
                r.protocol(), r.fingerprint(), r.sql(), error, wasCancelled);
            synchronized (slow) {
                slow.addFirst(entry);
                while (slow.size() > config.slowBuffer()) {
                    slow.removeLast();
                }
            }
            // Só o fingerprint vai para o log (e daí para o Loki): o texto com valores nunca sai do servidor por aqui.
            AxonLog.log("warn", "slow_query", "consulta lenta", Map.of("duration_ms", nanos / 1_000_000L,
                "user", r.user() == null ? "" : r.user(), "ns", r.ns() == null ? "" : r.ns(),
                "db", r.db() == null ? "" : r.db(), "fingerprint", r.fingerprint(), "protocol", r.protocol(),
                "cancelled", wasCancelled, "error", error));
        }
    }

    private void record(Running r, long nanos, boolean error) {
        statFor(r.fingerprint()).add(nanos, error, r.ns(), r.db());
    }

    /**
     * A estatística do fingerprint, criada se preciso. Só um fingerprint novo entra na trava: sem ela, threads
     * concorrentes descartariam a mesma entrada, cada uma incluiria a sua, e o mapa cresceria sem limite.
     */
    private Stat statFor(String fingerprint) {
        Stat known = stats.get(fingerprint);
        if (known != null) {
            return known;
        }
        synchronized (stats) {
            Stat current = stats.get(fingerprint);
            if (current != null) {
                return current;
            }
            if (stats.size() >= MAX_FINGERPRINTS) {
                evictOldest();
            }
            Stat created = new Stat(fingerprint);
            stats.put(fingerprint, created);
            return created;
        }
    }

    /** Descarta as entradas vistas há mais tempo, em lote, para o mapa cheio não ser ordenado a cada instrução nova. */
    private void evictOldest() {
        // a hora é copiada antes de ordenar: ela muda enquanto outras threads somam amostras
        record Seen(String fingerprint, long atMs) {}
        stats.values().stream()
            .map(s -> new Seen(s.fingerprint(), s.lastSeenMs()))
            .sorted(Comparator.comparingLong(Seen::atMs))
            .limit(EVICTION_BATCH)
            .toList()
            .forEach(s -> stats.remove(s.fingerprint()));
    }

    /** Cancela uma consulta pelo id. {@code false} se ela já terminou. */
    public boolean cancel(String id, String by) {
        Running r = running.get(id);
        if (r == null) {
            return false;
        }
        r.handle().cancel(by);
        return true;
    }

    /** Cancela o que o dono (a {@code Session} do motor) estiver executando: no timeout a consulta passa a parar de verdade. */
    public void cancelOwner(Object owner, String by) {
        if (owner == null) {
            return;
        }
        for (Running r : running.values()) {
            if (r.owner == owner) {
                r.handle().cancel(by);
            }
        }
    }

    public boolean sessionBusy(String sessionId) {
        return running.values().stream().anyMatch(r -> sessionId.equals(r.sessionId()));
    }

    public List<Running> running() {
        List<Running> out = new ArrayList<>(running.values());
        out.sort(Comparator.comparingLong(Running::startedAtMs));
        return out;
    }

    public int runningCount() {
        return running.size();
    }

    public long cancelledTotal() {
        return cancelled.get();
    }

    public long slowTotal() {
        return slowTotal.get();
    }

    // ------------------------------------------------------------------
    // lentas e agregação
    // ------------------------------------------------------------------

    /** Uma consulta que passou do limite. */
    public record SlowEntry(String id, long tsMs, long durationMs, String user, String ns, String db, String protocol,
                            String fingerprint, String sql, boolean error, boolean cancelled) {}

    public List<SlowEntry> slow(int limit, long sinceMs) {
        List<SlowEntry> out = new ArrayList<>();
        synchronized (slow) {
            for (SlowEntry e : slow) {
                if (e.tsMs() >= sinceMs) {
                    out.add(e);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Estatística por fingerprint. */
    public static final class Stat {
        private final String fingerprint;
        private long count;
        private long errors;
        private long totalNanos;
        private long maxNanos;
        private final long[] buckets = new long[BUCKETS.length + 1];
        private volatile long lastSeenMs;
        private String ns;
        private String db;

        Stat(String fingerprint) {
            this.fingerprint = fingerprint;
            // recém-criada conta como vista agora: senão sairia no próximo descarte, antes da primeira amostra
            this.lastSeenMs = System.currentTimeMillis();
        }

        synchronized void add(long nanos, boolean error, String ns, String db) {
            count++;
            if (error) {
                errors++;
            }
            totalNanos += nanos;
            maxNanos = Math.max(maxNanos, nanos);
            double s = nanos / 1_000_000_000.0;
            int slot = BUCKETS.length;
            for (int i = 0; i < BUCKETS.length; i++) {
                if (s <= BUCKETS[i]) {
                    slot = i;
                    break;
                }
            }
            buckets[slot]++;
            lastSeenMs = System.currentTimeMillis();
            this.ns = ns;
            this.db = db;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public synchronized long count() {
            return count;
        }

        public synchronized long errors() {
            return errors;
        }

        public synchronized long totalMs() {
            return totalNanos / 1_000_000L;
        }

        public synchronized long maxMs() {
            return maxNanos / 1_000_000L;
        }

        public synchronized long meanMs() {
            return count == 0 ? 0 : totalNanos / count / 1_000_000L;
        }

        /** Limite superior do balde onde cai o percentil 95 (aproximação, em milissegundos). */
        public synchronized long p95Ms() {
            long target = (long) Math.ceil(count * 0.95);
            long seen = 0;
            for (int i = 0; i < buckets.length; i++) {
                seen += buckets[i];
                if (seen >= target) {
                    return i < BUCKETS.length ? (long) (BUCKETS[i] * 1000) : maxNanos / 1_000_000L;
                }
            }
            return maxNanos / 1_000_000L;
        }

        public long lastSeenMs() {
            return lastSeenMs;
        }

        public synchronized String ns() {
            return ns;
        }

        public synchronized String db() {
            return db;
        }
    }

    /** Fingerprints ordenados por {@code total}, {@code max}, {@code count} ou {@code mean}. */
    public List<Stat> top(String orderBy, int limit) {
        Comparator<Stat> cmp = switch (orderBy == null ? "total" : orderBy) {
            case "max" -> Comparator.comparingLong(Stat::maxMs);
            case "count" -> Comparator.comparingLong(Stat::count);
            case "mean" -> Comparator.comparingLong(Stat::meanMs);
            case "p95" -> Comparator.comparingLong(Stat::p95Ms);
            default -> Comparator.comparingLong(Stat::totalMs);
        };
        return stats.values().stream().sorted(cmp.reversed()).limit(limit).toList();
    }

    // ------------------------------------------------------------------
    // sessões WebSocket
    // ------------------------------------------------------------------

    /** Uma conexão WebSocket (uma {@code Session} do motor por conexão). */
    public interface WsSession {
        String id();

        String user();

        String ns();

        String db();

        long connectedAtMs();

        long lastActivityMs();

        String remote();

        boolean inTransaction();

        long txOpenedAtMs();

        int txWrites();

        int txDeletes();

        /** Fecha a conexão (e a transação, se houver). */
        void kill(String by);

        /** Reverte a transação aberta. Só chamar com a sessão ociosa. */
        void rollback();
    }

    private final Map<Session, WsSession> byEngineSession = new java.util.concurrent.ConcurrentHashMap<>();

    /** Liga a sessão do motor à conexão WebSocket, para o despachante saber o id e o endereço de quem executa. */
    public void register(WsSession s, Session engineSession) {
        wsSessions.put(s.id(), s);
        byEngineSession.put(engineSession, s);
    }

    public String remoteOf(Session engineSession) {
        WsSession w = byEngineSession.get(engineSession);
        return w == null ? null : w.remote();
    }

    public String sessionIdOf(Session engineSession) {
        WsSession w = byEngineSession.get(engineSession);
        return w == null ? null : w.id();
    }

    public void unregister(String id, Session engineSession) {
        wsSessions.remove(id);
        byEngineSession.remove(engineSession);
    }

    public WsSession wsSession(String id) {
        return wsSessions.get(id);
    }

    public List<WsSession> wsSessions() {
        List<WsSession> out = new ArrayList<>(wsSessions.values());
        out.sort(Comparator.comparingLong(WsSession::connectedAtMs));
        return out;
    }

    public int wsCount() {
        return wsSessions.size();
    }

    public int openTransactions() {
        return (int) wsSessions.values().stream().filter(WsSession::inTransaction).count();
    }
}
