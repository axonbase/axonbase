package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Raft: instalação dos ganchos com a aplicação correndo")
class RaftHooksInstallTest {

    @TempDir
    Path dir;

    private static CommittedBatch batch(int index) {
        return new CommittedBatch("tx" + index, Map.of("k" + index, new byte[] {1}), Set.of());
    }

    @Test
    @DisplayName("a reentrega termina antes de qualquer aplicação nova: o journal recebe em ordem de índice")
    void replayHappensBeforeNewEntries() throws Exception {
        try (RaftNode node = new RaftNode("n1", dir, new MemoryBackend())) {
            node.appendLocal(1, 1, batch(1));
            node.applyThrough(1);
            node.appendLocal(1, 2, batch(2));
            List<Long> delivered = new CopyOnWriteArrayList<>();
            Thread applier = new Thread(() -> node.applyThrough(2), "aplicacao");

            node.hooks(new ReplicationHooks() {
                @Override
                public void recovered(CommittedBatch tail) {
                    // a thread de aplicação chega no meio da instalação
                    applier.start();
                    try {
                        applier.join(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public long replayFrom() {
                    return 1;
                }

                @Override
                public void committed(long index, long term, CommittedBatch applied) {
                    delivered.add(index);
                }
            });
            applier.join(10_000);

            assertFalse(applier.isAlive(), "a aplicação não terminou");
            assertEquals(List.of(1L, 2L), delivered);
        }
    }
}
