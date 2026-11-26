package com.axonbase.core.partition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E4, fatia 2: retenção por partição e TTL em segundo plano. */
@DisplayName("Manutenção: retenção e TTL")
class MaintenanceTest {

    @TempDir
    Path tmp;

    private final AtomicLong now = new AtomicLong(at("2026-11-04T00:00:00Z"));

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    private TimeData open(String storage) {
        return storage.equals("rocks") ? TimeData.rocks(tmp.resolve("db")) : TimeData.memory();
    }

    private PartitionMaintenance maintenance(TimeData db) {
        return new PartitionMaintenance(db.ds, PartitionMaintenance.Options.defaults()
            .withTtlPause(Duration.ZERO).withFullScanEvery(1), now::get);
    }

    private static List<String> ttlEntries(TimeData db, String table) {
        return db.backend.keysWithPrefix(PartitionKeys.ttlPrefix("test", "dev", table));
    }

    private static void session(TimeData db, String id, String expires) {
        db.run("CREATE sessions:" + id + " CONTENT {who: \"" + id + "\", expires: d\"" + expires + "\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "rocks"})
    @DisplayName("retenção remove só as partições que terminaram há mais que o prazo")
    void retention_dropsOldPartitions(String storage) {
        try (TimeData db = open(storage)) {
            db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d RETENTION 1d");
            db.insertThreeDays();

            PartitionMaintenance.Report report = maintenance(db).runOnce();

            assertEquals(2, report.droppedPartitions());
            assertEquals(0, report.failures());
            assertEquals(List.of("caio"), db.values("SELECT * FROM events", "who"));
            assertEquals(0, maintenance(db).runOnce().droppedPartitions());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "rocks"})
    @DisplayName("TTL remove as linhas vencidas, com os índices, e deixa as demais")
    void ttl_removesExpiredRows(String storage) {
        try (TimeData db = open(storage)) {
            db.run("DEFINE INDEX quem ON TABLE sessions COLUMNS who UNIQUE");
            db.run("DEFINE TTL ON TABLE sessions FIELD expires");
            session(db, "old", "2026-11-01T00:00:00Z");
            session(db, "edge", "2026-11-04T00:00:00Z");
            session(db, "new", "2026-12-01T00:00:00Z");
            db.run("CREATE sessions:forever CONTENT {who: \"forever\"}");

            PartitionMaintenance.Report report = maintenance(db).runOnce();

            assertEquals(2, report.expiredRows());
            assertEquals(List.of("forever", "new"), db.values("SELECT * FROM sessions", "who"));
            assertEquals(1, ttlEntries(db, "sessions").size());
            session(db, "old", "2027-01-01T00:00:00Z");
            assertEquals(3, db.count("SELECT * FROM sessions"));
        }
    }

    @Test
    @DisplayName("AFTER soma o prazo ao campo")
    void ttl_afterOffset() {
        try (TimeData db = TimeData.memory()) {
            db.run("DEFINE TTL ON TABLE sessions FIELD created AFTER 1d");
            db.run("CREATE sessions:a CONTENT {created: d\"2026-11-02T00:00:00Z\"}");
            db.run("CREATE sessions:b CONTENT {created: d\"2026-11-03T12:00:00Z\"}");

            maintenance(db).runOnce();

            assertEquals(1, db.count("SELECT * FROM sessions"));
            assertEquals(1, db.count("SELECT * FROM sessions:b"));
        }
    }

    @Test
    @DisplayName("entrada antiga de uma linha renovada ou sem o campo é descartada sem apagar a linha")
    void ttl_staleEntries_keepRow() {
        try (TimeData db = TimeData.memory()) {
            db.run("DEFINE TTL ON TABLE sessions FIELD expires");
            session(db, "renewed", "2026-11-01T00:00:00Z");
            session(db, "unset", "2026-11-01T00:00:00Z");
            db.run("UPDATE sessions:renewed MERGE {expires: d\"2027-01-01T00:00:00Z\"}");
            db.run("UPDATE sessions:unset MERGE {expires: NONE}");

            assertEquals(0, maintenance(db).runOnce().expiredRows());

            assertEquals(2, db.count("SELECT * FROM sessions"));
            assertEquals(1, ttlEntries(db, "sessions").size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "rocks"})
    @DisplayName("TTL definido depois alcança as linhas que já existiam")
    void ttl_backfillsExistingRows(String storage) {
        try (TimeData db = open(storage)) {
            for (int i = 0; i < 25; i++) {
                session(db, "s" + i, i < 15 ? "2026-11-01T00:00:00Z" : "2027-01-01T00:00:00Z");
            }
            db.run("DEFINE TTL ON TABLE sessions FIELD expires");
            PartitionMaintenance maintenance = new PartitionMaintenance(db.ds,
                PartitionMaintenance.Options.defaults().withTtlBatch(4).withTtlPause(Duration.ZERO)
                    .withFullScanEvery(1), now::get);

            maintenance.runOnce();
            maintenance.runOnce();

            assertEquals(10, db.count("SELECT * FROM sessions"));
            assertTrue(db.backend.keysWithPrefix("!ttlfill|").isEmpty());
        }
    }

    @Test
    @DisplayName("o teto por rodada limita o trabalho, e a rodada seguinte continua de onde parou")
    void ttl_respectsCapPerRun() {
        try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
            db.run("DEFINE TTL ON TABLE sessions FIELD expires");
            for (int i = 0; i < 30; i++) {
                session(db, "s" + i, "2026-11-01T00:00:00Z");
            }
            PartitionMaintenance maintenance = new PartitionMaintenance(db.ds,
                PartitionMaintenance.Options.defaults().withTtlBatch(5).withTtlMaxPerRun(10)
                    .withTtlPause(Duration.ZERO), now::get);

            assertEquals(10, maintenance.runOnce().expiredRows());
            assertEquals(10, maintenance.runOnce().expiredRows());
            assertEquals(10, maintenance.runOnce().expiredRows());
            assertEquals(0, db.count("SELECT * FROM sessions"));
        }
    }

    @Test
    @DisplayName("TTL numa tabela particionada remove a linha de dentro da partição")
    void ttl_onPartitionedTable() {
        try (TimeData db = TimeData.memory().threeDays()) {
            db.run("DEFINE TTL ON TABLE events FIELD at AFTER 2d");
            maintenance(db).runOnce(); // preenche as entradas das linhas existentes

            maintenance(db).runOnce();

            assertEquals(List.of("bia", "caio"), db.values("SELECT * FROM events", "who"));
        }
    }

    @Test
    @DisplayName("DROP TTL para a remoção e apaga as entradas")
    void dropTtl_stopsExpiry() {
        try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
            db.run("DEFINE TTL ON TABLE sessions FIELD expires");
            session(db, "old", "2026-11-01T00:00:00Z");
            db.run("DROP TTL ON TABLE sessions");

            assertEquals(0, maintenance(db).runOnce().expiredRows());

            assertTrue(ttlEntries(db, "sessions").isEmpty());
            assertEquals(1, db.count("SELECT * FROM sessions"));
        }
    }

    @Test
    @DisplayName("a definição de TTL e as entradas sobrevivem ao reinício")
    void ttl_survivesRestart() {
        try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
            db.run("DEFINE TTL ON TABLE sessions FIELD expires");
            session(db, "old", "2026-11-01T00:00:00Z");

            db.restart();

            assertEquals(1, maintenance(db).runOnce().expiredRows());
        }
    }

    @Test
    @DisplayName("as opções vêm do ambiente, e valor inválido cai no padrão")
    void options_fromEnvironment() {
        PartitionMaintenance.Options set = PartitionMaintenance.Options.fromEnvironment(java.util.Map.of(
            "AXON_PARTITION_MAINTENANCE_SECONDS", "0", "AXON_TTL_BATCH", "50", "AXON_TTL_MAX_PER_RUN", "muito"));

        assertTrue(set.interval().isZero());
        assertEquals(50, set.ttlBatch());
        assertEquals(PartitionMaintenance.Options.defaults().ttlMaxPerRun(), set.ttlMaxPerRun());
    }

    @Test
    @DisplayName("a thread de manutenção roda sozinha e para no close")
    void background_runsAndStops() throws InterruptedException {
        try (TimeData db = TimeData.memory()) {
            db.run("DEFINE TTL ON TABLE sessions FIELD expires");
            session(db, "old", "2026-11-01T00:00:00Z");

            try (PartitionMaintenance maintenance = new PartitionMaintenance(db.ds,
                    PartitionMaintenance.Options.defaults().withInterval(Duration.ofMillis(20)), now::get)) {
                maintenance.start();
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (db.count("SELECT * FROM sessions") > 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
            }

            assertEquals(0, db.count("SELECT * FROM sessions"));
        }
    }
}
