package com.axonbase.core.partition;

import com.axonbase.core.Session;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.catalog.PartitionSpec;
import com.axonbase.core.catalog.TtlSpec;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.Executor;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.Transaction;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Trabalho de segundo plano dos dados no tempo: retenção por partição e remoção das linhas vencidas pelo TTL. Em
 * cluster só o líder age, e tudo passa por transações comuns, então chega aos seguidores e ao journal como qualquer
 * escrita.
 *
 * <p>O TTL percorre as entradas de vencimento em ordem e só até a hora atual: o custo é proporcional ao que venceu.
 * Ele remove em lotes pequenos, com pausa entre eles e um teto por rodada, para não disputar com as escritas.</p>
 */
public final class PartitionMaintenance implements AutoCloseable {

    /** Variável da sessão interna que leva o id da linha vencida ao DELETE. */
    private static final String ROW = "axon_ttl_row";
    private static final byte[] EMPTY = new byte[0];

    /**
     * @param fullScanEvery a cada quantas rodadas a fila de vencimentos é relida do começo, para pegar linhas
     *                      gravadas já vencidas
     */
    public record Options(Duration interval, int ttlBatch, int ttlMaxPerRun, Duration ttlPause, int fullScanEvery) {

        public static Options defaults() {
            return new Options(Duration.ofSeconds(10), 200, 20_000, Duration.ofMillis(5), 30);
        }

        /** Lê {@code AXON_PARTITION_MAINTENANCE_SECONDS} (0 desliga), {@code AXON_TTL_BATCH} e o teto por rodada. */
        public static Options fromEnvironment(Map<String, String> env) {
            Options d = defaults();
            return new Options(
                Duration.ofSeconds(number(env, "AXON_PARTITION_MAINTENANCE_SECONDS", d.interval().toSeconds())),
                (int) Math.max(1, number(env, "AXON_TTL_BATCH", d.ttlBatch())),
                (int) Math.max(1, number(env, "AXON_TTL_MAX_PER_RUN", d.ttlMaxPerRun())),
                d.ttlPause(), d.fullScanEvery());
        }

        private static long number(Map<String, String> env, String name, long fallback) {
            String raw = env.get(name);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Math.max(0, Long.parseLong(raw.trim()));
            } catch (NumberFormatException invalid) {
                return fallback;
            }
        }

        public Options withInterval(Duration value) {
            return new Options(value, ttlBatch, ttlMaxPerRun, ttlPause, fullScanEvery);
        }

        public Options withTtlBatch(int value) {
            return new Options(interval, value, ttlMaxPerRun, ttlPause, fullScanEvery);
        }

        public Options withTtlMaxPerRun(int value) {
            return new Options(interval, ttlBatch, value, ttlPause, fullScanEvery);
        }

        public Options withTtlPause(Duration value) {
            return new Options(interval, ttlBatch, ttlMaxPerRun, value, fullScanEvery);
        }

        public Options withFullScanEvery(int value) {
            return new Options(interval, ttlBatch, ttlMaxPerRun, ttlPause, value);
        }
    }

    /** O que uma rodada fez. */
    public record Report(int droppedPartitions, int expiredRows, int backfilledRows, int failures) {
    }

    private static final class Tally {
        int dropped;
        int expired;
        int backfilled;
        int failures;

        Report report() {
            return new Report(dropped, expired, backfilled, failures);
        }
    }

    private final Datastore ds;
    private final Options options;
    private final LongSupplier clock;
    /** Até onde a fila de vencimentos de cada tabela já foi percorrida. */
    private final Map<String, String> cursors = new ConcurrentHashMap<>();
    private long rounds;
    private volatile boolean closed;
    private ScheduledExecutorService scheduler;

    public PartitionMaintenance(Datastore ds, Options options, LongSupplier clock) {
        this.ds = ds;
        this.options = options;
        this.clock = clock;
    }

    /** Começa a rodar em segundo plano. Com intervalo zero não faz nada. */
    public synchronized PartitionMaintenance start() {
        if (scheduler != null || closed || options.interval().isZero()) {
            return this;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "axon-partition-maintenance");
            t.setDaemon(true);
            return t;
        });
        long millis = Math.max(1, options.interval().toMillis());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                log("partition_maintenance_failed", "", e);
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
        return this;
    }

    /** Uma rodada completa agora. A falha numa tabela não impede as outras. */
    public synchronized Report runOnce() {
        Tally tally = new Tally();
        if (closed || !ds.acceptsWrites()) {
            return tally.report();
        }
        if (options.fullScanEvery() > 0 && rounds++ % options.fullScanEvery() == 0) {
            cursors.clear();
        }
        long now = clock.getAsLong();
        for (Database db : ds.allDatabases()) {
            db.catalog().partitionedTables().forEach((table, spec) -> attempt(tally, db, table,
                () -> tally.dropped += retain(db, table, spec, now)));
            db.catalog().ttlTables().forEach((table, ttl) -> attempt(tally, db, table, () -> {
                tally.backfilled += backfill(db, table, ttl);
                tally.expired += expire(db, table, ttl, now);
            }));
        }
        return tally.report();
    }

    private void attempt(Tally tally, Database db, String table, Runnable work) {
        if (closed) {
            return;
        }
        try {
            work.run();
        } catch (RuntimeException e) {
            tally.failures++;
            log("partition_maintenance_table_failed", db.ns() + "." + db.db() + "." + table, e);
        }
    }

    // ---- retenção ----

    private int retain(Database db, String table, PartitionSpec spec, long now) {
        if (spec.retentionMillis() == null) {
            return 0;
        }
        long limit = now - spec.retentionMillis();
        if (PartitionStore.list(db, db.records(), table).stream().noneMatch(p -> p.end() <= limit)) {
            return 0;
        }
        return inTransaction(db, (session, tx) -> {
            PartitionChanges changes = new PartitionChanges();
            List<PartitionDescriptor> expired = PartitionStore.list(db, tx, table).stream()
                .filter(p -> p.end() <= limit).toList();
            if (!expired.isEmpty()) {
                expired.forEach(p -> changes.drop(db, table, p.start()));
                changes.apply(db, table, tx);
            }
            return expired.size();
        });
    }

    // ---- TTL ----

    /** Dá a entrada de vencimento às linhas que já existiam quando o TTL foi definido. */
    private int backfill(Database db, String table, TtlSpec ttl) {
        String marker = PartitionKeys.ttlBackfillKey(db.ns(), db.db(), table);
        String prefix = PartitionKeys.tablePrefix(db.ns(), db.db(), table);
        int skip = PartitionKeys.dbPrefix(db.ns(), db.db()).length();
        int total = 0;
        while (!closed && total < options.ttlMaxPerRun()) {
            var cursor = db.records().get(marker);
            if (cursor.isEmpty()) {
                return total;
            }
            String from = cursor.get().length == 0 ? prefix
                : new String(cursor.get(), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, byte[]> entries = new LinkedHashMap<>();
            List<String> seen = new ArrayList<>();
            db.records().scanEntriesFrom(prefix, from, (key, value) -> {
                PartitionKeys.StoredRecord stored = PartitionKeys.record(key.substring(skip));
                if (stored != null) {
                    seen.add(key);
                    OptionalLong expiry = PartitionedKv.expiryOf(ttl, AxonJson.decode(value));
                    if (expiry.isPresent()) {
                        entries.put(PartitionKeys.ttlKey(db.ns(), db.db(), table, expiry.getAsLong(),
                            stored.recordKey()), EMPTY);
                    }
                }
                return seen.size() < options.ttlBatch();
            });
            boolean finished = seen.size() < options.ttlBatch();
            inTransaction(db, (session, tx) -> {
                entries.forEach(tx::putBlind);
                if (finished) {
                    tx.delete(marker);
                } else {
                    tx.put(marker, (seen.get(seen.size() - 1) + "\u0000")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return 0;
            });
            total += seen.size();
            if (finished) {
                return total;
            }
            pause();
        }
        return total;
    }

    private int expire(Database db, String table, TtlSpec ttl, long now) {
        String prefix = PartitionKeys.ttlPrefix(db.ns(), db.db(), table);
        String id = db.ns() + "|" + db.db() + "|" + table;
        int examined = 0;
        int expired = 0;
        while (!closed && examined < options.ttlMaxPerRun()) {
            int limit = Math.min(options.ttlBatch(), options.ttlMaxPerRun() - examined);
            List<String> due = new ArrayList<>();
            db.records().scanEntriesFrom(prefix, cursors.getOrDefault(id, prefix), (key, value) -> {
                if (expiryIn(prefix, key) > now) {
                    return false;
                }
                due.add(key);
                return due.size() < limit;
            });
            if (due.isEmpty()) {
                break;
            }
            expired += expireBatch(db, table, ttl, prefix, due, now);
            examined += due.size();
            cursors.put(id, due.get(due.size() - 1) + "\u0000");
            pause();
        }
        return expired;
    }

    private static long expiryIn(String prefix, String key) {
        return Long.parseLong(key.substring(prefix.length(), key.indexOf('\u0000', prefix.length())));
    }

    private int expireBatch(Database db, String table, TtlSpec ttl, String prefix, List<String> due, long now) {
        return inTransaction(db, (session, tx) -> {
            KvBackend view = PartitionedKv.wrap(db, tx, new PartitionContext(), ds.coldTier().records());
            List<AxonValue> rows = new ArrayList<>();
            for (String entry : due) {
                String recordKey = entry.substring(entry.indexOf('\u0000', prefix.length()) + 1);
                if (view instanceof PartitionedKv partitions && partitions.readOnlyRow(table, recordKey)) {
                    continue; // partição fria ou em mudança: a entrada fica para quando ela voltar
                }
                var raw = view.get(PartitionKeys.tablePrefix(db.ns(), db.db(), table) + recordKey);
                AxonValue key = keyValue(recordKey);
                if (raw.isPresent() && key != null) {
                    OptionalLong expiry = PartitionedKv.expiryOf(ttl, AxonJson.decode(raw.get()));
                    if (expiry.isPresent() && expiry.getAsLong() <= now) {
                        rows.add(AxonValue.record(table, key));
                    }
                }
                tx.delete(entry);
            }
            for (AxonValue row : rows) {
                Statement delete = new Statement.Delete(new Expr.Param(ROW), null, null);
                new Executor(ds).execute(new Query(List.of(delete)), session, Map.of(ROW, row));
            }
            return rows.size();
        });
    }

    /** O id a partir da chave do registro; {@code null} para um formato que o DELETE não sabe endereçar. */
    private static AxonValue keyValue(String recordKey) {
        String body = recordKey.substring(1);
        try {
            return switch (recordKey.charAt(0)) {
                case 'n' -> AxonValue.num(new java.math.BigDecimal(body));
                case 's' -> AxonValue.str(body);
                default -> AxonValue.uuid(UUID.fromString(body));
            };
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    // ---- apoio ----

    private interface Work {
        int run(Session session, Transaction tx);
    }

    /** Roda o trabalho numa transação própria; um conflito desfaz tudo e a próxima rodada tenta de novo. */
    private int inTransaction(Database db, Work work) {
        Session session = new Session(db.ns(), db.db());
        ds.beginSession(session);
        try {
            int result = work.run(session, session.tx());
            ds.commitSession(session);
            return result;
        } finally {
            if (session.inTransaction()) {
                ds.cancelSession(session);
            }
        }
    }

    private void pause() {
        long millis = options.ttlPause().toMillis();
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closed = true;
            }
        }
    }

    private static void log(String event, String target, RuntimeException e) {
        System.err.println("{\"event\":\"" + event + "\",\"target\":\"" + target + "\",\"error\":\""
            + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}");
    }

    /** Para a thread e espera a rodada em curso, para ninguém usar o armazenamento depois de fechado. */
    @Override
    public void close() {
        closed = true;
        ScheduledExecutorService running;
        synchronized (this) {
            running = scheduler;
            scheduler = null;
        }
        if (running != null) {
            running.shutdown();
            try {
                running.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
