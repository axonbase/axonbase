package com.axonbase.core.partition;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portões da E4 para partição, TTL e camada fria. Só rodam com {@code -Daxon.gate.e4=true}, numa máquina dedicada.
 *
 * <pre>
 * mvn -q -pl axonbase-core -am test -Daxon.gate.e4=true -Dtest=E4PartitionGateTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Parâmetros (propriedades {@code axon.gate.e4.*}): {@code rows} (1000000), {@code smallRows} (10000),
 * {@code maxDropMillis} (500), {@code writers} (8), {@code seconds} (30), {@code backlog} (200000),
 * {@code maxP99Increase} (0.2), {@code coldRows} (200000), {@code dir} (diretório de trabalho).
 */
@EnabledIfSystemProperty(named = "axon.gate.e4", matches = "true")
@DisplayName("Portão E4: partição, TTL e camada fria")
class E4PartitionGateTest {

    private static final Instant DAY = Instant.parse("2026-11-01T00:00:00Z");

    @TempDir
    Path tmp;

    private static long number(String name, long fallback) {
        return Long.getLong("axon.gate.e4." + name, fallback);
    }

    private static double ratio(String name, double fallback) {
        return Double.parseDouble(System.getProperty("axon.gate.e4." + name, Double.toString(fallback)));
    }

    private Path workDir(String name) throws IOException {
        String configured = System.getProperty("axon.gate.e4.dir");
        if (configured == null) {
            return tmp.resolve(name);
        }
        Files.createDirectories(Path.of(configured));
        return Files.createTempDirectory(Path.of(configured), name);
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("gate");
        s.database("e4");
        return s;
    }

    private static Datastore store(RocksDbBackend backend, ColdTier tier) {
        Datastore ds = new Datastore(backend, tier);
        ds.createDatabase("gate", "e4");
        return ds;
    }

    private static String day(int offset) {
        return "d\"" + DAY.plus(Duration.ofDays(offset)) + "\"";
    }

    /** Insere {@code rows} linhas na faixa do dia, com várias threads. */
    private static void load(Datastore ds, String table, int offset, long rows) throws Exception {
        int threads = (int) number("writers", 8);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong next = new AtomicLong();
        List<Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(pool.submit(() -> {
                Session s = session();
                for (long i = next.getAndIncrement(); i < rows; i = next.getAndIncrement()) {
                    ds.execute("CREATE " + table + " CONTENT {at: " + day(offset) + ", n: " + i
                        + ", body: \"linha de carga do portao\"}", s, null);
                }
            }));
        }
        for (Future<?> task : tasks) {
            task.get();
        }
        pool.shutdown();
    }

    private static double dropMillis(Datastore ds, int offset) {
        long from = System.nanoTime();
        ds.execute("DROP PARTITION ON TABLE events AT " + day(offset), session(), null);
        return (System.nanoTime() - from) / 1e6;
    }

    @Test
    @DisplayName("apagar uma partição de 1 milhão de linhas custa o mesmo que apagar uma pequena")
    void dropPartition_constantTime() throws Exception {
        long rows = number("rows", 1_000_000);
        long smallRows = number("smallRows", 10_000);
        try (RocksDbBackend backend = new RocksDbBackend(workDir("drop").toString(), RocksDbBackend.SyncMode.BATCH)) {
            Datastore ds = store(backend, ColdTier.NONE);
            ds.execute("DEFINE INDEX n_unico ON TABLE events COLUMNS n, at UNIQUE", session(), null);
            ds.execute("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d", session(), null);
            load(ds, "events", 0, smallRows);
            load(ds, "events", 1, rows);
            load(ds, "events", 2, 100);

            double small = dropMillis(ds, 0);
            double large = dropMillis(ds, 1);

            System.out.printf("gate_e4_drop small_rows=%d small_ms=%.2f large_rows=%d large_ms=%.2f%n",
                smallRows, small, rows, large);
            assertEquals(100, ds.execute("SELECT * FROM events", session(), null).asArray().size());
            assertTrue(large <= number("maxDropMillis", 500), "apagar levou " + large + " ms");
        }
    }

    private record Latency(long writes, double p50, double p99) {
    }

    /** Escreve com várias threads pelo tempo dado e mede a latência de cada escrita. */
    private static Latency write(Datastore ds, String table, boolean expiring) throws Exception {
        int threads = (int) number("writers", 8);
        long millis = number("seconds", 30) * 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicBoolean stop = new AtomicBoolean();
        List<Future<long[]>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(pool.submit(() -> {
                Session s = session();
                long[] taken = new long[1024];
                int count = 0;
                while (!stop.get()) {
                    // metade vence em um segundo, para a manutenção ter trabalho durante a carga
                    Instant expires = Instant.now().plusSeconds(expiring && count % 2 == 0 ? 1 : 86_400);
                    long from = System.nanoTime();
                    ds.execute("CREATE " + table + " CONTENT {expires: d\"" + expires
                        + "\", body: \"carga de escrita do portao\"}", s, null);
                    long elapsed = System.nanoTime() - from;
                    if (count == taken.length) {
                        taken = Arrays.copyOf(taken, count * 2);
                    }
                    taken[count++] = elapsed;
                }
                return Arrays.copyOf(taken, count);
            }));
        }
        Thread.sleep(millis);
        stop.set(true);
        List<long[]> parts = new ArrayList<>();
        for (Future<long[]> task : tasks) {
            parts.add(task.get());
        }
        pool.shutdown();
        long[] all = parts.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        return new Latency(all.length, all[all.length / 2] / 1e6, all[(int) (all.length * 0.99)] / 1e6);
    }

    @Test
    @DisplayName("o TTL remove sem degradar a latência de escrita")
    void ttl_doesNotDegradeWrites() throws Exception {
        long backlog = number("backlog", 200_000);
        try (RocksDbBackend backend = new RocksDbBackend(workDir("ttl").toString(), RocksDbBackend.SyncMode.BATCH)) {
            Datastore ds = store(backend, ColdTier.NONE);
            write(ds, "aquecimento", false);
            Latency baseline = write(ds, "plain", false);
            ds.execute("DEFINE TTL ON TABLE sessions FIELD expires", session(), null);
            loadExpired(ds, backlog);
            AtomicLong expired = new AtomicLong();
            Latency withTtl;
            try (PartitionMaintenance maintenance = new PartitionMaintenance(ds,
                    PartitionMaintenance.Options.defaults().withInterval(Duration.ofSeconds(1)),
                    System::currentTimeMillis)) {
                Thread counter = Thread.ofPlatform().start(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        expired.addAndGet(maintenance.runOnce().expiredRows());
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });
                withTtl = write(ds, "sessions", true);
                counter.interrupt();
                counter.join();
            }

            System.out.printf("gate_e4_ttl baseline_writes=%d baseline_p50_ms=%.3f baseline_p99_ms=%.3f "
                    + "ttl_writes=%d ttl_p50_ms=%.3f ttl_p99_ms=%.3f expired=%d%n",
                baseline.writes(), baseline.p50(), baseline.p99(), withTtl.writes(), withTtl.p50(), withTtl.p99(),
                expired.get());
            assertTrue(expired.get() > 0, "o TTL não removeu nada durante a carga");
            double limit = baseline.p99() * (1 + ratio("maxP99Increase", 0.2));
            assertTrue(withTtl.p99() <= limit, "p99 " + withTtl.p99() + " ms, limite " + limit + " ms");
        }
    }

    private static void loadExpired(Datastore ds, long rows) throws Exception {
        int threads = (int) number("writers", 8);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong next = new AtomicLong();
        List<Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(pool.submit(() -> {
                Session s = session();
                while (next.getAndIncrement() < rows) {
                    ds.execute("CREATE sessions CONTENT {expires: d\"2026-01-01T00:00:00Z\"}", s, null);
                }
            }));
        }
        for (Future<?> task : tasks) {
            task.get();
        }
        pool.shutdown();
    }

    private static long size(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    @Test
    @DisplayName("o restore com camada fria não copia os objetos frios")
    void restore_doesNotCopyColdObjects() throws Exception {
        long rows = number("coldRows", 200_000);
        Path live = workDir("cold-live");
        Path frozen = workDir("cold-objects");
        Path backupDir = workDir("cold-backups");
        try (ColdTier tier = ColdTier.of(new FileSystemColdStore(frozen), workDir("cold-cache"),
                ColdTier.Settings.defaults());
             RocksDbBackend backend = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.OFF);
             PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            Datastore ds = store(backend, tier);
            ds.execute("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d", session(), null);
            load(ds, "events", 0, rows);
            load(ds, "events", 1, 100);
            ds.execute("ALTER PARTITION ON TABLE events AT " + day(0) + " TO COLD", session(), null);
            long from = System.nanoTime();
            new PartitionMaintenance(ds, PartitionMaintenance.Options.defaults(), System::currentTimeMillis)
                .runOnce();
            double freeze = (System.nanoTime() - from) / 1e9;
            backend.syncWal();
            PhysicalBackups.Info info = ColdBackups.createPhysical(ds, backups, backend, backupDir);

            Path restored = workDir("cold-restore").resolve("db");
            from = System.nanoTime();
            PhysicalBackups.restore(backupDir, info.id(), restored);
            ColdBackups.requireRestorable(ds, restored);
            double restore = (System.nanoTime() - from) / 1e9;

            long cold = size(frozen.resolve("parts"));
            System.out.printf("gate_e4_cold rows=%d freeze_s=%.2f cold_bytes=%d backup_bytes=%d restored_bytes=%d "
                    + "restore_s=%.2f downloads=%d%n", rows, freeze, cold, info.sizeBytes(), size(restored),
                restore, tier.cache().downloads());
            assertEquals(0, tier.cache().downloads(), "o restore leu objetos frios");
            assertTrue(cold > 0, "nada foi para a camada fria");
            try (ColdTier other = ColdTier.of(new FileSystemColdStore(frozen), workDir("cold-cache2"),
                    ColdTier.Settings.defaults());
                 RocksDbBackend target = new RocksDbBackend(restored.toString(), RocksDbBackend.SyncMode.OFF)) {
                Datastore copy = new Datastore(target, other);
                assertEquals(rows + 100, copy.execute("SELECT count() AS n FROM events GROUP ALL", session(), null)
                    .asArray().get(0).asObject().get("n").asLong());
            }
        }
    }
}
