package com.axonbase.core.partition;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.journal.PitrJournal;
import com.axonbase.core.journal.PitrRestore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.axonbase.core.partition.TimeData.DAY1;
import static com.axonbase.core.partition.TimeData.DAY2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E4, fatia 4: backups referenciam os objetos frios, bloqueiam a retenção deles e o restore os confere. */
@DisplayName("Backups com camada fria")
class ColdBackupTest {

    @TempDir
    Path tmp;

    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());

    private ColdTier tier() {
        return ColdTier.of(new FileSystemColdStore(tmp.resolve("cold")), tmp.resolve("cache"),
            ColdTier.Settings.defaults().withGrace(Duration.ZERO).withGcInterval(Duration.ZERO));
    }

    private PartitionMaintenance maintenance(TimeData db) {
        return new PartitionMaintenance(db.ds, PartitionMaintenance.Options.defaults()
            .withTtlPause(Duration.ZERO), now::get);
    }

    private TimeData firstDayCold() {
        TimeData db = TimeData.rocks(tmp.resolve("db"), tier()).threeDays();
        cool(db, DAY1);
        return db;
    }

    private void cool(TimeData db, String day) {
        db.run("ALTER PARTITION ON TABLE events AT d\"" + day + "\" TO COLD");
        assertEquals(1, maintenance(db).runOnce().movedCold());
    }

    /** Duas passadas com um dia entre elas: com carência zero, tudo que pode ser apagado sai. */
    private int collect(TimeData db) {
        maintenance(db).runOnce();
        now.addAndGet(Duration.ofDays(1).toMillis());
        return maintenance(db).runOnce().collectedObjects();
    }

    private static List<Path> files(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private static long size(Path directory) throws IOException {
        long total = 0;
        for (Path file : files(directory)) {
            total += Files.size(file);
        }
        return total;
    }

    @Test
    @DisplayName("o backup físico não copia o objeto, restaura a referência e segura o objeto contra a coleta")
    void physical_referencesAndHolds() throws IOException {
        Path backupDir = tmp.resolve("backups");
        try (TimeData db = firstDayCold(); PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            RocksDbBackend rocks = (RocksDbBackend) db.backend;
            PhysicalBackups.Info info = ColdBackups.createPhysical(db.ds, backups, rocks, backupDir);

            assertEquals(1, files(tmp.resolve("cold").resolve("parts")).size());
            assertEquals(1, db.ds.coldTier().holds().names(ColdBackups.physicalPrefix(backupDir)).size());
            db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");
            assertEquals(0, collect(db));

            Path restored = tmp.resolve("restored");
            PhysicalBackups.restore(backupDir, info.id(), restored);
            ColdBackups.requireRestorable(db.ds, restored);
            try (ColdTier cold = tier()) {
                Datastore other = new Datastore(
                    new RocksDbBackend(restored.toString(), RocksDbBackend.SyncMode.OFF), cold);
                assertEquals(3, other.execute("SELECT * FROM events", TimeData.newSession(), null)
                    .asArray().size());
                assertEquals(1, cold.cache().downloads()); // veio do armazenamento, não do backup
                other.rocksBackend().orElseThrow().close();
            }
        }
    }

    @Test
    @DisplayName("restaurar sem os objetos frios é recusado antes da troca")
    void restore_missingObject_refused() throws IOException {
        Path backupDir = tmp.resolve("backups");
        try (TimeData db = firstDayCold(); PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            PhysicalBackups.Info info =
                ColdBackups.createPhysical(db.ds, backups, (RocksDbBackend) db.backend, backupDir);
            for (Path object : files(tmp.resolve("cold").resolve("parts"))) {
                Files.delete(object);
            }
            Path restored = tmp.resolve("restored");
            PhysicalBackups.restore(backupDir, info.id(), restored);

            AxonError refused = assertThrows(AxonError.class,
                () -> ColdBackups.requireRestorable(db.ds, restored));

            assertTrue(refused.getMessage().contains("parts/test/dev/events/"));
        }
    }

    @Test
    @DisplayName("backup apagado pela retenção passa a ter prazo, e só depois dele o objeto sai")
    void purge_turnsHoldIntoExpiringHold() throws IOException {
        Path backupDir = tmp.resolve("backups");
        try (TimeData db = firstDayCold(); PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            RocksDbBackend rocks = (RocksDbBackend) db.backend;
            ColdBackups.createPhysical(db.ds, backups, rocks, backupDir);
            db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");
            ColdBackups.createPhysical(db.ds, backups, rocks, backupDir);
            backups.purgeKeeping(1);

            ColdBackups.releasePurged(db.ds, backupDir, backups.list());

            assertEquals(0, collect(db));
            now.addAndGet(Duration.ofDays(40).toMillis());
            assertEquals(1, collect(db));
        }
    }

    @Test
    @DisplayName("o backup lógico leva a referência, não o conteúdo, e restaura legível")
    void logical_referencesWithoutCopying() throws IOException {
        try (TimeData db = TimeData.rocks(tmp.resolve("db"), tier())) {
            db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
            String filler = "x".repeat(2_000);
            for (int i = 0; i < 200; i++) {
                db.run("CREATE events:r" + i + " CONTENT {at: d\"" + DAY1 + "\", who: \"" + filler + "\"}");
            }
            ByteArrayOutputStream before = new ByteArrayOutputStream();
            db.ds.backupTo(before, 0);
            cool(db, DAY1);
            ByteArrayOutputStream after = new ByteArrayOutputStream();

            db.ds.backupTo(after, 0);

            assertTrue(after.size() < before.size() / 10, after.size() + " de " + before.size());
            assertEquals(1, db.ds.coldTier().holds().names("logical-").size());
            Path file = Files.write(tmp.resolve("dump.axbak"), after.toByteArray());
            try (ColdTier cold = tier();
                 RocksDbBackend target = new RocksDbBackend(tmp.resolve("copy").toString(),
                     RocksDbBackend.SyncMode.OFF)) {
                Datastore copy = new Datastore(target, cold);
                copy.restoreFile(file);
                assertEquals(200, copy.execute("SELECT * FROM events", TimeData.newSession(), null)
                    .asArray().size());
            }
        }
    }

    @Test
    @DisplayName("restaurar um incremental que remove a partição fria aplica a remoção")
    void logical_incrementalCarriesRangeDelete() throws IOException {
        try (TimeData db = firstDayCold()) {
            ByteArrayOutputStream full = new ByteArrayOutputStream();
            long[] mark = new long[1];
            db.ds.backupTo(full, 0, "local", (incremental, version) -> mark[0] = version);
            db.run("DROP PARTITION ON TABLE events AT d\"" + DAY2 + "\"");
            db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");
            ByteArrayOutputStream delta = new ByteArrayOutputStream();
            db.ds.backupTo(delta, mark[0]);
            Path chain = tmp.resolve("chain.axbak");
            Files.write(chain, full.toByteArray());
            Files.write(chain, delta.toByteArray(), java.nio.file.StandardOpenOption.APPEND);

            try (ColdTier cold = tier();
                 RocksDbBackend target = new RocksDbBackend(tmp.resolve("copy").toString(),
                     RocksDbBackend.SyncMode.OFF)) {
                Datastore copy = new Datastore(target, cold);
                copy.restoreFile(chain);
                assertEquals(1, copy.execute("SELECT * FROM events", TimeData.newSession(), null)
                    .asArray().size());
            }
        }
    }

    @Test
    @DisplayName("base com partição fria num motor sem camada fria não aceita backup")
    void backup_withoutColdTier_fails() {
        try (TimeData db = firstDayCold()) {
            db.restart(ColdTier.NONE);

            AxonError e = assertThrows(AxonError.class, () -> db.ds.backupCluster());

            assertTrue(e.getMessage().contains("AXON_COLD_DIR"));
        }
    }

    @Test
    @DisplayName("PITR antes e depois de remover a partição, e antes de ela ir para a camada fria")
    void pitr_aroundDropAndColdMove() throws Exception {
        Path live = tmp.resolve("db");
        Path backupDir = tmp.resolve("backups");
        Path journalDir = tmp.resolve("journal");
        try (TimeData db = TimeData.rocks(live, tier());
             PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            RocksDbBackend rocks = (RocksDbBackend) db.backend;
            try (PitrJournal journal = PitrJournal.attach(rocks, journalDir,
                    JournalOptions.defaults())) {
                db.threeDays();
                int base = ColdBackups.createPhysical(db.ds, backups, rocks, backupDir).id();
                db.run("CREATE events:d CONTENT {at: d\"" + DAY2 + "\", who: \"davi\"}");
                long local = pause();
                cool(db, DAY1);
                long cold = pause();
                db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");
                db.run("DROP PARTITION ON TABLE events AT d\"" + DAY2 + "\"");
                long dropped = pause();
                journal.seal();

                assertEquals(List.of("ana", "bia", "caio", "davi"),
                    restored(db.ds, backups, backupDir, base, journalDir, live, local, "local"));
                assertEquals(List.of("ana", "bia", "caio", "davi"),
                    restored(db.ds, backups, backupDir, base, journalDir, live, cold, "cold"));
                assertEquals(List.of("caio"),
                    restored(db.ds, backups, backupDir, base, journalDir, live, dropped, "dropped"));
            }
        }
    }

    /** Um instante entre duas operações, com folga de alguns milissegundos dos dois lados. */
    private static long pause() throws InterruptedException {
        Thread.sleep(5);
        long instant = System.currentTimeMillis();
        Thread.sleep(5);
        return instant;
    }

    private List<String> restored(Datastore ds, PhysicalBackups backups, Path backupDir, int base, Path journalDir,
                                  Path live, long instant, String name) throws IOException {
        PitrRestore.Prepared prepared = PitrRestore.prepare(backupDir, base, journalDir, live,
            HlcTimestamp.endOfMillisecond(instant));
        ColdBackups.requireRestorable(ds, prepared.directory());
        Path kept = tmp.resolve("pitr-" + name);
        Files.move(prepared.directory(), kept);
        try (ColdTier cold = tier();
             RocksDbBackend target = new RocksDbBackend(kept.toString(), RocksDbBackend.SyncMode.OFF)) {
            return new Datastore(target, cold).execute("SELECT * FROM events", TimeData.newSession(), null)
                .asArray().stream().map(r -> r.asObject().get("who").asString()).sorted().toList();
        }
    }
}
