package com.axonbase.core.cluster;

import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static com.axonbase.core.cluster.ClusterHarness.data;
import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: réplica de leitura a partir de backup físico")
class ClusterLearnerTest {

    @TempDir
    Path dir;

    private ClusterHarness cluster(RaftOptions options) {
        return ClusterHarness.rocks(dir, options, "n1", "n2", "n3").startAll();
    }

    private Path backup(ClusterRuntime source, String name) {
        Path target = dir.resolve(name);
        try (PhysicalBackups backups = PhysicalBackups.open(target)) {
            backups.create((RocksDbBackend) source.backend());
        }
        return target;
    }

    private static boolean caughtUp(ClusterRuntime leader, ClusterRuntime learner) {
        return learner.commitIndex() == leader.commitIndex() && learner.lastIndex() == leader.lastIndex();
    }

    @Test
    @DisplayName("o learner entra de um backup tirado sob escrita, alcança pelo log e não entra no quórum")
    void joinsFromBackupUnderWrites() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults())) {
            ClusterRuntime leader = cluster.awaitLeader();
            for (int i = 0; i < 200; i++) {
                write(leader, "antes" + i, "v" + i);
            }
            AtomicBoolean writing = new AtomicBoolean(true);
            AtomicInteger written = new AtomicInteger();
            Thread writer = Thread.ofPlatform().start(() -> {
                while (writing.get()) {
                    int i = written.get();
                    write(leader, "durante" + i, "v" + i);
                    written.incrementAndGet();
                }
            });
            try {
                await("escritas não começaram", 10_000, () -> written.get() > 20);
                Path backups = backup(leader, "backups");
                long atBackup = leader.commitIndex();
                RaftApplier.Applied restored = LearnerBootstrap.restore(backups, cluster.dir("r1").resolve("db"))
                    .orElseThrow();
                assertTrue(restored.index() >= 200 && restored.index() <= atBackup, restored.toString());
                ClusterRuntime learner = cluster.start("r1");
                await("learner não alcançou", 60_000,
                    () -> learner.commitIndex() >= atBackup + 20 && !learner.leaderAddress().isEmpty());
            } finally {
                writing.set(false);
                writer.join(30_000);
            }
            ClusterRuntime learner = cluster.node("r1");
            await("learner não convergiu: " + cluster.describe(), 60_000, () -> caughtUp(leader, learner));

            assertEquals(data(leader), data(learner));
            assertEquals(ClusterRole.LEARNER, learner.role());
            assertTrue(learner.status().ready());
            assertEquals(3, leader.members());
            assertEquals(2, leader.quorum());
            assertEquals(List.of("r1"), leader.learnerInfos().stream().map(ClusterRuntime.PeerInfo::node).toList());
            assertEquals(0, leader.snapshotBytes(), "o learner deveria ter usado só o backup e o log");
            assertDoesNotThrow(() -> learner.awaitRead(ReadConsistency.LINEARIZABLE));
            assertThrows(NotLeaderException.class, () -> write(learner, "x", "y"));
            // Promover um nó que ainda se recusa a votar só reduziria a disponibilidade.
            assertFalse(leader.addPeer("r1", learner.config().advertiseAddress()));
            assertEquals(3, leader.members());
        }
    }

    @Test
    @DisplayName("o learner não vota nem completa quórum")
    void doesNotCountForQuorum() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults())) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "k", "v");
            LearnerBootstrap.restore(backup(leader, "backups"), cluster.dir("r1").resolve("db"));
            ClusterRuntime learner = cluster.start("r1");
            await("learner não entrou", 30_000, () -> !leader.learnerInfos().isEmpty() && caughtUp(leader, learner));

            cluster.nodes().stream().filter(n -> n != leader && n != learner)
                .map(n -> n.config().nodeId()).toList().forEach(cluster::stop);

            assertThrows(QuorumUnavailableException.class, () -> write(leader, "sem-quorum", "v"));
            cluster.stop(leader.config().nodeId());
            Thread.sleep(2_000);
            assertEquals(ClusterRole.LEARNER, learner.role());
            assertEquals(0, learner.electionsStarted());
        }
    }

    @Test
    @DisplayName("o learner segue o novo líder depois de uma troca")
    void followsNewLeader() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults())) {
            ClusterRuntime first = cluster.awaitLeader();
            write(first, "k", "1");
            LearnerBootstrap.restore(backup(first, "backups"), cluster.dir("r1").resolve("db"));
            ClusterRuntime learner = cluster.start("r1");
            await("learner não entrou", 30_000, () -> caughtUp(first, learner));

            cluster.stop(first.config().nodeId());
            ClusterRuntime second = cluster.nodes().stream().filter(n -> n != learner).findAny().orElseThrow();
            await("sem novo líder", 30_000, () -> cluster.nodes().stream()
                .anyMatch(n -> n.role() == ClusterRole.LEADER && n.status().ready()));
            second = cluster.awaitLeader();
            write(second, "k", "2");
            ClusterRuntime leader = second;
            await("learner não seguiu o novo líder", 30_000, () -> caughtUp(leader, learner));

            assertEquals(data(leader), data(learner));
        }
    }

    @Test
    @DisplayName("um backup mais velho que o log é recusado até chegar um mais novo")
    void oldBackupNeedsANewerOne() throws Exception {
        RaftOptions small = RaftOptions.defaults().withRetainEntries(20).withSegmentBytes(2_000)
            .withRetainMillis(0);
        try (ClusterHarness cluster = cluster(small)) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "k", "0");
            Path old = backup(leader, "velho");
            for (int i = 0; i < 600; i++) {
                write(leader, "k" + i, "v" + i);
            }
            assertTrue(leader.firstLogIndex() > 2, "o líder não compactou");
            LearnerBootstrap.restore(old, cluster.dir("r1").resolve("db"));
            ClusterRuntime stale = cluster.start("r1");
            await("o líder não pediu um backup novo", 30_000,
                () -> leader.learnersNeedingBackup().contains("r1"));
            assertTrue(stale.commitIndex() < leader.commitIndex());
            assertEquals(0, leader.snapshotBytes(), "learner não recebe snapshot pela rede");

            cluster.stop("r1");
            deleteTree(cluster.dir("r1"));
            LearnerBootstrap.restore(backup(leader, "novo"), cluster.dir("r1").resolve("db"));
            ClusterRuntime fresh = cluster.start("r1");
            await("o líder não aceitou o backup novo", 30_000, () -> leader.learnersNeedingBackup().isEmpty());
            write(leader, "depois", "do-backup-novo");
            await("learner novo não convergiu: " + cluster.describe(), 60_000, () -> caughtUp(leader, fresh));

            assertEquals(data(leader), data(fresh));
            assertTrue(leader.learnersNeedingBackup().isEmpty());
        }
    }

    @Test
    @DisplayName("o log recente fica guardado pelo tempo configurado, para um backup de minutos atrás servir")
    void recentLogIsRetainedByTime() throws Exception {
        RaftOptions held = RaftOptions.defaults().withRetainEntries(20).withSegmentBytes(2_000)
            .withRetainMillis(600_000);
        try (ClusterHarness cluster = cluster(held)) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "k", "0");
            Path backups = backup(leader, "backups");
            for (int i = 0; i < 600; i++) {
                write(leader, "k" + i, "v" + i);
            }
            assertEquals(1, leader.firstLogIndex());
            LearnerBootstrap.restore(backups, cluster.dir("r1").resolve("db"));
            ClusterRuntime learner = cluster.start("r1");
            await("learner não convergiu", 60_000, () -> caughtUp(leader, learner));
            assertEquals(data(leader), data(learner));
        }
    }

    @Test
    @DisplayName("o learner reinicia de onde parou, sem novo backup, e serve para tirar backup")
    void restartsAndTakesBackups() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults())) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "k", "1");
            Path backups = backup(leader, "backups");
            Path db = cluster.dir("r1").resolve("db");
            assertTrue(LearnerBootstrap.restore(backups, db).isPresent());
            ClusterRuntime learner = cluster.start("r1");
            await("learner não entrou", 30_000, () -> caughtUp(leader, learner));
            cluster.stop("r1");
            write(leader, "k", "2");

            assertTrue(LearnerBootstrap.restore(backups, db).isEmpty(), "base existente não é sobrescrita");
            ClusterRuntime again = cluster.start("r1");
            await("learner não voltou", 30_000, () -> caughtUp(leader, again));
            assertEquals(data(leader), data(again));

            Path fromLearner = backup(again, "do-learner");
            RaftApplier.Applied applied = LearnerBootstrap.restore(fromLearner, dir.resolve("copia")).orElseThrow();
            assertEquals(again.commitIndex(), applied.index());
        }
    }

    @Test
    @DisplayName("backup que não veio de um cluster, ou diretório sem backup, não vira learner")
    void refusesUnusableBackups() throws Exception {
        Path standalone = dir.resolve("solto");
        try (RocksDbBackend db = new RocksDbBackend(standalone.toString());
             PhysicalBackups backups = PhysicalBackups.open(dir.resolve("backups-solto"))) {
            db.put("k", new byte[] {1});
            backups.create(db);
        }
        Path target = dir.resolve("alvo");
        assertThrows(IllegalStateException.class,
            () -> LearnerBootstrap.restore(dir.resolve("backups-solto"), target));
        assertFalse(Files.exists(target));
        assertThrows(IllegalStateException.class,
            () -> LearnerBootstrap.restore(dir.resolve("inexistente"), target));
        assertFalse(Files.exists(dir.resolve("inexistente")));
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
