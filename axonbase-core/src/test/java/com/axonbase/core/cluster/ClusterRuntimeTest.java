package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: runtime Raft sobre TCP")
class ClusterRuntimeTest {

    /**
     * Reserva portas mantendo todos os sockets abertos ao mesmo tempo.
     *
     * <p>Pedir e fechar uma porta por vez deixa o sistema devolver a mesma porta na
     * chamada seguinte, e o segundo nó falha ao subir com "address already in use".</p>
     */
    private static List<Integer> reservePorts(int count) throws Exception {
        List<ServerSocket> held = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0);
                held.add(socket);
                ports.add(socket.getLocalPort());
            }
        } finally {
            for (ServerSocket socket : held) {
                socket.close();
            }
        }
        return ports;
    }

    private static ClusterRuntime awaitLeader(List<ClusterRuntime> nodes) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            for (ClusterRuntime node : nodes) {
                if (node.role() == ClusterRole.LEADER) {
                    return node;
                }
            }
            Thread.sleep(75);
        }
        throw new AssertionError("nenhum líder eleito");
    }

    private static ServerSocket slowPeer() throws Exception {
        ServerSocket server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread.ofVirtual().start(() -> {
                        try (socket) {
                            Thread.sleep(2_000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                    return;
                }
            }
        });
        return server;
    }

    @Test
    void elegeLiderPorTcpEReplicaOBatchParaOsSeguidores() throws Exception {
        List<Integer> ports = reservePorts(3);
        InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", ports.get(0));
        InetSocketAddress a2 = new InetSocketAddress("127.0.0.1", ports.get(1));
        InetSocketAddress a3 = new InetSocketAddress("127.0.0.1", ports.get(2));
        try (ClusterRuntime n1 = new ClusterRuntime(new ClusterConfig("n1", "g", a1, List.of(a2, a3)),
                Files.createTempDirectory("n1"), new MemoryBackend());
             ClusterRuntime n2 = new ClusterRuntime(new ClusterConfig("n2", "g", a2, List.of(a1, a3)),
                Files.createTempDirectory("n2"), new MemoryBackend());
             ClusterRuntime n3 = new ClusterRuntime(new ClusterConfig("n3", "g", a3, List.of(a1, a2)),
                Files.createTempDirectory("n3"), new MemoryBackend())) {
            ClusterRuntime leader = awaitLeader(List.of(n1, n2, n3));
            long index = leader.confirm(leader.config().nodeId(),
                new CommittedBatch("tx", Map.of("x", new byte[] {9}), Set.of()));
            assertEquals(1, index);
            Thread.sleep(300);
            assertArrayEquals(new byte[] {9}, n1.backend().get("x").orElseThrow());
            assertArrayEquals(new byte[] {9}, n2.backend().get("x").orElseThrow());
            assertArrayEquals(new byte[] {9}, n3.backend().get("x").orElseThrow());

            // O status do líder reflete papel, termo e commit reais, com quórum vivo.
            ClusterStatusProvider.Status status = leader.status();
            assertEquals(ClusterRole.LEADER, status.role());
            assertEquals(leader.config().nodeId(), status.leader());
            assertEquals(1, status.commitIndex());
            assertEquals(3, status.members());
            assertEquals(2, status.quorum());
            assertTrue(status.active() >= status.quorum(), "líder sem quórum vivo: " + status);
            assertTrue(status.ready());
        }
    }

    @Test
    void seguidorRecusaEscritaEAnunciaOEnderecoDoLider() throws Exception {
        List<Integer> ports = reservePorts(2);
        InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", ports.get(0));
        InetSocketAddress a2 = new InetSocketAddress("127.0.0.1", ports.get(1));
        try (ClusterRuntime n1 = new ClusterRuntime(new ClusterConfig("n1", "g", a1, List.of(a2)),
                Files.createTempDirectory("n1"), new MemoryBackend());
             ClusterRuntime n2 = new ClusterRuntime(new ClusterConfig("n2", "g", a2, List.of(a1)),
                Files.createTempDirectory("n2"), new MemoryBackend())) {
            ClusterRuntime leader = awaitLeader(List.of(n1, n2));
            ClusterRuntime follower = leader == n1 ? n2 : n1;
            // O seguidor só aprende o endereço do líder quando um heartbeat chega.
            for (int attempt = 0; attempt < 40 && follower.leaderAddress().isEmpty(); attempt++) {
                Thread.sleep(75);
            }

            NotLeaderException moved = follower.notLeader();
            assertEquals(leader.config().nodeId(), moved.leader());
            assertEquals(RaftWire.advertise(leader.config().advertiseAddress()),
                moved.leaderAddress());
            assertTrue(moved.hasLeaderAddress());

            ClusterStatusProvider.Status status = follower.status();
            assertEquals(ClusterRole.FOLLOWER, status.role());
            assertFalse(status.isLeader());
            // Um seguidor que conhece o líder está pronto para servir leituras.
            assertTrue(status.ready());
        }
    }

    @Test
    void iniciaListenerDoNo() throws Exception {
        var dir = Files.createTempDirectory("node");
        try (var r = new ClusterRuntime(new ClusterConfig("n1", "c",
            new InetSocketAddress("127.0.0.1", 0), List.of()), dir)) {
            assertTrue(r.port() > 0);
            assertEquals("n1", r.status().nodeId());
        }
    }

    @Test
    void addPeerRemoveERecalculanQuorum() throws Exception {
        var dir = Files.createTempDirectory("node");
        try (var rt = new ClusterRuntime(new ClusterConfig("n1", "g",
            new InetSocketAddress("127.0.0.1", 0), List.of()), dir)) {
            awaitLeader(List.of(rt));
            assertEquals(1, rt.members());
            assertEquals(1, rt.quorum());

            InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 9001);
            assertTrue(rt.addPeer("n2", peer));
            assertEquals(2, rt.members());
            assertEquals(2, rt.quorum());
            // Non duplica membros xa coñecidos.
            assertFalse(rt.addPeer("n2", peer));
            assertEquals(2, rt.members());
            assertTrue(rt.peerLag().containsKey(peer));

            assertTrue(rt.removePeer("127.0.0.1:9001"));
            assertEquals(1, rt.members());
            assertEquals(1, rt.quorum());
            assertFalse(rt.peerLag().containsKey(peer));
            assertFalse(rt.removePeer("127.0.0.1:9001"), "retirar un non-membro é NOOP");
        }
    }

    @Test
    void peersPersistenNoReinicioDoNo() throws Exception {
        var dir = Files.createTempDirectory("node");
        InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 9001);
        try (var rt = new ClusterRuntime(new ClusterConfig("n", "g",
            new InetSocketAddress("127.0.0.1", 0), List.of()), dir, new MemoryBackend())) {
            awaitLeader(List.of(rt));
            rt.addPeer("n2", peer);
        }
        try (var rt2 = new ClusterRuntime(new ClusterConfig("n", "g",
            new InetSocketAddress("127.0.0.1", 0), List.of()), dir, new MemoryBackend())) {
            assertEquals(2, rt2.members());
            assertEquals(2, rt2.quorum());
        }
    }

    @Test
    void eleccionIncrementaOContador() throws Exception {
        var dir = Files.createTempDirectory("node");
        try (var rt = new ClusterRuntime(new ClusterConfig("n", "g",
            new InetSocketAddress("127.0.0.1", 0), List.of()), dir)) {
            awaitLeader(List.of(rt));
            assertTrue(rt.electionsStarted() >= 1);
        }
    }

    @Test
    void confirmSenQuorumIncrementaOFalho() throws Exception {
        List<Integer> ports = reservePorts(2);
        InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", ports.get(0));
        InetSocketAddress a2 = new InetSocketAddress("127.0.0.1", ports.get(1));
        try (ClusterRuntime n1 = new ClusterRuntime(new ClusterConfig("n1", "g", a1, List.of(a2)),
                Files.createTempDirectory("n1"), new MemoryBackend());
             ClusterRuntime n2 = new ClusterRuntime(new ClusterConfig("n2", "g", a2, List.of(a1)),
                Files.createTempDirectory("n2"), new MemoryBackend())) {
            ClusterRuntime leader = awaitLeader(List.of(n1, n2));
            ClusterRuntime follower = leader == n1 ? n2 : n1;
            follower.close();

            long before = leader.quorumUnavailable();
            assertThrows(QuorumUnavailableException.class, () -> leader.confirm(
                leader.config().nodeId(), new CommittedBatch("fail-quorum", Map.of("x", new byte[] {2}), Set.of())));
            assertTrue(leader.quorumUnavailable() > before,
                "confirm sen quórum debe incrementar quorumUnavailable");
        }
    }

    @Test
    void aplicaAppendNoBackendDoRuntimeERecuperaDoLog() throws Exception {
        var dir = Files.createTempDirectory("node");
        var backend = new MemoryBackend();
        var config = new ClusterConfig("n1", "c", new InetSocketAddress("127.0.0.1", 0), List.of());
        try (var runtime = new ClusterRuntime(config, dir, backend)) {
            var response = RaftWire.call(new InetSocketAddress("127.0.0.1", runtime.port()),
                new RaftWire.Request(RaftWire.APPEND, "c", 1, "leader", 1, 1,
                    RaftPayload.encode(new CommittedBatch("tx", Map.of("record", new byte[] {7}),
                        Set.of()))), 1000);
            assertTrue(response.accepted());
            assertArrayEquals(new byte[] {7}, backend.get("record").orElseThrow());
        }
        var recovered = new MemoryBackend();
        try (var runtime = new ClusterRuntime(config, dir, recovered)) {
            assertArrayEquals(new byte[] {7}, recovered.get("record").orElseThrow());
        }
    }

    @Test
    void confirmNaoEsperaTimeoutSerialDePeerLentoDepoisDoQuorum() throws Exception {
        List<Integer> ports = reservePorts(2);
        InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", ports.get(0));
        InetSocketAddress a2 = new InetSocketAddress("127.0.0.1", ports.get(1));
        try (ServerSocket slow = slowPeer();
             ClusterRuntime n1 = new ClusterRuntime(new ClusterConfig("n1", "g", a1,
                 List.of(new InetSocketAddress("127.0.0.1", slow.getLocalPort()), a2)),
                 Files.createTempDirectory("n1"), new MemoryBackend());
             ClusterRuntime n2 = new ClusterRuntime(new ClusterConfig("n2", "g", a2,
                 List.of(new InetSocketAddress("127.0.0.1", slow.getLocalPort()), a1)),
                 Files.createTempDirectory("n2"), new MemoryBackend())) {
            ClusterRuntime leader = awaitLeader(List.of(n1, n2));

            long started = System.nanoTime();
            long index = leader.confirm(leader.config().nodeId(),
                new CommittedBatch("fast-quorum", Map.of("x", new byte[] {1}), Set.of()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals(1, index);
            assertTrue(elapsedMillis < 400,
                "quórum respondeu, mas confirm esperou peer lento por " + elapsedMillis + " ms");
        }
    }
}
