package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portão de medida da E1.1 (opt-in: {@code -Daxon.gate.physical=true}). {@code -Daxon.gate.physical.mb} muda o
 * tamanho da base (padrão 1024; o alvo do plano é 10240, 10 GB). Precisa de cerca de 2 vezes o tamanho da base
 * livre em disco: a base é apagada antes do restore.
 */
@EnabledIfSystemProperty(named = "axon.gate.physical", matches = "true")
@DisplayName("Portão de medida do backup físico")
class PhysicalBackupGateTest {

    private static final int VALUE_BYTES = 4096;

    @TempDir
    Path tmp;

    private final int mb = Integer.getInteger("axon.gate.physical.mb", 1024);

    private static byte[] value(Random random) {
        byte[] v = new byte[VALUE_BYTES];
        random.nextBytes(v); // incompressível: o tamanho em disco é o tamanho lógico
        return v;
    }

    private static void load(RocksDbBackend b, long rows, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long per = rows / threads;
        for (int t = 0; t < threads; t++) {
            long from = t * per;
            long to = t == threads - 1 ? rows : from + per;
            pool.submit(() -> {
                Random random = new Random(from);
                for (long i = from; i < to; i++) {
                    b.put(String.format("k%012d", i), value(random));
                }
                return null;
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.HOURS));
    }

    /** SHA-256 sobre todas as chaves e valores em ordem. */
    private static String fingerprint(RocksDbBackend b) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long[] n = {0};
        b.scanEntries("", (k, v) -> {
            sha.update(k.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(v);
            n[0]++;
            return true;
        });
        return HexFormat.of().formatHex(sha.digest()) + "/" + n[0];
    }

    private static long sizeOf(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private static void mutate(RocksDbBackend b, long baseRows, long extraFrom, int updates, int inserts) {
        Random random = new Random(extraFrom);
        for (int i = 0; i < updates; i++) {
            b.put(String.format("k%012d", (long) (random.nextDouble() * baseRows)), value(random));
        }
        for (int i = 0; i < inserts; i++) {
            b.put(String.format("k%012d", extraFrom + i), value(random));
        }
    }

    @Test
    @DisplayName("restaura o terceiro incremental com checksum igual e mede o tempo de restore")
    void restoreTimeAndThirdIncrementalChecksum() throws Exception {
        long rows = mb * 1024L * 1024L / VALUE_BYTES;
        Path live = tmp.resolve("live");
        Path store = tmp.resolve("bk");
        String expected;
        long t0 = System.nanoTime();
        int third;
        long fullSeconds;
        long incSeconds;
        try (RocksDbBackend b = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.OFF);
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            load(b, rows, 4);
            System.out.printf("GATE FISICO carga de %d MB (%d linhas): %.1f s%n", mb, rows,
                (System.nanoTime() - t0) / 1e9);
            t0 = System.nanoTime();
            pb.create(b);
            fullSeconds = Math.round((System.nanoTime() - t0) / 1e9);
            long afterFull = sizeOf(store);
            int churn = (int) Math.max(1000, rows / 50); // 2% da base muda entre backups
            long inc = 0;
            third = -1;
            for (int round = 0; round < 2; round++) {
                mutate(b, rows, rows + 10_000_000L * (round + 1), churn, churn / 2);
                t0 = System.nanoTime();
                third = pb.create(b).id();
                inc += System.nanoTime() - t0;
            }
            incSeconds = Math.round(inc / 2e9);
            long afterThird = sizeOf(store);
            expected = fingerprint(b);
            System.out.printf("GATE FISICO backup completo %d s; incremental médio %d s; o diretório de backup cresceu de %d MB para %d MB%n",
                fullSeconds, incSeconds, afterFull >> 20, afterThird >> 20);
            assertTrue(afterThird - afterFull < afterFull, "os incrementais não podem copiar a base inteira de novo");
        }
        // libera o disco da base original: o backup é a única cópia daqui em diante
        DirectorySwap.deleteTree(live);
        Path restored = tmp.resolve("restaurado");
        t0 = System.nanoTime();
        PhysicalBackups.restore(store, third, restored);
        double restoreSeconds = (System.nanoTime() - t0) / 1e9;
        double mbPerSecond = mb / restoreSeconds;
        double projected10gb = 10240 / mbPerSecond;
        System.out.printf("GATE FISICO restore de %d MB em %.1f s (%.0f MB/s); projeção para 10 GB: %.0f s (meta 600 s)%n",
            mb, restoreSeconds, mbPerSecond, projected10gb);
        try (RocksDbBackend r = new RocksDbBackend(restored.toString(), RocksDbBackend.SyncMode.OFF)) {
            assertEquals(expected, fingerprint(r), "o terceiro incremental restaura com o mesmo checksum da origem");
        }
        assertTrue(projected10gb < 600, "restore de 10 GB projetado em " + Math.round(projected10gb) + " s (meta 600 s)");
    }

    @Test
    @DisplayName("com 5 mil escritas por segundo e backups frequentes a vazão cai no máximo 10%")
    void throughputUnderBackups() throws Exception {
        long rows = Math.min(mb, 1024) * 1024L * 1024L / VALUE_BYTES;
        Path live = tmp.resolve("live");
        Path store = tmp.resolve("bk");
        try (RocksDbBackend b = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.BATCH);
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            load(b, rows, 4);
            pb.create(b);
            double baseline = writeRate(b, null, 30);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong backups = new AtomicLong();
            Thread backer = Thread.ofPlatform().start(() -> {
                // backups a cada 5 s no lugar de a cada 5 min: 60 vezes mais frequentes do que na produção
                while (!stop.get()) {
                    try {
                        pb.create(b);
                        backups.incrementAndGet();
                        pb.purgeKeeping(3);
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            double withBackups = writeRate(b, backer, 60);
            stop.set(true);
            backer.interrupt();
            backer.join(60_000);
            System.out.printf("GATE FISICO escritas a 5000/s: %.0f/s sem backup, %.0f/s com %d backups em 60 s (queda %.1f%%)%n",
                baseline, withBackups, backups.get(), (1 - withBackups / baseline) * 100);
            assertTrue(backups.get() >= 3, "o backup precisa ter rodado várias vezes durante a medida");
            assertTrue(withBackups >= baseline * 0.9, "queda acima de 10%: " + baseline + " para " + withBackups);
            assertTrue(withBackups >= 4500, "vazão sustentada abaixo de 4500/s");
        }
    }

    /** Escreve a 5000 por segundo no total, com 8 threads, durante {@code seconds}, e devolve as escritas por segundo. */
    private static double writeRate(RocksDbBackend b, Thread ignored, int seconds) throws Exception {
        int threads = 8;
        double perThread = 5000.0 / threads;
        AtomicLong done = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.submit(() -> {
                Random random = new Random(id * 31L + start);
                long i = 0;
                long begin = System.nanoTime();
                while (!stop.get()) {
                    b.put("w" + id + "-" + (i++ % 200_000), value(random));
                    done.incrementAndGet();
                    long due = begin + (long) (i / perThread * 1e9);
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                        } catch (InterruptedException e) {
                            return null;
                        }
                    }
                }
                return null;
            });
        }
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        return done.get() / ((System.nanoTime() - start) / 1e9);
    }
}
