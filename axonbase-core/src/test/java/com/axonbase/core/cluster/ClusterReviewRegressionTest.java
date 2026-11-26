package com.axonbase.core.cluster;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: casos apontados na revisão")
class ClusterReviewRegressionTest {

    @TempDir
    Path dir;

    private static List<InetSocketAddress> addresses(int count) {
        return ClusterHarness.reservePorts(count).stream()
            .map(port -> new InetSocketAddress("127.0.0.1", port)).toList();
    }

    private static RaftWire.Request heartbeat(String group, long term, long commit) {
        return new RaftWire.Request(RaftWire.APPEND_STREAM, group, term, "fantasma", 0, commit,
            RaftStream.encode(0, List.of()));
    }

    @Test
    @DisplayName("batimentos recebidos não bastam para uma leitura com limite: o nó precisa confirmar com o líder")
    void boundedReadNeedsConfirmationFromTheLeader() throws Exception {
        List<InetSocketAddress> two = addresses(2);
        try (ClusterRuntime follower = new ClusterRuntime(new ClusterConfig("f", "g", two.get(0),
                List.of(two.get(1))), dir.resolve("f"), new MemoryBackend())) {
            // Batimentos atrasados na fila do TCP têm a mesma cara de batimentos novos.
            for (int i = 0; i < 5; i++) {
                assertTrue(RaftWire.call(two.get(0), heartbeat("g", 5, 0), 1_000).accepted());
            }
            assertThrows(StaleReadException.class, () -> follower.awaitRead(ReadConsistency.maxLag(10_000)));
        }
    }

    @Test
    @DisplayName("uma requisição de outro cluster é recusada sem mexer no termo")
    void foreignGroupIsRejected() throws Exception {
        List<InetSocketAddress> two = addresses(2);
        try (ClusterRuntime node = new ClusterRuntime(new ClusterConfig("f", "g", two.get(0),
                List.of(two.get(1))), dir.resolve("f"), new MemoryBackend())) {
            RaftWire.Response response = RaftWire.call(two.get(0), heartbeat("outro", 99, 0), 1_000);
            assertFalse(response.accepted());
            assertTrue(response.term() < 99);
        }
    }

    @Test
    @DisplayName("só entra como learner o endereço autorizado")
    void onlyListedLearnersAreFed() throws Exception {
        List<InetSocketAddress> three = addresses(3);
        InetSocketAddress allowed = three.get(1);
        RaftOptions options = RaftOptions.defaults()
            .withAllowedLearners(List.of(RaftWire.advertise(allowed)));
        try (ClusterRuntime leader = new ClusterRuntime(new ClusterConfig("n1", "g", three.get(0), List.of()),
                dir.resolve("n1"), new MemoryBackend(), options);
             ClusterRuntime stranger = new ClusterRuntime(new ClusterConfig("intruso", "g", three.get(2),
                List.of(three.get(0)), true), dir.resolve("i"), new MemoryBackend(), options);
             ClusterRuntime learner = new ClusterRuntime(new ClusterConfig("r1", "g", allowed,
                List.of(three.get(0)), true), dir.resolve("r1"), new MemoryBackend(), options)) {
            await("sem líder", 30_000, () -> leader.role() == ClusterRole.LEADER);
            write(leader, "segredo", "v");
            await("learner autorizado não recebeu", 30_000, () -> learner.backend().get("segredo").isPresent());
            Thread.sleep(1_000);
            assertTrue(stranger.backend().get("segredo").isEmpty(), "dados enviados a um endereço não autorizado");
            assertEquals(List.of("r1"),
                leader.learnerInfos().stream().map(ClusterRuntime.PeerInfo::node).toList());
        }
    }

    @Test
    @DisplayName("sem lista configurada nenhum learner é aceito")
    void learnersAreOffByDefault() throws Exception {
        List<InetSocketAddress> two = addresses(2);
        RaftOptions closed = RaftOptions.defaults().withAllowedLearners(List.of());
        try (ClusterRuntime leader = new ClusterRuntime(new ClusterConfig("n1", "g", two.get(0), List.of()),
                dir.resolve("n1"), new MemoryBackend(), closed);
             ClusterRuntime learner = new ClusterRuntime(new ClusterConfig("r1", "g", two.get(1),
                List.of(two.get(0)), true), dir.resolve("r1"), new MemoryBackend(), closed)) {
            await("sem líder", 30_000, () -> leader.role() == ClusterRole.LEADER);
            write(leader, "k", "v");
            Thread.sleep(1_000);
            assertTrue(leader.learnerInfos().isEmpty());
            assertTrue(learner.backend().get("k").isEmpty());
        }
    }

    @Test
    @DisplayName("uma leitura linearizável num seguidor já vê o efeito do listener")
    void linearizableReadWaitsForTheListener() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime follower = cluster.awaitFollower();
            List<String> visible = new CopyOnWriteArrayList<>();
            follower.appliedBatchListener(applied -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                visible.add(applied.batch().transactionId());
            });
            ClusterRuntime leader = cluster.awaitLeader();
            for (int i = 0; i < 10; i++) {
                String transaction = "ddl-" + i;
                leader.confirm(leader.config().nodeId(),
                    new CommittedBatch(transaction, Map.of("k", new byte[] {(byte) i}), Set.of()));
                follower.awaitRead(ReadConsistency.LINEARIZABLE);
                assertTrue(visible.contains(transaction), "leitura antes do listener ver " + transaction);
            }
        }
    }

    /** Membro de mentira que responde o que o teste manda, para observar o que o líder envia. */
    private static RaftWire.Response reply(RaftWire.Request request, boolean accepted, long match) {
        return new RaftWire.Response(request.term(), accepted, match);
    }

    private ClusterRuntime compactingLeader(List<InetSocketAddress> three, RocksDbBackend backend)
            throws Exception {
        RaftOptions small = RaftOptions.defaults().withRetainEntries(5).withSegmentBytes(1_000)
            .withRetainMillis(0);
        return new ClusterRuntime(new ClusterConfig("n1", "g", three.get(0),
            List.of(three.get(1), three.get(2))), dir.resolve("n1"), backend, small);
    }

    @Test
    @DisplayName("o líder continua batendo enquanto um snapshot demorado é enviado")
    void heartbeatsContinueDuringSnapshot() throws Exception {
        List<InetSocketAddress> three = addresses(3);
        RaftNode good = new RaftNode("n2", dir.resolve("n2"), new MemoryBackend());
        AtomicBoolean behind = new AtomicBoolean();
        AtomicBoolean sending = new AtomicBoolean();
        AtomicInteger beatsDuring = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        TcpRaftServer.Handler slow = request -> {
            if (request.type() == RaftWire.SNAPSHOT) {
                sending.set(true);
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                sending.set(false);
                snapshots.incrementAndGet();
                behind.set(false);
                return reply(request, true, request.index());
            }
            if (sending.get() && request.type() != RaftWire.VOTE) {
                beatsDuring.incrementAndGet();
            }
            if (request.type() == RaftWire.APPEND_STREAM && behind.get()) {
                return reply(request, false, 0);
            }
            int count = request.type() == RaftWire.APPEND_STREAM
                ? RaftStream.decode(request.payload()).entries().size() : 0;
            return reply(request, true, request.index() + count);
        };
        try (good;
             TcpRaftServer n2 = new TcpRaftServer(three.get(1).getPort(), good::handle);
             TcpRaftServer n3 = new TcpRaftServer(three.get(2).getPort(), slow);
             RocksDbBackend backend = new RocksDbBackend(dir.resolve("db").toString());
             ClusterRuntime leader = compactingLeader(three, backend)) {
            await("sem líder", 30_000, () -> leader.role() == ClusterRole.LEADER);
            for (int i = 0; i < 100; i++) {
                write(leader, "k" + i, "v");
            }
            assertTrue(leader.firstLogIndex() > 1, "sem compactação não há snapshot");
            behind.set(true);
            await("snapshot não foi enviado", 60_000, () -> snapshots.get() > 0);
            assertTrue(beatsDuring.get() >= 5, "batimentos durante o snapshot: " + beatsDuring.get());
        }
    }

    @Test
    @DisplayName("um nó antigo que recebeu snapshot ganha a entrada seguinte no formato que ele aceita com buraco")
    void legacyFollowerAfterSnapshotGetsUnframedEntry() throws Exception {
        List<InetSocketAddress> three = addresses(3);
        RaftNode good = new RaftNode("n2", dir.resolve("n2"), new MemoryBackend());
        AtomicBoolean refusing = new AtomicBoolean(true);
        AtomicLong snapshotAt = new AtomicLong(-1);
        AtomicLong emptyAfter = new AtomicLong(-1);
        List<Long> applied = new CopyOnWriteArrayList<>();
        // Emula a 0.10.1: não fala HELLO; depois do snapshot o log fica vazio e só uma entrada sem o
        // cabeçalho de AppendEntries entra com buraco.
        TcpRaftServer.Handler old = request -> {
            if (request.type() == RaftWire.SNAPSHOT) {
                snapshotAt.set(request.index());
                emptyAfter.set(request.index());
                refusing.set(false);
                return reply(request, true, 0);
            }
            if (request.type() != RaftWire.APPEND) {
                return reply(request, false, 0);
            }
            if (request.payload().length == 0) {
                return reply(request, true, 0);
            }
            boolean framed = java.nio.ByteBuffer.wrap(request.payload()).getInt() == 0x41585045;
            if (refusing.get() || emptyAfter.get() >= 0 && framed) {
                return reply(request, false, 0);
            }
            emptyAfter.set(-1);
            applied.add(request.index());
            return reply(request, true, request.index());
        };
        try (good;
             TcpRaftServer n2 = new TcpRaftServer(three.get(1).getPort(), good::handle);
             TcpRaftServer n3 = new TcpRaftServer(three.get(2).getPort(), old);
             RocksDbBackend backend = new RocksDbBackend(dir.resolve("db").toString());
             ClusterRuntime leader = compactingLeader(three, backend)) {
            await("sem líder", 30_000, () -> leader.role() == ClusterRole.LEADER);
            for (int i = 0; i < 100; i++) {
                write(leader, "k" + i, "v");
            }
            await("nó antigo não recebeu snapshot", 60_000, () -> snapshotAt.get() >= 0);
            write(leader, "depois", "v");
            await("nó antigo não voltou a receber o log", 60_000,
                () -> !applied.isEmpty() && applied.getLast() == leader.lastIndex());
        }
    }

    @Test
    @DisplayName("muitas escritas entre a confirmação e o listener não fazem o líder tratar o próprio lote como alheio")
    void locallyOriginatedBatchesAreRememberedUntilDelivered() {
        List<CommittedBatch> confirmed = new ArrayList<>();
        Datastore ds = new Datastore(new MemoryBackend());
        ds.commitCoordinator((node, batch) -> {
            confirmed.add(batch);
            return confirmed.size();
        }, "n1");
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        ds.execute("DEFINE TABLE t", session, null);
        CommittedBatch definition = confirmed.getLast();
        for (int i = 0; i < 600; i++) {
            ds.execute("CREATE t:" + "r" + i, session, null);
        }
        long rebuilds = ds.catalogRebuilds();

        ds.onReplicatedBatch(new AppliedBatchListener.AppliedBatch(definition, Map.of()));

        assertEquals(rebuilds, ds.catalogRebuilds());
    }
}
