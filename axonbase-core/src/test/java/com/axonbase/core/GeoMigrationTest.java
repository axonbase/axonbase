package com.axonbase.core;

import com.axonbase.core.cluster.AppliedBatchListener;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.axonbase.core.GeoTestData.INDEX;
import static com.axonbase.core.GeoTestData.insert;
import static com.axonbase.core.GeoTestData.ns;
import static com.axonbase.core.GeoTestData.point;
import static com.axonbase.core.GeoTestData.run;
import static com.axonbase.core.GeoTestData.sortedNs;
import static com.axonbase.core.GeoTestData.strategy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Passagem do índice geohash para o H3: sem resultado errado em silêncio")
class GeoMigrationTest {

    private static final String CENTER = point(-46.65, -23.55);
    private static final String RADIUS = " WHERE geo::distance(pos, " + CENTER + ") <= 2500";
    private static final String NEAREST = " ORDER BY geo::distance(pos, " + CENTER + ") LIMIT 15";

    @AfterEach
    void reset() {
        IndexBuilder.chunkHook(null);
    }

    private static MemoryBackend loaded(int rows) {
        MemoryBackend backend = new MemoryBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "dev");
        run(ds, "DEFINE TABLE g SCHEMALESS; DEFINE TABLE h SCHEMALESS; " + INDEX);
        Random rnd = new Random(1);
        for (int i = 0; i < rows; i++) {
            insert(ds, i, new double[] {-46.70 + rnd.nextDouble() * 0.1, -23.60 + rnd.nextDouble() * 0.1});
        }
        return backend;
    }

    private static List<String> keys(KvBackend kv, String prefix) {
        return kv.keysWithPrefix(prefix);
    }

    private static void deleteAll(KvBackend kv, String prefix) {
        keys(kv, prefix).forEach(kv::delete);
    }

    /** Deixa a base como o binário 0.10 a deixaria: só chaves geohash, sem marcador de construção. */
    private static void toLegacy(KvBackend kv) {
        List<String> entries = keys(kv, "SHR|test|dev|g|g_pos|");
        deleteAll(kv, "SH|test|dev|g|g_pos|");
        deleteAll(kv, "SHR|test|dev|g|g_pos|");
        kv.delete("SHV|test|dev|g|g_pos");
        kv.delete("IXB|test|dev|g|g_pos");
        for (String reverse : entries) {
            String id = reverse.substring("SHR|test|dev|g|g_pos|".length());
            kv.put("SP|test|dev|g|g_pos|6gyf4b|" + id, new byte[0]);
            kv.put("SPR|test|dev|g|g_pos|" + id, "6gyf4b".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        kv.put("SPV|test|dev|g|g_pos", new byte[] {1});
    }

    /** Abre a base com a reconstrução parada no primeiro bloco, confere, solta e espera. */
    private static void assertRebuilds(java.util.function.Supplier<Datastore> open) throws InterruptedException {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        IndexBuilder.chunkHook(() -> {
            paused.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Datastore ds;
        try {
            ds = open.get();
            assertTrue(paused.await(30, TimeUnit.SECONDS), "o índice não foi reconstruído");
            assertEquals("FULL_SCAN", strategy(ds, "SELECT n FROM g" + RADIUS));
            assertEquals("FULL_SCAN", strategy(ds, "SELECT n FROM g" + NEAREST));
            assertSame(ds);
            // escrita durante a reconstrução
            insert(ds, 90_000, new double[] {-46.65, -23.55});
            run(ds, "DELETE g:3; DELETE h:3; UPDATE g:4 SET pos = " + CENTER + "; UPDATE h:4 SET pos = " + CENTER);
            assertSame(ds);
        } finally {
            go.countDown();
        }
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertCurrent(ds);
    }

    private static void assertSame(Datastore ds) {
        assertTrue(sortedNs(ds, "SELECT n FROM h" + RADIUS).size() > 10);
        assertEquals(sortedNs(ds, "SELECT n FROM h" + RADIUS), sortedNs(ds, "SELECT n FROM g" + RADIUS));
        assertEquals(ns(ds, "SELECT n FROM h" + NEAREST), ns(ds, "SELECT n FROM g" + NEAREST));
    }

    private static void assertCurrent(Datastore ds) {
        KvBackend kv = ds.findDatabase("test", "dev").records();
        assertEquals("GEO", strategy(ds, "SELECT n FROM g" + RADIUS));
        assertEquals("GEO_KNN", strategy(ds, "SELECT n FROM g" + NEAREST));
        assertSame(ds);
        assertEquals(List.of(), keys(kv, "SP|"));
        assertEquals(List.of(), keys(kv, "SPR|"));
        assertEquals(List.of(), keys(kv, "SPV|"));
        assertEquals(keys(kv, "SHR|").size(), keys(kv, "SH|").size());
        assertEquals(List.of("IXB|test|dev|g|g_pos"), keys(kv, "IXB|"));
        assertTrue(kv.get("SHV|test|dev|g|g_pos").isPresent());
    }

    @Test
    @DisplayName("índice geohash antigo é reconstruído ao abrir a base, e a consulta varre a tabela até lá")
    void legacyIndexIsRebuilt() throws InterruptedException {
        MemoryBackend backend = loaded(900);
        toLegacy(backend);
        assertRebuilds(() -> new Datastore(backend));
    }

    @Test
    @DisplayName("voltar do binário antigo (que apaga o marcador ao refazer o dele) refaz o índice H3")
    void upgradeAfterDowngradeRebuilds() throws InterruptedException {
        MemoryBackend backend = loaded(700);
        backend.delete("IXB|test|dev|g|g_pos");
        assertRebuilds(() -> new Datastore(backend));
    }

    @Test
    @DisplayName("construção interrompida no meio é refeita")
    void interruptedBuildResumes() throws InterruptedException {
        MemoryBackend backend = loaded(700);
        backend.delete("SHV|test|dev|g|g_pos");
        deleteAll(backend, "SH|test|dev|g|g_pos|0");
        assertRebuilds(() -> new Datastore(backend));
    }

    @Test
    @DisplayName("restaurar um backup feito pelo binário antigo reconstrói o índice")
    void legacyBackupIsRebuilt() throws InterruptedException {
        MemoryBackend source = loaded(700);
        Datastore ds = new Datastore(source);
        toLegacy(source);
        String dump = ds.backupCluster();
        assertRebuilds(() -> {
            Datastore target = new Datastore(new MemoryBackend());
            target.restoreCluster(dump);
            return target;
        });
    }

    @Test
    @DisplayName("lote replicado de um líder antigo invalida o índice e o reconstrói")
    void replicatedLegacyWriteRebuilds() throws InterruptedException {
        MemoryBackend backend = loaded(700);
        Datastore ds = new Datastore(backend);
        assertCurrent(ds);
        assertRebuilds(() -> {
            String key = "SP|test|dev|g|g_pos|6gyf4b|n5";
            backend.put(key, new byte[0]);
            CommittedBatch batch = new CommittedBatch("lider-antigo", Map.of(key, new byte[0]), Set.of());
            ds.onReplicatedBatch(new AppliedBatchListener.AppliedBatch(batch, Map.of()));
            return ds;
        });
    }

    @Test
    @DisplayName("escrita antiga que chega durante a reconstrução faz refazer antes de ficar pronto")
    void legacyWriteDuringRebuildIsCaught() throws InterruptedException {
        MemoryBackend backend = loaded(700);
        toLegacy(backend);
        java.util.concurrent.atomic.AtomicInteger chunks = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        IndexBuilder.chunkHook(() -> {
            if (chunks.incrementAndGet() == 2) {
                paused.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Datastore ds = new Datastore(backend);
        assertTrue(paused.await(30, TimeUnit.SECONDS));
        backend.put("SP|test|dev|g|g_pos|6gyf4b|n5", new byte[0]);
        go.countDown();
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertTrue(chunks.get() >= 4, "uma passada só: " + chunks.get() + " blocos");
        assertCurrent(ds);
    }

    @Test
    @DisplayName("queda no meio da limpeza do índice antigo deixa o marcador: o binário antigo ainda o refaz")
    void crashDuringPurgeKeepsTheMarker() throws InterruptedException {
        MemoryBackend inner = loaded(100);
        toLegacy(inner);
        HookBackend backend = new HookBackend(inner);
        backend.beforeDelete = key -> {
            if (key.startsWith("SP|")) {
                throw new IndexBuilder.Crash();
            }
        };
        new Datastore(backend);
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertTrue(inner.get("IXB|test|dev|g|g_pos").isPresent(), "sem marcador, o 0.10 usaria um índice pela metade");
        assertTrue(inner.get("SHV|test|dev|g|g_pos").isEmpty());
        assertRebuilds(() -> new Datastore(inner));
    }

    @Test
    @DisplayName("recarga do catálogo no meio da construção faz recomeçar, sem marcar um índice pela metade")
    void catalogReloadDuringBuildRestarts() throws InterruptedException {
        MemoryBackend backend = loaded(700);
        toLegacy(backend);
        java.util.concurrent.atomic.AtomicInteger chunks = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        IndexBuilder.chunkHook(() -> {
            if (chunks.incrementAndGet() == 2) {
                paused.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Datastore ds = new Datastore(backend);
        assertTrue(paused.await(30, TimeUnit.SECONDS));
        ds.reloadFromBackend();
        go.countDown();
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertTrue(chunks.get() >= 4, "não recomeçou: " + chunks.get() + " blocos");
        assertCurrent(ds);
    }

    @Test
    @DisplayName("índice em dia não é reconstruído ao reabrir")
    void currentIndexIsKept() {
        MemoryBackend backend = loaded(50);
        IndexBuilder.chunkHook(() -> {
            throw new AssertionError("não deveria reconstruir");
        });
        Datastore ds = new Datastore(backend);
        assertEquals("GEO", strategy(ds, "SELECT n FROM g" + RADIUS));
    }

    @Test
    @DisplayName("apagar o índice, a tabela ou redefinir sem GEO não deixa chaves nem marcador")
    void removalLeavesNothing() {
        for (String ddl : List.of("DROP INDEX g_pos", "REMOVE TABLE g",
            "DEFINE INDEX g_pos ON TABLE g COLUMNS pos")) {
            MemoryBackend backend = loaded(30);
            Datastore ds = new Datastore(backend);
            run(ds, ddl);
            for (String prefix : List.of("SH|", "SHR|", "SHV|", "IXB|", "SP|", "SPR|")) {
                assertEquals(List.of(), keys(backend, prefix), ddl + " deixou " + prefix);
            }
        }
    }
}
