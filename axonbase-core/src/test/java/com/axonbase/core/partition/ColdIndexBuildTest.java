package com.axonbase.core.partition;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.partition.cold.ColdStore;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.axonbase.core.partition.TimeData.DAY1;
import static com.axonbase.core.partition.TimeData.DAY2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uma falha da camada fria durante a construção de um índice não pode custar o índice. */
@DisplayName("Índices com a camada fria fora")
class ColdIndexBuildTest {

    private static final String MARKER = "IXB|test|dev|events|quem";
    private static final String DEFINE = "DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE";

    @TempDir
    Path tmp;

    private final AtomicInteger caches = new AtomicInteger();
    /** Quando ligado, o armazenamento responde que os objetos existem, mas não os entrega. */
    private final AtomicBoolean downloadsFail = new AtomicBoolean();

    @AfterEach
    void clearHook() {
        IndexBuilder.chunkHook(null);
    }

    /** Uma camada fria com cache próprio, como a de um processo que acabou de subir. */
    private ColdTier tier() {
        return ColdTier.of(flaky(new FileSystemColdStore(tmp.resolve("cold"))),
            tmp.resolve("cache-" + caches.incrementAndGet()), ColdTier.Settings.defaults());
    }

    private ColdStore flaky(ColdStore inner) {
        return new ColdStore() {
            @Override
            public void upload(String key, Path file) throws IOException {
                inner.upload(key, file);
            }

            @Override
            public boolean download(String key, Path target) throws IOException {
                if (downloadsFail.get()) {
                    throw new IOException("armazenamento fora do ar");
                }
                return inner.download(key, target);
            }

            @Override
            public void write(String key, byte[] content) throws IOException {
                inner.write(key, content);
            }

            @Override
            public Optional<byte[]> read(String key) throws IOException {
                return inner.read(key);
            }

            @Override
            public boolean exists(String key) throws IOException {
                return inner.exists(key);
            }

            @Override
            public void delete(String key) throws IOException {
                inner.delete(key);
            }

            @Override
            public List<String> list(String prefix) throws IOException {
                return inner.list(prefix);
            }

            @Override
            public String describe() {
                return inner.describe();
            }
        };
    }

    /** Três dias, o primeiro na camada fria. */
    private TimeData firstDayCold() {
        TimeData db = TimeData.rocks(tmp.resolve("db"), tier()).threeDays();
        db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO COLD");
        PartitionMaintenance maintenance = new PartitionMaintenance(db.ds,
            PartitionMaintenance.Options.defaults().withTtlPause(Duration.ZERO), System::currentTimeMillis);
        assertEquals(1, maintenance.runOnce().movedCold());
        return db;
    }

    /** Um índice UNIQUE cuja construção caiu antes do primeiro bloco, como numa queda do processo. */
    private TimeData interrupted() throws InterruptedException {
        TimeData db = firstDayCold();
        db.ds.findDatabase("test", "dev").indexInlineRows(0);
        IndexBuilder.chunkHook(() -> {
            throw new IndexBuilder.Crash();
        });
        db.run(DEFINE);
        assertTrue(IndexBuilder.awaitAll(10_000));
        IndexBuilder.chunkHook(null);
        assertEquals("building", state(db, "quem"));
        return db;
    }

    private static void reopen(TimeData db, ColdTier cold) throws InterruptedException {
        db.restart(cold);
        assertTrue(IndexBuilder.awaitAll(10_000));
    }

    /** {@code ready}, {@code building} ou {@code null} quando o índice não existe. */
    private static String state(TimeData db, String index) {
        AxonValue found = db.run("INFO FOR TABLE events").asObject().get("indexes").asObject().get(index);
        return found == null ? null : found.asObject().get("state").asString();
    }

    private static String failure(TimeData db, String index) {
        AxonValue failures = db.run("INFO FOR TABLE events").asObject().get("index_failures");
        AxonValue found = failures == null ? null : failures.asObject().get(index);
        return found == null ? "" : found.asString();
    }

    private static void assertKept(TimeData db) {
        assertEquals("building", state(db, "quem"), "o índice foi removido ou dado como pronto");
        assertTrue(db.backend.get(MARKER).isPresent(), "sem o marcador a construção não seria retomada");
    }

    /** Pronto e valendo contra a linha que está na camada fria. */
    private static void assertRebuilt(TimeData db) {
        assertEquals("ready", state(db, "quem"));
        assertEquals("", failure(db, "quem"));
        assertThrows(AxonError.class,
            () -> db.run("CREATE events:z CONTENT {at: d\"" + DAY2 + "\", who: \"ana\"}"));
    }

    @Test
    @DisplayName("subir sem a camada fria mantém o índice interrompido e registra o erro; com ela, ele é refeito")
    void withoutColdTierKeepsIndex() throws InterruptedException {
        try (TimeData db = interrupted()) {
            reopen(db, ColdTier.NONE);

            assertKept(db);
            assertTrue(failure(db, "quem").contains("AXON_COLD_DIR"), failure(db, "quem"));

            reopen(db, tier());
            assertRebuilt(db);
        }
    }

    @Test
    @DisplayName("com um objeto frio fora do alcance o índice é mantido")
    void missingObjectKeepsIndex() throws Exception {
        try (TimeData db = interrupted()) {
            Path objects = tmp.resolve("cold").resolve("parts");
            Path away = tmp.resolve("parts-fora");
            Files.move(objects, away);

            reopen(db, tier());

            assertKept(db);
            assertFalse(failure(db, "quem").isEmpty());
            Files.move(away, objects);
            reopen(db, tier());
            assertRebuilt(db);
        }
    }

    @Test
    @DisplayName("uma leitura fria que falha no meio da construção mantém o índice")
    void failingDownloadKeepsIndex() throws InterruptedException {
        try (TimeData db = interrupted()) {
            downloadsFail.set(true);

            reopen(db, tier());

            assertKept(db);
            assertFalse(failure(db, "quem").isEmpty());
            downloadsFail.set(false);
            reopen(db, tier());
            assertRebuilt(db);
        }
    }

    @Test
    @DisplayName("redefinir um índice parado tenta construir de novo: nunca o dá como pronto sem ler as linhas")
    void redefiningStalledIndexRebuilds() throws InterruptedException {
        try (TimeData db = interrupted()) {
            downloadsFail.set(true);
            reopen(db, tier());

            assertThrows(AxonError.class, () -> db.run(DEFINE));

            assertKept(db);
            downloadsFail.set(false);
            db.run(DEFINE);
            assertTrue(IndexBuilder.awaitAll(10_000));
            assertRebuilt(db);
        }
    }

    @Test
    @DisplayName("um índice novo com a camada fria fora falha sem registrar nada")
    void newIndexWithoutColdTierRegistersNothing() throws InterruptedException {
        try (TimeData db = firstDayCold()) {
            reopen(db, ColdTier.NONE);

            AxonError refused = assertThrows(AxonError.class, () -> db.run(DEFINE));

            assertTrue(refused.getMessage().contains("AXON_COLD_DIR"), refused.getMessage());
            assertEquals(null, state(db, "quem"));
            assertTrue(db.backend.get(MARKER).isEmpty());
        }
    }
}
