package com.axonbase.core.partition;

import com.axonbase.common.AxonError;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.cluster.CommitCoordinator;
import com.axonbase.core.cluster.CommittedBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Num líder recém eleito (ou depois de uma escrita sem desfecho), a barreira de validação é o momento em que as
 * entradas pendentes aparecem na base local. As condições de partição precisam ser conferidas depois dela.
 */
@DisplayName("Partições em cluster: condições conferidas depois da barreira")
class PartitionBarrierTest {

    private static final long FIRST_DAY = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
    private static final String LATE_INSERT =
        "CREATE events:novo CONTENT {at: d\"2026-11-01T11:00:00Z\", who: \"novo\"}";

    /** Coordenador em que a barreira aplica, uma vez, a entrada que ainda estava pendente. */
    private static final class PendingEntry implements CommitCoordinator {
        final AtomicReference<Runnable> pending = new AtomicReference<>();

        @Override
        public long beginValidation() {
            Runnable entry = pending.getAndSet(null);
            if (entry != null) {
                entry.run();
            }
            return 0;
        }

        @Override
        public long confirm(String nodeId, CommittedBatch batch) {
            return 1;
        }
    }

    private static PendingEntry cluster(TimeData db) {
        PendingEntry coordinator = new PendingEntry();
        db.ds.commitCoordinator(coordinator, "n1");
        return coordinator;
    }

    @Test
    @DisplayName("DROP PARTITION pendente: a linha confirmada continua visível")
    void insertDuringPendingDrop_isNotLost() {
        try (TimeData db = TimeData.memory().threeDays()) {
            Database database = db.ds.findDatabase("test", "dev");
            cluster(db).pending.set(() -> {
                PartitionChanges changes = new PartitionChanges();
                changes.drop(database, "events", FIRST_DAY);
                changes.apply(database, "events", database.records());
            });

            db.run(LATE_INSERT);

            assertEquals(List.of("novo"), db.values("SELECT * FROM events:novo", "who"));
            assertEquals(List.of("bia", "caio", "novo"), db.values("SELECT * FROM events", "who"));
        }
    }

    @Test
    @DisplayName("congelamento pendente: a escrita é recusada em vez de entrar numa partição somente leitura")
    void insertDuringPendingFreeze_isRefused() {
        try (TimeData db = TimeData.memory().threeDays()) {
            Database database = db.ds.findDatabase("test", "dev");
            cluster(db).pending.set(() -> {
                PartitionDescriptor first = PartitionStore.read(database, database.records(), "events", FIRST_DAY)
                    .orElseThrow();
                PartitionStore.save(database, database.records(), "events", first.freezing("parts/pendente"));
            });

            assertThrows(AxonError.class, () -> db.run(LATE_INSERT));

            assertEquals(0, db.count("SELECT * FROM events:novo"));
            assertEquals(List.of("ana"), db.values(
                "SELECT * FROM events WHERE at < d\"2026-11-02T00:00:00Z\"", "who"));
        }
    }
}
