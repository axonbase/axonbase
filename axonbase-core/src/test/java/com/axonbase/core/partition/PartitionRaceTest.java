package com.axonbase.core.partition;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.partition.cold.ColdStore;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.core.storage.VersionConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.axonbase.core.partition.TimeData.DAY1;
import static com.axonbase.core.partition.TimeData.DAY2;
import static com.axonbase.core.partition.TimeData.DAY3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Corridas e falhas no meio das operações de partição: o que não pode perder, duplicar nem ressuscitar linha. */
@DisplayName("Partições: corridas e falhas")
class PartitionRaceTest {

    @TempDir
    Path tmp;

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-11-04T00:00:00Z").toEpochMilli());

    /** Armazenamento frio que roda um gancho uma vez, no meio de um envio ou de um download, ou recusa gravações. */
    private static final class HookedStore implements ColdStore {
        private final ColdStore inner;
        final AtomicReference<Runnable> afterUpload = new AtomicReference<>();
        final AtomicReference<Runnable> afterDownload = new AtomicReference<>();
        volatile String refusedWrites;

        HookedStore(Path directory) {
            this.inner = new FileSystemColdStore(directory);
        }

        private static void run(AtomicReference<Runnable> hook) {
            Runnable once = hook.getAndSet(null);
            if (once != null) {
                once.run();
            }
        }

        @Override
        public void upload(String key, Path file) throws IOException {
            inner.upload(key, file);
            run(afterUpload);
        }

        @Override
        public boolean download(String key, Path target) throws IOException {
            boolean found = inner.download(key, target);
            run(afterDownload);
            return found;
        }

        @Override
        public void write(String key, byte[] content) throws IOException {
            if (refusedWrites != null && key.startsWith(refusedWrites)) {
                throw new IOException("gravação recusada pelo teste");
            }
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
    }

    private ColdTier tier(ColdStore store) {
        return ColdTier.of(store, tmp.resolve("cache"), ColdTier.Settings.defaults());
    }

    private PartitionMaintenance maintenance(TimeData db) {
        return new PartitionMaintenance(db.ds, PartitionMaintenance.Options.defaults()
            .withTtlPause(Duration.ZERO).withFullScanEvery(1), now::get);
    }

    private static void toCold(TimeData db, String day) {
        db.run("ALTER PARTITION ON TABLE events AT d\"" + day + "\" TO COLD");
    }

    /** Quantas chaves físicas guardam o registro de chave {@code recordKey}. */
    private static long copies(TimeData db, String recordKey) {
        return db.physicalKeys("events").stream().filter(k -> k.endsWith("\u0000" + recordKey)
            && !k.contains("!uidx")).count();
    }

    @Nested
    @DisplayName("camada fria")
    class Cold {

        @Test
        @DisplayName("outro nó conclui o congelamento durante o envio: o objeto em uso não é apagado")
        void takeoverDuringUpload_keepsLiveObject() {
            HookedStore store = new HookedStore(tmp.resolve("cold"));
            try (TimeData db = TimeData.rocks(tmp.resolve("db"), tier(store)).threeDays()) {
                toCold(db, DAY1);
                store.afterUpload.set(() -> assertEquals(1, maintenance(db).runOnce().movedCold()));

                assertEquals(0, maintenance(db).runOnce().movedCold());

                assertEquals(List.of("ana", "bia", "caio"), db.values("SELECT * FROM events", "who"));
            }
        }

        @Test
        @DisplayName("partição removida durante o envio continua removida")
        void dropDuringUpload_staysDropped() {
            HookedStore store = new HookedStore(tmp.resolve("cold"));
            try (TimeData db = TimeData.memory(tier(store)).threeDays()) {
                toCold(db, DAY1);
                store.afterUpload.set(() -> db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\""));

                assertEquals(0, maintenance(db).runOnce().movedCold());

                assertEquals(2, db.count("SHOW PARTITIONS ON TABLE events"));
                assertEquals(List.of("bia", "caio"), db.values("SELECT * FROM events", "who"));
            }
        }

        @Test
        @DisplayName("desistir durante o envio mantém as escritas feitas depois")
        void toHotDuringUpload_keepsWrites() {
            HookedStore store = new HookedStore(tmp.resolve("cold"));
            try (TimeData db = TimeData.memory(tier(store)).threeDays()) {
                toCold(db, DAY1);
                store.afterUpload.set(() -> {
                    db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO HOT");
                    db.run("UPDATE events:a MERGE {who: \"depois\"}");
                });

                assertEquals(0, maintenance(db).runOnce().movedCold());

                assertEquals(List.of("depois"), db.values("SELECT * FROM events:a", "who"));
            }
        }

        @Test
        @DisplayName("partição removida enquanto volta da camada fria não ressuscita")
        void dropDuringThaw_staysDropped() {
            HookedStore store = new HookedStore(tmp.resolve("cold"));
            try (TimeData db = TimeData.memory(tier(store)).threeDays()) {
                toCold(db, DAY1);
                maintenance(db).runOnce();
                db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO HOT");
                store.afterDownload.set(() -> db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\""));

                assertEquals(0, maintenance(db).runOnce().thawed());

                assertEquals(List.of("bia", "caio"), db.values("SELECT * FROM events", "who"));
            }
        }

        @Test
        @DisplayName("TTL definido com a partição fria alcança as linhas quando ela volta")
        void ttlDefinedWhileCold_expiresAfterThaw() {
            HookedStore store = new HookedStore(tmp.resolve("cold"));
            try (TimeData db = TimeData.memory(tier(store)).threeDays()) {
                toCold(db, DAY1);
                maintenance(db).runOnce();
                db.run("DEFINE TTL ON TABLE events FIELD at AFTER 1h");
                maintenance(db).runOnce();
                assertEquals(List.of("ana"), db.values("SELECT * FROM events", "who"));

                db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO HOT");
                maintenance(db).runOnce();

                assertEquals(0, db.count("SELECT * FROM events"));
            }
        }

        @Test
        @DisplayName("restore lógico antigo com objeto faltando é recusado sem tocar na base")
        void legacyRestore_missingObject_keepsDatabase() throws IOException {
            HookedStore store = new HookedStore(tmp.resolve("cold"));
            try (TimeData db = TimeData.rocks(tmp.resolve("db"), tier(store)).threeDays()) {
                toCold(db, DAY1);
                maintenance(db).runOnce();
                String dump = db.ds.backupCluster();
                for (String object : store.list("parts/")) {
                    store.delete(object);
                }

                assertThrows(AxonError.class, () -> db.ds.restoreCluster(dump));

                assertEquals(2, db.count("SELECT * FROM events WHERE at >= d\"2026-11-02T00:00:00Z\""));
            }
        }

        @Test
        @DisplayName("backup físico cujo bloqueio falha não fica na lista")
        void physicalBackup_holdFailure_removesBackup() {
            HookedStore store = new HookedStore(tmp.resolve("cold"));
            Path backupDir = tmp.resolve("backups");
            try (TimeData db = TimeData.rocks(tmp.resolve("db"), tier(store)).threeDays();
                 PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
                toCold(db, DAY1);
                maintenance(db).runOnce();
                store.refusedWrites = "holds/physical-";

                assertThrows(AxonError.class, () -> ColdBackups.createPhysical(db.ds, backups,
                    (RocksDbBackend) db.backend, backupDir));

                assertTrue(backups.list().isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("transações")
    class Transactions {

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("remover e regravar a mesma faixa numa transação não vê nem ressuscita o que foi removido")
        void dropThenReinsert(String storage) {
            try (TimeData db = storage.equals("rocks") ? TimeData.rocks(tmp.resolve("db")) : TimeData.memory()) {
                db.run("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
                db.threeDays();
                Session tx = TimeData.newSession();
                db.run(tx, "BEGIN");
                db.run(tx, "CREATE events:x CONTENT {at: d\"" + DAY1 + "\", who: \"xis\"}");
                db.run(tx, "DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");
                db.run(tx, "CREATE events:nova CONTENT {at: d\"" + DAY1 + "\", who: \"ana\"}");
                assertEquals(3, db.run(tx, "SELECT * FROM events").asArray().size());
                db.run(tx, "COMMIT");

                assertEquals(3, db.count("SELECT * FROM events"));
                assertEquals(0, db.count("SELECT * FROM events:a"));
                assertEquals(0, db.count("SELECT * FROM events:x"));
                assertEquals(0, copies(db, "sx") + copies(db, "sa"));
                assertEquals(1, db.count("SELECT * FROM events WHERE who = \"ana\""));
            }
        }

        @Test
        @DisplayName("o mesmo id em partições novas de duas transações: a segunda conflita")
        void sameIdInNewPartitions_conflicts() {
            try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
                Session one = TimeData.newSession();
                Session two = TimeData.newSession();
                db.run(one, "BEGIN");
                db.run(two, "BEGIN");
                db.run(one, "CREATE events:z CONTENT {at: d\"" + DAY1 + "\"}");
                db.run(two, "CREATE events:z CONTENT {at: d\"" + DAY2 + "\"}");
                db.run(one, "COMMIT");

                assertThrows(VersionConflictException.class, () -> db.run(two, "COMMIT"));

                assertEquals(1, db.count("SELECT * FROM events"));
            }
        }

        @Test
        @DisplayName("o mesmo valor único em partições novas de duas transações: a segunda conflita")
        void sameUniqueValueInNewPartitions_conflicts() {
            try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
                db.run("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
                Session one = TimeData.newSession();
                Session two = TimeData.newSession();
                db.run(one, "BEGIN");
                db.run(two, "BEGIN");
                db.run(one, "CREATE events:p CONTENT {at: d\"" + DAY1 + "\", who: \"u\"}");
                db.run(two, "CREATE events:q CONTENT {at: d\"" + DAY2 + "\", who: \"u\"}");
                db.run(one, "COMMIT");

                assertThrows(VersionConflictException.class, () -> db.run(two, "COMMIT"));

                assertEquals(1, db.count("SELECT * FROM events"));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("enquanto uma transação particiona a tabela ninguém escreve nela; cancelada, volta a ser comum")
        void definePending_blocksOthers(String storage) {
            try (TimeData db = storage.equals("rocks") ? TimeData.rocks(tmp.resolve("db")) : TimeData.memory()) {
                Session tx = TimeData.newSession();
                db.run(tx, "BEGIN");
                db.run(tx, "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");

                assertThrows(RuntimeException.class,
                    () -> db.run("CREATE events:fora CONTENT {at: d\"" + DAY1 + "\"}"));
                db.run(tx, "CANCEL");
                db.run("CREATE events:comum CONTENT {nada: 1}");

                assertEquals(1, db.count("SELECT * FROM events"));
            }
        }

        @Test
        @DisplayName("confirmada a definição, todos escrevem nas partições")
        void definePublishedOnCommit() {
            try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
                Session tx = TimeData.newSession();
                db.run(tx, "BEGIN");
                db.run(tx, "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
                db.run(tx, "CREATE events:dentro CONTENT {at: d\"" + DAY1 + "\"}");
                db.run(tx, "COMMIT");

                db.run("CREATE events:depois CONTENT {at: d\"" + DAY2 + "\"}");

                assertEquals(2, db.count("SELECT * FROM events"));
            }
        }
    }

    @Nested
    @DisplayName("escritas avulsas concorrentes")
    class Autocommit {

        private void concurrently(int threads, java.util.function.IntConsumer work) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int worker = t;
                tasks.add(pool.submit(() -> work.accept(worker)));
            }
            for (Future<?> task : tasks) {
                task.get(120, TimeUnit.SECONDS);
            }
            pool.shutdown();
        }

        @Test
        @DisplayName("mover e editar a mesma linha ao mesmo tempo nunca deixa duas cópias")
        void concurrentMoves_keepOneCopy() throws Exception {
            try (TimeData db = TimeData.rocks(tmp.resolve("db")).threeDays()) {
                String[] days = {DAY1, DAY2, DAY3};

                concurrently(8, worker -> {
                    Session s = TimeData.newSession();
                    for (int i = 0; i < 60; i++) {
                        db.run(s, worker % 2 == 0
                            ? "UPDATE events:a MERGE {at: d\"" + days[(worker + i) % 3] + "\"}"
                            : "UPDATE events:a MERGE {n: " + i + "}");
                    }
                });

                assertEquals(1, copies(db, "sa"));
                assertEquals(3, db.count("SELECT * FROM events"));
            }
        }

        @Test
        @DisplayName("criar o mesmo id em partições novas ao mesmo tempo deixa uma linha")
        void concurrentCreatesOfSameId_keepOneRow() throws Exception {
            try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
                for (int round = 0; round < 20; round++) {
                    int base = round * 10;
                    String id = "r" + round;

                    concurrently(6, worker -> db.run(TimeData.newSession(), "CREATE events:" + id + " CONTENT {at: d\""
                        + Instant.parse(DAY1).plus(Duration.ofDays(base + worker)) + "\"}"));

                    assertEquals(1, copies(db, "s" + id), "rodada " + round);
                }
            }
        }
    }

    /** Um evento que recusa uma linha não pode parar a fila do TTL. */
    @Test
    @DisplayName("uma linha que o TTL não consegue apagar não para as outras")
    void poisonedRow_doesNotStallTtl() {
        try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
            db.run("DEFINE EVENT recusa ON TABLE sessions WHEN $event = \"DELETE\" AND $before.who = \"ruim\" "
                + "THEN (ERROR \"nao\")");
            db.run("DEFINE TTL ON TABLE sessions FIELD expires");
            for (String who : List.of("ruim", "um", "dois")) {
                db.run("CREATE sessions:" + who + " CONTENT {who: \"" + who
                    + "\", expires: d\"2026-11-01T00:00:00Z\"}");
            }

            PartitionMaintenance.Report report = maintenance(db).runOnce();

            assertEquals(2, report.expiredRows());
            assertEquals(1, report.failures());
            assertEquals(List.of("ruim"), db.values("SELECT * FROM sessions", "who"));
        }
    }
}
