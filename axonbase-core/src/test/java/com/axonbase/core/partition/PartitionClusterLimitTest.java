package com.axonbase.core.partition;

import com.axonbase.core.partition.cold.FileSystemColdStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.axonbase.core.partition.TimeData.DAY1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Em cluster, a volta de uma partição fria é uma única entrada do log Raft. Acima do teto ela não replica e as escritas
 * seguintes param, então o motor recusa antes. O teto aqui é de 1 byte, para qualquer partição passar dele.
 */
@DisplayName("Camada fria em cluster: teto de tamanho da partição")
class PartitionClusterLimitTest {

    @TempDir
    Path tmp;

    private final long now = Instant.parse("2026-11-04T00:00:00Z").toEpochMilli();

    private TimeData open() {
        return TimeData.memory(ColdTier.of(new FileSystemColdStore(tmp.resolve("cold")), tmp.resolve("cache"),
            ColdTier.Settings.defaults().withClusterBytes(1))).threeDays();
    }

    private static void cluster(TimeData db) {
        db.ds.commitCoordinator((node, batch) -> 1, "n1");
    }

    private PartitionMaintenance.Report maintain(TimeData db) {
        return new PartitionMaintenance(db.ds, PartitionMaintenance.Options.defaults()
            .withTtlPause(Duration.ZERO), () -> now).runOnce();
    }

    private static String firstState(TimeData db) {
        return db.rows("SHOW PARTITIONS ON TABLE events").get(0).asObject().get("state").asString();
    }

    private static void alter(TimeData db, String tier) {
        db.run("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO " + tier);
    }

    @Test
    @DisplayName("partição acima do teto não vai para a camada fria e continua aceitando escrita")
    void tooLargeToFreeze_staysHot() {
        try (TimeData db = open()) {
            cluster(db);
            alter(db, "COLD");

            assertEquals(0, maintain(db).movedCold());

            assertEquals("HOT", firstState(db));
            db.run("UPDATE events:a MERGE {who: \"depois\"}");
            assertEquals(List.of("bia", "caio", "depois"), db.values("SELECT * FROM events", "who"));
        }
    }

    @Test
    @DisplayName("TO HOT de partição fria acima do teto é recusado com erro")
    void tooLargeToThaw_isRefused() {
        try (TimeData db = open()) {
            alter(db, "COLD");
            assertEquals(1, maintain(db).movedCold());
            cluster(db);

            assertThrows(RuntimeException.class, () -> alter(db, "HOT"));

            assertEquals("COLD", firstState(db));
        }
    }

    @Test
    @DisplayName("partição que já estava voltando não gera a entrada gigante, continua legível e aceita TO COLD")
    void pendingThaw_isNotProposed() {
        try (TimeData db = open()) {
            alter(db, "COLD");
            assertEquals(1, maintain(db).movedCold());
            alter(db, "HOT");
            cluster(db);

            assertEquals(0, maintain(db).thawed());

            assertEquals("THAWING", firstState(db));
            assertEquals(List.of("ana", "bia", "caio"), db.values("SELECT * FROM events", "who"));
            alter(db, "COLD");
            assertEquals("COLD", firstState(db));
        }
    }
}
