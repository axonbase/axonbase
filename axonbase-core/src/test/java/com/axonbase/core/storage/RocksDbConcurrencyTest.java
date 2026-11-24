package com.axonbase.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concorrência, durabilidade e atomicidade do backend RocksDB sem monitor global. */
class RocksDbConcurrencyTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] v) {
        return new String(v, StandardCharsets.UTF_8);
    }

    /** Incremento otimista com retry: nenhuma atualização pode se perder. */
    @Test
    void noLostUpdateUnderOptimisticRetry() throws Exception {
        try (RocksDbBackend db = new RocksDbBackend(dir.resolve("lost").toString())) {
            db.put("counter", b("0"));
            int threads = 8;
            int perThread = 300;
            AtomicInteger conflicts = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        while (true) {
                            var read = db.getVersioned("counter").orElseThrow();
                            int next = Integer.parseInt(s(read.value())) + 1;
                            try {
                                db.commit(Map.of("counter", read.version()), Map.of("counter", b(Integer.toString(next))),
                                    Set.of());
                                break;
                            } catch (VersionConflictException conflict) {
                                conflicts.incrementAndGet();
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(threads * perThread, Integer.parseInt(s(db.get("counter").orElseThrow())));
        }
    }

    /** Dois commits com a mesma versão esperada: exatamente um vence, o outro recebe conflito. */
    @Test
    void exactlyOneWinnerOnSameExpectedVersion() throws Exception {
        try (RocksDbBackend db = new RocksDbBackend(dir.resolve("occ").toString())) {
            for (int round = 0; round < 200; round++) {
                String key = "k" + round;
                db.put(key, b("base"));
                long version = db.versionOf(key);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger wins = new AtomicInteger();
                AtomicInteger losses = new AtomicInteger();
                List<Thread> threads = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    String mine = "t" + t;
                    Thread th = new Thread(() -> {
                        try {
                            start.await();
                            db.commit(Map.of(key, version), Map.of(key, b(mine)), Set.of());
                            wins.incrementAndGet();
                        } catch (VersionConflictException conflict) {
                            losses.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    threads.add(th);
                    th.start();
                }
                start.countDown();
                for (Thread th : threads) {
                    th.join();
                }
                assertEquals(1, wins.get(), "round " + round);
                assertEquals(3, losses.get(), "round " + round);
            }
        }
    }

    /** Versões por chave só crescem; versões de escritas diferentes nunca se repetem. */
    @Test
    void versionsAreMonotonicAndUnique() throws Exception {
        try (RocksDbBackend db = new RocksDbBackend(dir.resolve("mono").toString())) {
            int threads = 8;
            int perThread = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    List<Long> own = new ArrayList<>();
                    long last = 0;
                    for (int i = 0; i < perThread; i++) {
                        // metade das escritas na chave compartilhada, metade numa chave própria
                        String key = i % 2 == 0 ? "shared" : "own" + id;
                        db.put(key, b("v" + i));
                        long v = db.versionOf(key);
                        if (key.equals("shared")) {
                            assertTrue(v > 0);
                        } else {
                            assertTrue(v > last, "versão da chave própria regrediu");
                            last = v;
                            own.add(v);
                        }
                    }
                    return own;
                }));
            }
            Set<Long> all = new HashSet<>();
            for (Future<List<Long>> f : futures) {
                for (long v : f.get(60, TimeUnit.SECONDS)) {
                    assertTrue(all.add(v), "versão repetida " + v);
                }
            }
            pool.shutdown();
            assertTrue(db.currentVersion() >= all.stream().mapToLong(Long::longValue).max().orElse(0));
        }
    }

    /** Uma varredura enxerga uma foto: pares gravados atomicamente nunca aparecem pela metade. */
    @Test
    void scanIsConsistentWhileWriting() throws Exception {
        try (RocksDbBackend db = new RocksDbBackend(dir.resolve("scan").toString())) {
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger round = new AtomicInteger();
            Thread writer = new Thread(() -> {
                int i = 0;
                while (!stop.get()) {
                    i++;
                    String val = "r" + i;
                    Map<String, byte[]> pair = new HashMap<>();
                    for (int k = 0; k < 20; k++) {
                        pair.put("p/" + k, b(val));
                    }
                    db.commit(Map.of(), pair, Set.of());
                    round.set(i);
                }
            });
            writer.start();
            try {
                int checked = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    Set<String> values = new HashSet<>();
                    int[] count = {0};
                    db.scanEntries("p/", (k, v) -> {
                        values.add(s(v));
                        count[0]++;
                        return true;
                    });
                    if (count[0] > 0) {
                        assertEquals(20, count[0]);
                        assertEquals(1, values.size(), "varredura viu duas gerações: " + values);
                        checked++;
                    }
                }
                assertTrue(checked > 0);
            } finally {
                stop.set(true);
                writer.join();
            }
            assertTrue(round.get() > 0);
        }
    }

    /** O limite superior do prefixo não deixa vazar chaves vizinhas nem perder as do prefixo. */
    @Test
    void prefixBoundsAreExact() {
        try (RocksDbBackend db = new RocksDbBackend(dir.resolve("bound").toString())) {
            db.put("ab", b("1"));
            db.put("ab\u0000x", b("2"));
            db.put("abz", b("3"));
            db.put("ac", b("4"));
            db.put("a", b("5"));
            assertEquals(List.of("ab", "ab\u0000x", "abz"), db.keysWithPrefix("ab"));
            assertEquals(List.of("ab\u0000x"), db.keysWithPrefix("ab\u0000"));
            assertEquals(5, db.keysWithPrefix("").size());
            assertEquals(0, db.keysWithPrefix("b").size());
        }
    }

    /** Fechar e reabrir mantém dados, e as versões novas continuam acima das antigas. */
    @Test
    void reopenKeepsDataAndVersionsMonotonic() {
        String path = dir.resolve("durable").toString();
        long before;
        try (RocksDbBackend db = new RocksDbBackend(path)) {
            db.put("x", b("1"));
            db.put("y", b("2"));
            db.delete("y");
            db.put("x", b("3"));
            before = db.versionOf("x");
        }
        try (RocksDbBackend db = new RocksDbBackend(path)) {
            assertEquals("3", s(db.get("x").orElseThrow()));
            assertTrue(db.get("y").isEmpty());
            assertEquals(before, db.versionOf("x"));
            assertTrue(db.currentVersion() >= before);
            db.put("z", b("9"));
            assertTrue(db.versionOf("z") > before);
            db.put("x", b("4"));
            assertTrue(db.versionOf("x") > before);
        }
    }

    @Test
    void reopenWorksInEverySyncMode() {
        for (RocksDbBackend.SyncMode mode : RocksDbBackend.SyncMode.values()) {
            String path = dir.resolve("mode-" + mode).toString();
            try (RocksDbBackend db = new RocksDbBackend(path, mode)) {
                db.put("k", b(mode.name()));
                db.flush();
            }
            try (RocksDbBackend db = new RocksDbBackend(path, mode)) {
                assertEquals(mode.name(), s(db.get("k").orElseThrow()));
            }
        }
    }

    /** Se o lote não pode ser montado, nada é gravado: nem valor, nem versão, nem as outras chaves. */
    @Test
    void failedBatchLeavesNothingBehind() {
        try (RocksDbBackend db = new RocksDbBackend(dir.resolve("atomic").toString())) {
            db.put("keep", b("old"));
            long keepVersion = db.versionOf("keep");
            Map<String, byte[]> puts = new java.util.LinkedHashMap<>();
            puts.put("a", b("1"));
            puts.put("keep", b("new"));
            puts.put("boom", null);
            assertThrows(RuntimeException.class, () -> db.commit(Map.of(), puts, Set.of()));
            assertTrue(db.get("a").isEmpty());
            assertEquals("old", s(db.get("keep").orElseThrow()));
            assertEquals(keepVersion, db.versionOf("keep"));
            assertEquals(0L, db.versionOf("a"));
            // o lote falho não deixa a marca d'água presa
            db.put("later", b("x"));
            assertEquals(db.versionOf("later"), db.currentVersion());
        }
    }

    /** Conflito numa chave do lote cancela as demais: tudo ou nada. */
    @Test
    void conflictAppliesNothing() {
        try (RocksDbBackend db = new RocksDbBackend(dir.resolve("conflict").toString())) {
            db.put("a", b("1"));
            db.put("b", b("1"));
            long va = db.versionOf("a");
            long vb = db.versionOf("b");
            db.put("b", b("2"));
            Map<String, Long> expected = Map.of("a", va, "b", vb);
            assertThrows(VersionConflictException.class,
                () -> db.commit(expected, Map.of("a", b("X"), "c", b("X")), Set.of()));
            assertEquals("1", s(db.get("a").orElseThrow()));
            assertFalse(db.get("c").isPresent());
        }
    }

    /** Muitas threads em chaves sobrepostas, em lotes multi-chave, não travam (ordem das travas). */
    @Test
    void overlappingMultiKeyCommitsDoNotDeadlock() throws Exception {
        try (RocksDbBackend db = new RocksDbBackend(dir.resolve("deadlock").toString())) {
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    java.util.Random random = new java.util.Random(id);
                    for (int i = 0; i < 400; i++) {
                        Map<String, byte[]> puts = new HashMap<>();
                        for (int k = 0; k < 6; k++) {
                            puts.put("m" + random.nextInt(40), b("t" + id));
                        }
                        db.commit(Map.of(), puts, Set.of());
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
        }
    }
}
