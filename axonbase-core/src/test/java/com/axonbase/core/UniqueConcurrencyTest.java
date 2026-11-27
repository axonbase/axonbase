package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UNIQUE sob escritas simultâneas fora de transação")
class UniqueConcurrencyTest {

    private static final int ROUNDS = 50;
    private static final int WRITERS = 4;
    private static final int BUILD_ROWS = 1200;
    private static final String BUILD = "DEFINE INDEX email ON TABLE c COLUMNS email UNIQUE";

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static void run(Datastore ds, String sql) {
        ds.execute(sql, session(), null);
    }

    private static int count(Datastore ds, String sql) {
        return ds.execute(sql, session(), null).asArray().size();
    }

    private static RocksDbBackend open(Path dir) {
        return new RocksDbBackend(dir.resolve("rocks").toString());
    }

    /** Solta os escritores juntos e devolve quantos foram aceitos. */
    private static int race(Datastore ds, IntFunction<String> statement) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Thread[] writers = new Thread[WRITERS];
        for (int i = 0; i < writers.length; i++) {
            String sql = statement.apply(i);
            writers[i] = new Thread(() -> {
                try {
                    start.await();
                    run(ds, sql);
                    accepted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException refused) {
                    // recusado: é o esperado para todos menos um
                }
            });
            writers[i].start();
        }
        start.countDown();
        for (Thread writer : writers) {
            writer.join();
        }
        return accepted.get();
    }

    @Test
    @DisplayName("criações simultâneas do mesmo valor: uma só é aceita")
    void concurrentCreates(@TempDir Path dir) throws Exception {
        try (RocksDbBackend backend = open(dir)) {
            Datastore ds = new Datastore(backend);
            run(ds, "DEFINE TABLE c SCHEMALESS");
            run(ds, "CREATE INDEX email ON TABLE c COLUMNS email UNIQUE");
            for (int round = 0; round < ROUNDS; round++) {
                int r = round;
                int accepted = race(ds, i -> "CREATE c:" + (r * 10 + i) + " SET email = 'e" + r + "'");

                assertEquals(1, accepted, "rodada " + round);
            }
            assertEquals(ROUNDS, count(ds, "SELECT * FROM c"));
        }
    }

    @Test
    @DisplayName("atualizações simultâneas para o mesmo valor: uma só é aceita, com índice criado depois dos dados")
    void concurrentUpdatesOnIndexBuiltAfterTheData(@TempDir Path dir) throws Exception {
        try (RocksDbBackend backend = open(dir)) {
            Datastore ds = new Datastore(backend);
            for (int i = 0; i < ROUNDS * WRITERS; i++) {
                run(ds, "CREATE c:" + i + " SET a = 'a" + i + "', b = 1");
            }
            run(ds, "CREATE INDEX par ON TABLE c COLUMNS a, b UNIQUE");
            for (int round = 0; round < ROUNDS; round++) {
                int r = round;
                int accepted = race(ds, i -> "UPDATE c:" + (r * WRITERS + i) + " SET a = 'novo" + r + "'");

                assertEquals(1, accepted, "rodada " + round);
            }
        }
    }

    @Test
    @DisplayName("o valor liberado por DELETE ou UPDATE volta a ser disputado, também depois de reabrir")
    void releasedValueIsContendedAgain(@TempDir Path dir) throws Exception {
        try (RocksDbBackend backend = open(dir)) {
            Datastore ds = new Datastore(backend);
            run(ds, "DEFINE TABLE c SCHEMALESS");
            run(ds, "CREATE INDEX email ON TABLE c COLUMNS email UNIQUE");
            run(ds, "CREATE c:apagado SET email = 'x'");
            run(ds, "DELETE c:apagado");
            run(ds, "CREATE c:trocado SET email = 'y'");
            run(ds, "UPDATE c:trocado SET email = 'z'");
        }
        try (RocksDbBackend backend = open(dir)) {
            Datastore ds = new Datastore(backend);

            assertEquals(1, race(ds, i -> "CREATE c:x" + i + " SET email = 'x'"));
            assertEquals(1, race(ds, i -> "CREATE c:y" + i + " SET email = 'y'"));
            assertThrows(RuntimeException.class, () -> run(ds, "CREATE c:z SET email = 'z'"));
            assertDoesNotThrow(() -> run(ds, "UPDATE c:trocado SET outro = 1"));
        }
    }

    @Test
    @DisplayName("construção em segundo plano: uma escrita aceita por valor durante e depois, e uma chave por valor")
    void onlineBuildClaimsEveryValue(@TempDir Path dir) throws Exception {
        try (RocksDbBackend backend = open(dir)) {
            Datastore ds = new Datastore(backend);
            loadForBackgroundBuild(ds);
            CountDownLatch paused = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger chunks = new AtomicInteger();
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
            try {
                run(ds, BUILD);
                assertTrue(paused.await(30, TimeUnit.SECONDS));

                assertEquals(1, race(ds, i -> "CREATE c:durante" + i + " SET email = 'durante'"));
            } finally {
                go.countDown();
                assertTrue(IndexBuilder.awaitAll(60_000));
                IndexBuilder.chunkHook(null);
            }

            assertEquals(BUILD_ROWS + 1, claims(backend));
            assertEquals(1, race(ds, i -> "CREATE c:depois" + i + " SET email = 'depois'"));
            assertThrows(RuntimeException.class, () -> run(ds, "CREATE c:z SET email = 'u10'"));

            run(ds, "DROP INDEX email ON TABLE c");

            assertEquals(0, claims(backend));
        }
    }

    @Test
    @DisplayName("construção retomada depois de uma queda termina com uma chave por valor")
    void resumedBuildClaimsEveryValue(@TempDir Path dir) throws Exception {
        try (RocksDbBackend backend = open(dir)) {
            Datastore ds = new Datastore(backend);
            loadForBackgroundBuild(ds);
            AtomicInteger chunks = new AtomicInteger();
            IndexBuilder.chunkHook(() -> {
                if (chunks.incrementAndGet() == 3) {
                    throw new IndexBuilder.Crash();
                }
            });
            try {
                run(ds, BUILD);
                assertTrue(IndexBuilder.awaitAll(60_000));
            } finally {
                IndexBuilder.chunkHook(null);
            }
        }
        try (RocksDbBackend backend = open(dir)) {
            Datastore ds = new Datastore(backend);
            assertTrue(IndexBuilder.awaitAll(60_000));

            assertEquals(BUILD_ROWS, claims(backend));
            assertThrows(RuntimeException.class, () -> run(ds, "CREATE c:z SET email = 'u10'"));
            assertEquals(1, race(ds, i -> "CREATE c:novo" + i + " SET email = 'novo'"));
        }
    }

    /** Linhas suficientes para a construção ter mais de dois blocos. */
    private static void loadForBackgroundBuild(Datastore ds) {
        run(ds, "DEFINE TABLE c SCHEMALESS");
        ds.findDatabase("n", "d").indexInlineRows(0);
        for (int i = 0; i < BUILD_ROWS; i++) {
            run(ds, "CREATE c:" + i + " SET email = 'u" + i + "'");
        }
    }

    private static int claims(RocksDbBackend backend) {
        return backend.keysWithPrefix("UQL|n|d|c|").size();
    }

    @Test
    @DisplayName("tabela apagada e recriada continua recusando só o que se repete")
    void droppedTableLeavesNothingBehind(@TempDir Path dir) {
        try (RocksDbBackend backend = open(dir)) {
            Datastore ds = new Datastore(backend);
            run(ds, "CREATE c:1 SET email = 'x'");
            run(ds, "CREATE INDEX email ON TABLE c COLUMNS email UNIQUE");
            run(ds, "UPDATE c:1 SET email = 'y'");
            run(ds, "DROP TABLE c");

            assertEquals(0, backend.keysWithPrefix("UQL|n|d|c|").size());
            run(ds, "CREATE c:2 SET email = 'y'");
            assertEquals(1, count(ds, "SELECT * FROM c"));
        }
    }
}
