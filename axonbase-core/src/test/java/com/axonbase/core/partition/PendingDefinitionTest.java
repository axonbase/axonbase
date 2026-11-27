package com.axonbase.core.partition;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.AppliedBatchListener;
import com.axonbase.core.cluster.CommitCoordinator;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Um líder recém eleito pode ter no log uma definição do líder anterior que a base ainda não aplicou, ou que o catálogo
 * em memória ainda não releu. Uma escrita executada com o catálogo atrasado gravaria fora da partição ou sem o índice.
 */
@DisplayName("Cluster: definição pendente no líder novo")
class PendingDefinitionTest {

    private static final String TABLE = "DEFINE TABLE events";
    private static final String PARTITION = "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d";
    private static final String LATE_INSERT =
        "CREATE events:novo CONTENT {at: d\"2026-11-01T11:00:00Z\", who: \"novo\"}";

    /** A base aplica o lote pendente na barreira; o catálogo só o relê quando alguém espera por ele. */
    private static final class NewLeader implements CommitCoordinator {
        private final MemoryBackend backend;
        private final Datastore ds;
        CommittedBatch pending;
        private CommittedBatch undelivered;
        long epoch;

        NewLeader(TimeData db) {
            this.backend = (MemoryBackend) db.backend;
            this.ds = db.ds;
        }

        private void apply() {
            if (pending != null) {
                backend.commit(Map.of(), pending.puts(), pending.deletes());
                undelivered = pending;
                pending = null;
            }
        }

        void deliver() {
            if (undelivered != null) {
                CommittedBatch batch = undelivered;
                undelivered = null;
                ds.onReplicatedBatch(new AppliedBatchListener.AppliedBatch(batch, Map.of()));
            }
        }

        @Override
        public long awaitCatalog() {
            apply();
            deliver();
            return epoch;
        }

        @Override
        public long beginValidation() {
            apply();
            return epoch;
        }

        @Override
        public long confirm(String nodeId, CommittedBatch batch) {
            return 1;
        }
    }

    /** O lote que o líder anterior propôs para a definição. */
    private static CommittedBatch fromTheOldLeader(String definition) {
        try (TimeData old = TimeData.memory()) {
            old.run(TABLE);
            AtomicReference<CommittedBatch> sent = new AtomicReference<>();
            old.ds.commitCoordinator((node, batch) -> {
                sent.set(batch);
                return 1;
            }, "n0");
            old.run(definition);
            return sent.get();
        }
    }

    private static NewLeader newLeader(TimeData db) {
        db.run(TABLE);
        NewLeader leader = new NewLeader(db);
        db.ds.commitCoordinator(leader, "n1");
        return leader;
    }

    @Test
    @DisplayName("DEFINE PARTITION pendente: a linha entra na partição e continua visível")
    void pendingPartitioning_isSeenByTheWrite() {
        CommittedBatch definition = fromTheOldLeader(PARTITION);
        try (TimeData db = TimeData.memory()) {
            NewLeader leader = newLeader(db);
            leader.pending = definition;

            db.run(LATE_INSERT);
            leader.deliver();

            assertEquals(List.of("novo"), db.values("SELECT * FROM events:novo", "who"));
            assertEquals(1, db.count("SHOW PARTITIONS ON TABLE events"));
        }
    }

    @Test
    @DisplayName("DEFINE INDEX UNIQUE pendente: a escrita já alimenta o índice")
    void pendingIndex_isMaintainedByTheWrite() {
        CommittedBatch definition = fromTheOldLeader("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
        try (TimeData db = TimeData.memory()) {
            NewLeader leader = newLeader(db);
            leader.pending = definition;

            db.run(LATE_INSERT);
            leader.deliver();

            assertThrows(RuntimeException.class,
                () -> db.run("CREATE events:outro CONTENT {who: \"novo\"}"));
        }
    }

    @Test
    @DisplayName("definição que aparece entre a execução e a confirmação vira conflito")
    void definitionArrivingBeforeCommit_conflicts() {
        CommittedBatch definition = fromTheOldLeader(PARTITION);
        try (TimeData db = TimeData.memory()) {
            NewLeader leader = newLeader(db);
            Session session = TimeData.newSession();
            db.ds.beginSession(session);
            db.run(session, LATE_INSERT);
            leader.pending = definition;
            leader.epoch = 1; // a liderança mudou no meio

            assertThrows(VersionConflictException.class, () -> db.ds.commitSession(session));
        }
    }
}
