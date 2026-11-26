package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend.SyncMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: desfecho desconhecido e sincronização do log")
class ClusterOutcomeTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("escrita já gravada que perde o quórum tem desfecho desconhecido; a recusada antes é falha certa")
    void lostQuorumIsUnknownOnlyForAppendedWrites() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2").startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "antes", "1");
            String follower = cluster.nodes().stream().filter(n -> n != leader).findFirst().orElseThrow()
                .config().nodeId();
            cluster.stop(follower);

            CommitOutcomeUnknownException unknown = assertThrows(CommitOutcomeUnknownException.class,
                () -> write(leader, "pendente", "2"));
            assertTrue(unknown.getMessage().contains("desconhecido"), unknown.getMessage());

            // Agora o líder já sabe que não há maioria: recusa antes de gravar.
            long last = leader.lastIndex();
            QuorumUnavailableException refused = assertThrows(QuorumUnavailableException.class,
                () -> write(leader, "recusada", "3"));
            assertEquals(QuorumUnavailableException.class, refused.getClass());
            assertEquals(last, leader.lastIndex());

            cluster.start(follower);
            await("o quórum não voltou", 60_000, () -> cluster.nodes().stream()
                .allMatch(n -> n.backend().get("pendente").isPresent()));
            assertTrue(cluster.awaitLeader().backend().get("recusada").isEmpty());
        }
    }

    @Test
    @DisplayName("fechar o líder com uma escrita pendente a deixa com desfecho desconhecido")
    void closingWithPendingWriteIsUnknown() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2").startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "antes", "1");
            cluster.nodes().stream().filter(n -> n != leader).map(n -> n.config().nodeId()).toList()
                .forEach(cluster::stop);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            long before = leader.lastIndex();
            Thread writer = Thread.ofPlatform().start(() -> {
                try {
                    write(leader, "pendente", "2");
                } catch (RuntimeException e) {
                    outcome.set(e);
                }
            });
            await("a escrita não entrou no log", 10_000, () -> leader.lastIndex() > before);
            cluster.stop(leader.config().nodeId());
            writer.join(30_000);
            assertEquals(CommitOutcomeUnknownException.class, outcome.get().getClass(), String.valueOf(outcome.get()));
        }
    }

    @Test
    @DisplayName("o log Raft faz fsync por escrita por padrão, mesmo com o armazenamento em batch")
    void raftLogSyncsAlwaysByDefault() throws Exception {
        String previousStorage = System.getProperty("axon.storage.sync");
        System.setProperty("axon.storage.sync", "batch");
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            assertEquals(SyncMode.ALWAYS, RaftOptions.defaults().sync());
            ClusterRuntime leader = cluster.awaitLeader();
            long before = leader.logSyncs();
            for (int i = 0; i < 5; i++) {
                write(leader, "k" + i, "v");
            }
            assertTrue(leader.logSyncs() - before >= 5, "fsyncs: " + (leader.logSyncs() - before));
        } finally {
            restore("axon.storage.sync", previousStorage);
        }
    }

    @Test
    @DisplayName("batch no log é uma escolha própria do log, e um valor desconhecido é recusado")
    void logSyncHasItsOwnSetting() {
        String previous = System.getProperty("axon.raft.log.sync");
        try {
            System.setProperty("axon.raft.log.sync", "batch");
            assertEquals(SyncMode.BATCH, RaftOptions.defaults().sync());
            System.setProperty("axon.raft.log.sync", "ALWAYS");
            assertEquals(SyncMode.ALWAYS, RaftOptions.defaults().sync());
            for (String invalid : List.of("off", "sempre")) {
                System.setProperty("axon.raft.log.sync", invalid);
                assertThrows(IllegalArgumentException.class, RaftOptions::defaults, invalid);
            }
        } finally {
            restore("axon.raft.log.sync", previous);
        }
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    @Test
    @DisplayName("com o log em batch a escrita não espera o fsync")
    void batchLogDoesNotWaitForDisk() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir,
                RaftOptions.defaults().withSync(SyncMode.BATCH), "n1").startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            long before = leader.logSyncs();
            for (int i = 0; i < 20; i++) {
                write(leader, "k" + i, "v");
            }
            assertTrue(leader.logSyncs() - before <= 2, "fsyncs: " + (leader.logSyncs() - before));
        }
    }
}
