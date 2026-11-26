package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static com.axonbase.core.cluster.ClusterHarness.data;
import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: pontos de extensão da replicação")
class ClusterHooksTest {

    @TempDir
    Path dir;

    private static CommittedBatch named(String transactionId) {
        return new CommittedBatch(transactionId, Map.of(transactionId, new byte[] {1}), Set.of());
    }

    @Test
    @DisplayName("o carimbo roda na ordem do índice e o gancho de confirmação vê todo nó na mesma ordem")
    void stampAndCommittedFollowLogOrder() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            AtomicInteger sequence = new AtomicInteger();
            Map<String, List<String>> committed = new ConcurrentHashMap<>();
            for (ClusterRuntime node : cluster.nodes()) {
                List<String> seen = new CopyOnWriteArrayList<>();
                committed.put(node.config().nodeId(), seen);
                node.hooks(new ReplicationHooks() {
                    @Override
                    public CommittedBatch stamp(CommittedBatch batch) {
                        return batch.transactionId().startsWith("noop") ? batch
                            : named(String.format("s%05d", sequence.incrementAndGet()));
                    }

                    @Override
                    public void committed(long index, long term, CommittedBatch batch) {
                        if (batch.transactionId().startsWith("s")) {
                            seen.add(batch.transactionId());
                        }
                    }
                });
            }
            ClusterRuntime leader = cluster.awaitLeader();
            List<Thread> writers = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                writers.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 50; i++) {
                        write(leader, "k", "v");
                    }
                }));
            }
            for (Thread writer : writers) {
                writer.join(60_000);
            }
            cluster.awaitConverged();
            List<String> expected = java.util.stream.IntStream.rangeClosed(1, 200)
                .mapToObj(i -> String.format("s%05d", i)).toList();
            committed.forEach((node, seen) -> assertEquals(expected, seen, node));
        }
    }

    @Test
    @DisplayName("um lote recusado pelo seguidor é reenviado dali, sem recuar no log nem trocar de líder")
    void rejectedBatchIsRetriedFromTheSamePoint() throws Exception {
        List<Integer> ports = ClusterHarness.reservePorts(2);
        InetSocketAddress leaderAddress = new InetSocketAddress("127.0.0.1", ports.get(0));
        InetSocketAddress followerAddress = new InetSocketAddress("127.0.0.1", ports.get(1));
        RaftNode follower = new RaftNode("f", dir.resolve("f"), new MemoryBackend());
        AtomicInteger refusals = new AtomicInteger();
        follower.hooks(new ReplicationHooks() {
            @Override
            public boolean accept(CommittedBatch batch) {
                return !batch.transactionId().equals("adiantado") || refusals.incrementAndGet() > 5;
            }
        });
        List<Long> previousSent = new CopyOnWriteArrayList<>();
        TcpRaftServer.Handler recording = request -> {
            if (request.type() == RaftWire.APPEND_STREAM) {
                previousSent.add(request.index());
            }
            return follower.handle(request);
        };
        try (follower;
             TcpRaftServer server = new TcpRaftServer(followerAddress.getPort(), recording);
             ClusterRuntime leader = new ClusterRuntime(new ClusterConfig("l", "g", leaderAddress,
                 List.of(followerAddress)), dir.resolve("l"), new MemoryBackend())) {
            await("sem líder", 30_000, () -> leader.role() == ClusterRole.LEADER);
            for (int i = 0; i < 20; i++) {
                write(leader, "k" + i, "v");
            }
            long elections = leader.electionsStarted();
            int before = previousSent.size();

            long index = leader.confirm("l", named("adiantado"));

            assertTrue(refusals.get() > 5);
            long lowest = previousSent.subList(before, previousSent.size()).stream().mapToLong(Long::longValue)
                .min().orElseThrow();
            assertTrue(lowest >= index - 2, "o líder recuou até " + lowest + " por causa do índice " + index);
            assertEquals(elections, leader.electionsStarted());
            assertEquals(0, leader.snapshotBytes());
        }
    }

    @Test
    @DisplayName("o lote de um snapshot passa pelos ganchos de quem envia e de quem recebe, que pode recusar")
    void snapshotGoesThroughTheHooks() throws Exception {
        try (RaftNode source = new RaftNode("a", dir.resolve("a"), new MemoryBackend());
             RaftNode target = new RaftNode("b", dir.resolve("b"), new MemoryBackend())) {
            source.hooks(new ReplicationHooks() {
                @Override
                public CommittedBatch stamp(CommittedBatch batch) {
                    return new CommittedBatch("carimbado", batch.puts(), batch.deletes(), batch.fullSnapshot());
                }
            });
            source.appendLocal(1, 1, named("um"));
            source.commitLocal(1, "a", 1);
            RaftNode.Image image = source.image();
            RaftWire.Request request = new RaftWire.Request(RaftWire.SNAPSHOT, "g", 1, "a", image.index(), 1,
                RaftPayload.encode(image.batch()));
            List<String> seen = new ArrayList<>();
            AtomicInteger refusals = new AtomicInteger();
            target.hooks(new ReplicationHooks() {
                @Override
                public boolean accept(CommittedBatch batch) {
                    seen.add(batch.transactionId());
                    return refusals.incrementAndGet() > 1;
                }
            });

            org.junit.jupiter.api.Assertions.assertFalse(target.handle(request).accepted());
            assertEquals(0, target.commitIndex());
            assertTrue(target.handle(request).accepted());
            assertEquals(1, target.commitIndex());
            assertEquals(List.of("carimbado", "carimbado"), seen);
        }
    }

    @Test
    @DisplayName("ao instalar os ganchos o nó entrega a última entrada do log, para o relógio não voltar")
    void hooksSeeTheTailOfTheLogOnInstall() throws Exception {
        Path node = dir.resolve("n");
        try (RaftNode first = new RaftNode("n", node, new MemoryBackend())) {
            first.appendLocal(1, 1, named("um"));
            first.appendLocal(1, 2, named("dois"));
        }
        List<String> recovered = new ArrayList<>();
        try (RaftNode again = new RaftNode("n", node, new MemoryBackend())) {
            again.hooks(new ReplicationHooks() {
                @Override
                public void recovered(CommittedBatch batch) {
                    recovered.add(batch.transactionId());
                }
            });
        }
        assertEquals(List.of("dois"), recovered);
    }
}
