package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend.SyncMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static com.axonbase.core.cluster.ClusterHarness.data;
import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: group commit, pipeline e recuperação de seguidores")
class ClusterReplicationTest {

    @TempDir
    Path dir;

    private static void writeConcurrently(ClusterRuntime leader, int threads, int each, Set<Long> indexes)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int worker = t;
            tasks.add(pool.submit(() -> {
                for (int i = 0; i < each; i++) {
                    assertTrue(indexes.add(write(leader, "w" + worker + "-" + i, "v" + i)));
                }
            }));
        }
        for (Future<?> task : tasks) {
            task.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    @Test
    @DisplayName("escritas concorrentes dividem fsyncs e chegam iguais aos três nós")
    void concurrentWritesGroupAndConverge() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir,
                RaftOptions.defaults().withSync(SyncMode.ALWAYS), "n1", "n2", "n3").startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            long syncsBefore = leader.logSyncs();
            Set<Long> indexes = ConcurrentHashMap.newKeySet();

            writeConcurrently(leader, 8, 50, indexes);

            assertEquals(400, indexes.size());
            long syncs = leader.logSyncs() - syncsBefore;
            assertTrue(syncs > 0 && syncs < 400, "400 escritas usaram " + syncs + " fsyncs no líder");
            cluster.awaitConverged();
            Map<String, String> expected = data(leader);
            assertEquals(400, expected.size());
            cluster.nodes().forEach(n -> assertEquals(expected, data(n)));
        }
    }

    @Test
    @DisplayName("quando confirm volta, o líder já aplicou o lote")
    void confirmReturnsAfterLocalApply() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            for (int i = 0; i < 100; i++) {
                long index = write(leader, "k", "v" + i);
                assertTrue(leader.commitIndex() >= index);
                assertArrayEquals(("v" + i).getBytes(StandardCharsets.UTF_8),
                    leader.backend().get("k").orElseThrow());
            }
        }
    }

    @Test
    @DisplayName("o listener do líder recebe os lotes na ordem do log")
    void leaderListenerSeesBatchesInOrder() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            List<String> seen = new CopyOnWriteArrayList<>();
            leader.appliedBatchListener(applied -> seen.add(applied.batch().transactionId()));
            List<String> sent = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                CommittedBatch batch = new CommittedBatch("ordem-" + i, Map.of("k", new byte[] {(byte) i}), Set.of());
                leader.confirm(leader.config().nodeId(), batch);
                sent.add(batch.transactionId());
            }
            await("listener não recebeu tudo: " + seen.size(), 10_000, () -> seen.size() == 50);
            assertEquals(sent, seen);
        }
    }

    @Test
    @DisplayName("um seguidor que ficou fora alcança o líder pelo log, sem snapshot")
    void lateFollowerCatchesUpFromLog() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime follower = cluster.awaitFollower();
            String down = follower.config().nodeId();
            cluster.stop(down);
            ClusterRuntime leader = cluster.awaitLeader();
            writeConcurrently(leader, 4, 500, ConcurrentHashMap.newKeySet());

            cluster.start(down);
            cluster.awaitConverged();

            assertEquals(data(cluster.awaitLeader()), data(cluster.node(down)));
            assertEquals(0, cluster.awaitLeader().snapshotBytes());
        }
    }

    @Test
    @DisplayName("um seguidor atrás do log compactado recebe snapshot e converge")
    void followerBehindCompactionGetsSnapshot() throws Exception {
        RaftOptions small = RaftOptions.defaults().withRetainEntries(20).withSegmentBytes(2_000).withRetainMillis(0);
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, small, "n1", "n2", "n3").startAll()) {
            String down = cluster.awaitFollower().config().nodeId();
            cluster.stop(down);
            ClusterRuntime leader = cluster.awaitLeader();
            for (int i = 0; i < 600; i++) {
                write(leader, "k" + i, "v" + i);
            }
            assertTrue(leader.firstLogIndex() > 1, "o líder não compactou o log");

            cluster.start(down);
            cluster.awaitConverged();

            assertEquals(data(cluster.awaitLeader()), data(cluster.node(down)));
            assertTrue(cluster.awaitLeader().snapshotBytes() > 0);
        }
    }

    @Test
    @DisplayName("reiniciar um cluster durável recompõe o estado e aceita escritas")
    void restartKeepsData() throws Exception {
        RaftOptions options = RaftOptions.defaults().withRetainEntries(20).withSegmentBytes(2_000).withRetainMillis(0);
        Map<String, String> expected;
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, options, "n1", "n2", "n3").startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            for (int i = 0; i < 200; i++) {
                write(leader, "k" + i, "v" + i);
            }
            cluster.awaitConverged();
            expected = data(leader);
            List.of("n1", "n2", "n3").forEach(cluster::stop);

            cluster.startAll();
            ClusterRuntime again = cluster.awaitLeader();
            write(again, "depois", "reinicio");
            cluster.awaitConverged();
            expected = new java.util.TreeMap<>(expected);
            expected.put("depois", "reinicio");
            for (ClusterRuntime node : cluster.nodes()) {
                assertEquals(expected, data(node));
            }
        }
    }

    @Test
    @DisplayName("sem quórum a escrita falha; quando o quórum volta o cluster aceita escritas e converge")
    void quorumLossAndReturn() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "antes", "1");
            List<String> followers = cluster.nodes().stream().filter(n -> n != leader)
                .map(n -> n.config().nodeId()).toList();
            followers.forEach(cluster::stop);

            assertThrows(QuorumUnavailableException.class, () -> write(leader, "sem-quorum", "2"));

            followers.forEach(cluster::start);
            ClusterRuntime again = cluster.awaitLeader();
            await("escrita não voltou", 30_000, () -> {
                try {
                    write(cluster.awaitLeader(), "depois", "3");
                    return true;
                } catch (RuntimeException notYet) {
                    return false;
                }
            });
            cluster.awaitConverged();
            Map<String, String> expected = data(cluster.awaitLeader());
            assertEquals("1", expected.get("antes"));
            assertEquals("3", expected.get("depois"));
            cluster.nodes().forEach(n -> assertEquals(expected, data(n)));
        }
    }

    @Test
    @DisplayName("uma validação feita antes de uma troca de liderança não é aceita")
    void staleValidationIsRejected() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1").startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            long epoch = leader.beginValidation();
            assertThrows(com.axonbase.core.storage.VersionConflictException.class,
                () -> leader.confirm("n1", ClusterHarness.put("k", "v"), epoch - 1));
            assertEquals(0, leader.lastIndex());
        }
    }

    /**
     * Nó das versões até a 0.10.x no fio: uma requisição por conexão e só os tipos 1 a 3.
     */
    private static final class LegacyPeer implements AutoCloseable {
        final MemoryBackend backend = new MemoryBackend();
        final List<Byte> types = new CopyOnWriteArrayList<>();
        private final java.net.ServerSocket server;
        private final RaftNode node;

        LegacyPeer(Path dir, InetSocketAddress address) throws IOException {
            node = new RaftNode("old", dir, backend);
            server = new java.net.ServerSocket();
            server.setReuseAddress(true);
            server.bind(address);
            Thread.ofPlatform().daemon().start(() -> {
                while (!server.isClosed()) {
                    try (var socket = server.accept();
                         var in = new java.io.DataInputStream(socket.getInputStream());
                         var out = new java.io.DataOutputStream(socket.getOutputStream())) {
                        byte type = in.readByte();
                        String group = in.readUTF();
                        long term = in.readLong();
                        String actor = in.readUTF();
                        String advertise = in.readUTF();
                        long index = in.readLong();
                        long commit = in.readLong();
                        byte[] payload = in.readNBytes(in.readInt());
                        types.add(type);
                        RaftWire.Response response = type > RaftWire.SNAPSHOT
                            ? new RaftWire.Response(node.term(), false, node.lastIndex())
                            : node.handle(new RaftWire.Request(type, group, term, actor, advertise, index, commit,
                                payload));
                        out.writeLong(response.term());
                        out.writeBoolean(response.accepted());
                        out.writeLong(response.matchIndex());
                        out.flush();
                    } catch (IOException ignored) {
                        // conexão encerrada pelo outro lado
                    }
                }
            });
        }

        @Override
        public void close() throws IOException {
            server.close();
            node.close();
        }
    }

    @Test
    @DisplayName("um líder novo replica para um nó antigo, que só entende uma entrada por conexão")
    void replicatesToLegacyFollower() throws Exception {
        List<Integer> ports = ClusterHarness.reservePorts(2);
        InetSocketAddress leaderAddress = new InetSocketAddress("127.0.0.1", ports.get(0));
        InetSocketAddress oldAddress = new InetSocketAddress("127.0.0.1", ports.get(1));
        try (LegacyPeer old = new LegacyPeer(dir.resolve("old"), oldAddress);
             ClusterRuntime leader = new ClusterRuntime(new ClusterConfig("new", "g", leaderAddress,
                 List.of(oldAddress)), dir.resolve("new"), new MemoryBackend())) {
            await("sem líder", 30_000, () -> leader.role() == ClusterRole.LEADER);
            Set<Long> indexes = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                indexes.add(write(leader, "k" + i, "v" + i));
            }
            await("nó antigo não convergiu", 10_000, () -> old.backend.keysWithPrefix("k").size() == 30);
            assertEquals(30, indexes.size());
            assertTrue(old.types.contains(RaftWire.APPEND));
        }
    }
}
