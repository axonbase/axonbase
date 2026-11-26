package com.axonbase.core.partition;

import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.axonbase.core.partition.TimeData.DAY1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E4, fatia 3: mover partição para a camada fria, ler transparente, trazer de volta e coletar lixo. */
@DisplayName("Camada fria")
class ColdTierTest {

    @TempDir
    Path tmp;

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-11-04T00:00:00Z").toEpochMilli());

    private ColdTier tier() {
        return ColdTier.of(new FileSystemColdStore(tmp.resolve("cold")), tmp.resolve("cache"),
            ColdTier.Settings.defaults().withGrace(Duration.ofDays(2)).withGcInterval(Duration.ZERO));
    }

    private TimeData open(String storage) {
        return storage.equals("rocks") ? TimeData.rocks(tmp.resolve("db"), tier()) : TimeData.memory(tier());
    }

    private PartitionMaintenance maintenance(TimeData db) {
        return new PartitionMaintenance(db.ds, PartitionMaintenance.Options.defaults()
            .withTtlPause(Duration.ZERO).withFullScanEvery(1), now::get);
    }

    /** Três dias, com o primeiro já na camada fria. */
    private TimeData firstDayCold(String storage) {
        TimeData db = open(storage);
        db.run("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
        db.threeDays();
        db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO COLD");
        assertEquals(1, maintenance(db).runOnce().movedCold());
        return db;
    }

    private static List<String> states(TimeData db) {
        return db.rows("SHOW PARTITIONS ON TABLE events").stream()
            .map(p -> p.asObject().get("state").asString()).toList();
    }

    private List<Path> objects() throws IOException {
        Path parts = tmp.resolve("cold").resolve("parts");
        if (!Files.exists(parts)) {
            return List.of();
        }
        try (var files = Files.walk(parts)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static List<String> localRecords(TimeData db) {
        return db.physicalKeys("events").stream()
            .map(k -> PartitionKeys.record(k.substring(PartitionKeys.dbPrefix("test", "dev").length())))
            .filter(java.util.Objects::nonNull).map(PartitionKeys.StoredRecord::recordKey).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "rocks"})
    @DisplayName("a partição sai do disco local, vira um objeto e continua consultável")
    void move_removesLocalRecordsAndReadsTransparently(String storage) throws IOException {
        try (TimeData db = firstDayCold(storage)) {
            assertEquals(List.of("COLD", "HOT", "HOT"), states(db));
            assertEquals(List.of("sb", "sc"), localRecords(db));
            assertEquals(1, objects().size());
            AxonValue cold = db.rows("SHOW PARTITIONS ON TABLE events").get(0);
            assertEquals(1L, cold.asObject().get("rows").asLong());

            assertEquals(List.of("ana", "bia", "caio"), db.values("SELECT * FROM events", "who"));
            assertEquals(List.of("ana"), db.values("SELECT * FROM events:a", "who"));
            assertEquals(List.of("ana"), db.values(
                "SELECT * FROM events WHERE at < d\"2026-11-02T00:00:00Z\"", "who"));
            assertEquals(List.of("ana"), db.values("SELECT * FROM events WHERE who = \"ana\"", "who"));
            AxonValue total = db.rows("SELECT count() AS n FROM events GROUP ALL").get(0);
            assertEquals(3L, total.asObject().get("n").asLong());
        }
    }

    @Test
    @DisplayName("consulta podada fora da faixa fria não baixa o objeto; id ausente é recusado pelo filtro")
    void pruningAndBloom_avoidDownloads() {
        try (TimeData db = firstDayCold("memory")) {
            long before = db.ds.coldTier().cache().downloads();

            db.values("SELECT * FROM events WHERE at >= d\"2026-11-02T00:00:00Z\"", "who");
            assertEquals(0, db.count("SELECT * FROM events:naoexiste"));
            db.run("CREATE events:novo CONTENT {at: d\"2026-11-03T11:00:00Z\", who: \"novo\"}");

            assertEquals(before, db.ds.coldTier().cache().downloads());
        }
    }

    @Test
    @DisplayName("partição fria é somente leitura, e a unicidade continua valendo contra ela")
    void coldPartition_isReadOnly() {
        try (TimeData db = firstDayCold("rocks")) {
            for (String write : List.of(
                    "UPDATE events:a MERGE {who: \"x\"}",
                    "DELETE events:a",
                    "CREATE events:a CONTENT {at: d\"2026-11-03T11:00:00Z\", who: \"y\"}",
                    "CREATE events:z CONTENT {at: d\"" + DAY1 + "\", who: \"z\"}",
                    "CREATE events:w CONTENT {at: d\"2026-11-03T11:00:00Z\", who: \"ana\"}")) {
                assertThrows(RuntimeException.class, () -> db.run(write), write);
            }
            assertEquals(List.of("ana", "bia", "caio"), db.values("SELECT * FROM events", "who"));
            assertEquals(List.of("ana"), db.values("SELECT * FROM events WHERE who = \"ana\"", "who"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "rocks"})
    @DisplayName("TO HOT traz as linhas de volta, graváveis, e o prazo não as leva de novo")
    void thaw_bringsRowsBack(String storage) {
        try (TimeData db = open(storage)) {
            db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d COLD AFTER 1d");
            db.insertThreeDays();
            assertEquals(2, maintenance(db).runOnce().movedCold());
            db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO HOT");

            PartitionMaintenance.Report report = maintenance(db).runOnce();

            assertEquals(1, report.thawed());
            assertEquals(0, report.movedCold());
            assertEquals(List.of("HOT", "COLD", "HOT"), states(db));
            db.run("UPDATE events:a MERGE {who: \"ana2\"}");
            assertEquals(List.of("ana2", "bia", "caio"), db.values("SELECT * FROM events", "who"));
            assertEquals(0, maintenance(db).runOnce().movedCold());
        }
    }

    @Test
    @DisplayName("COLD AFTER move sozinho as partições que passaram do prazo")
    void coldAfter_movesAutomatically() {
        try (TimeData db = TimeData.memory(tier())) {
            db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d COLD AFTER 1d");
            db.insertThreeDays();

            maintenance(db).runOnce();

            assertEquals(List.of("COLD", "COLD", "HOT"), states(db));
        }
    }

    @Test
    @DisplayName("congelamento interrompido é retomado, e desistir (TO HOT) libera a escrita")
    void freezing_resumesOrReverts() {
        try (TimeData db = TimeData.memory(tier()).threeDays()) {
            db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO COLD");
            assertEquals(List.of("FREEZING", "HOT", "HOT"), states(db));
            assertThrows(RuntimeException.class, () -> db.run("DELETE events:a"));
            assertEquals(3, db.count("SELECT * FROM events"));

            db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO HOT");
            db.run("UPDATE events:a MERGE {who: \"livre\"}");

            assertEquals(0, maintenance(db).runOnce().movedCold());
            db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO COLD");
            assertEquals(1, maintenance(db).runOnce().movedCold());
            assertEquals(List.of("livre"), db.values("SELECT * FROM events:a", "who"));
        }
    }

    @Test
    @DisplayName("sem camada fria configurada o pedido é recusado")
    void move_withoutColdTier_rejected() {
        try (TimeData db = TimeData.memory().threeDays()) {
            RuntimeException e = assertThrows(RuntimeException.class,
                () -> db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO COLD"));

            assertTrue(e.getMessage().contains("AXON_COLD_DIR"));
        }
    }

    @Test
    @DisplayName("com o armazenamento fora, a consulta falha em vez de devolver parte das linhas")
    void unavailableStore_failsQueries() throws IOException {
        try (TimeData db = firstDayCold("memory")) {
            for (Path object : objects()) {
                Files.delete(object);
            }

            assertThrows(RuntimeException.class, () -> db.run("SELECT * FROM events"));
            assertEquals(2, db.count("SELECT * FROM events WHERE at >= d\"2026-11-02T00:00:00Z\""));
        }
    }

    @Test
    @DisplayName("reinício mantém a partição fria legível, e o dump lógico a inclui")
    void restartAndExport_keepColdData() {
        try (TimeData db = firstDayCold("rocks"); TimeData copy = TimeData.memory()) {
            db.restart(tier());

            assertEquals(List.of("ana", "bia", "caio"), db.values("SELECT * FROM events", "who"));
            copy.run(db.ds.exportDatabase("test", "dev"));
            assertEquals(List.of("ana", "bia", "caio"), copy.values("SELECT * FROM events", "who"));
        }
    }

    @Test
    @DisplayName("índice criado depois alcança as linhas frias")
    void indexBuiltLater_coversColdRows() {
        try (TimeData db = TimeData.memory(tier()).threeDays()) {
            db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO COLD");
            maintenance(db).runOnce();

            db.run("CREATE INDEX quem_col ON TABLE events COLUMNS who COLUMNAR");

            assertEquals(List.of("ana"), db.values("SELECT * FROM events WHERE who = \"ana\"", "who"));
        }
    }

    @Test
    @DisplayName("objeto de partição removida só é apagado depois da carência")
    void garbage_waitsGracePeriod() throws IOException {
        try (TimeData db = firstDayCold("memory")) {
            db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");

            assertEquals(0, maintenance(db).runOnce().collectedObjects());
            now.addAndGet(Duration.ofDays(1).toMillis());
            assertEquals(0, maintenance(db).runOnce().collectedObjects());
            assertEquals(1, objects().size());

            now.addAndGet(Duration.ofDays(2).toMillis());
            assertEquals(1, maintenance(db).runOnce().collectedObjects());
            assertTrue(objects().isEmpty());
        }
    }

    @Test
    @DisplayName("objeto em uso ou preso por um bloqueio nunca é apagado")
    void garbage_respectsReferencesAndHolds() throws IOException {
        try (TimeData db = firstDayCold("memory")) {
            maintenance(db).runOnce();
            now.addAndGet(Duration.ofDays(30).toMillis());
            assertEquals(0, maintenance(db).runOnce().collectedObjects());

            String object = ColdGarbage.referenced(db.ds).iterator().next();
            db.ds.coldTier().holds().put("backup-teste", java.util.Set.of(object),
                com.axonbase.core.partition.cold.ColdHolds.NEVER);
            db.run("DROP TABLE events");
            maintenance(db).runOnce();
            now.addAndGet(Duration.ofDays(30).toMillis());

            assertEquals(0, maintenance(db).runOnce().collectedObjects());
            db.ds.coldTier().holds().release("backup-teste");
            maintenance(db).runOnce();
            now.addAndGet(Duration.ofDays(30).toMillis());
            assertEquals(1, maintenance(db).runOnce().collectedObjects());
        }
    }

    @Test
    @DisplayName("a configuração vem do ambiente; sem variável fica desligada e as duas juntas são recusadas")
    void fromEnvironment() {
        assertFalse(ColdTier.fromEnvironment(Map.of()).configured());
        try (ColdTier tier = ColdTier.fromEnvironment(Map.of(
                "AXON_COLD_DIR", tmp.resolve("env").toString(), "AXON_COLD_CACHE_DIR", tmp.resolve("c").toString(),
                "AXON_COLD_CACHE_MB", "5", "AXON_COLD_GC_GRACE_DAYS", "9"))) {
            assertTrue(tier.configured());
            assertEquals(5L << 20, tier.settings().cacheBytes());
            assertEquals(Duration.ofDays(9), tier.settings().grace());
        }
        assertThrows(IllegalArgumentException.class, () -> ColdTier.fromEnvironment(
            Map.of("AXON_COLD_DIR", "x", "AXON_COLD_S3", "s3://b/p")));
        assertThrows(IllegalArgumentException.class, () -> ColdTier.fromEnvironment(
            Map.of("AXON_COLD_DIR", tmp.resolve("env2").toString(), "AXON_COLD_CACHE_MB", "muito")));
    }

    @Test
    @DisplayName("reinício no meio do congelamento: a partição reabre inteira e o envio recomeça")
    void interruptedUpload_resumesAfterReopen() {
        Path database = tmp.resolve("db");
        try (TimeData db = TimeData.rocks(database, tier()).threeDays()) {
            db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO COLD");
            db.restart(tier());

            assertEquals(3, db.count("SELECT * FROM events"));
            assertEquals(1, maintenance(db).runOnce().movedCold());
            assertEquals(3, db.count("SELECT * FROM events"));
        }
    }
}
