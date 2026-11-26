package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nós saudáveis e sob carga não abrem eleição: nem quando o disco do líder segura o log por mais que o prazo de
 * eleição, nem quando um seguidor passa esse tempo recebendo requisições antes de atender.
 */
@DisplayName("Cluster: sem eleição quando o líder está vivo")
class ClusterElectionUnderLoadTest {

    /** Mais que o maior prazo de eleição (850 ms), com folga. */
    private static final long STALL_MILLIS = 2_500;

    @TempDir
    Path dir;

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test
    @DisplayName("log do líder preso por um fsync demorado não derruba a liderança")
    void aStalledLeaderLogDoesNotStartAnElection() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "antes", "v");
            cluster.awaitConverged();
            List<ClusterRuntime> nodes = cluster.nodes();
            long elections = nodes.stream().mapToLong(ClusterRuntime::electionsStarted).sum();

            // É a trava que o fsync de um segmento cheio segura em appendEncoded.
            Object log = field(field(leader, "node"), "log");
            CountDownLatch held = new CountDownLatch(1);
            Thread disk = Thread.ofPlatform().start(() -> {
                synchronized (log) {
                    held.countDown();
                    try {
                        Thread.sleep(STALL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue(held.await(10, TimeUnit.SECONDS));
            disk.join(60_000);
            Thread.sleep(500);

            assertEquals(elections, nodes.stream().mapToLong(ClusterRuntime::electionsStarted).sum(),
                cluster.describe());
            assertEquals(ClusterRole.LEADER, leader.role(), cluster.describe());
            write(leader, "depois", "v");
        }
    }

    @Test
    @DisplayName("log do líder preso por mais de 10 segundos acaba em troca de líder")
    void aLeaderStuckForTooLongIsReplaced() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "antes", "v");
            cluster.awaitConverged();
            Object log = field(field(leader, "node"), "log");
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread disk = Thread.ofPlatform().start(() -> {
                synchronized (log) {
                    held.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            try {
                assertTrue(held.await(10, TimeUnit.SECONDS));
                ClusterHarness.await("o líder preso não foi substituído", 40_000, () -> cluster.nodes().stream()
                    .anyMatch(node -> node != leader && node.role() == ClusterRole.LEADER));
            } finally {
                release.countDown();
                disk.join(60_000);
            }
        }
    }

    private static byte[] encoded(RaftWire.Request request) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RaftWire.write(new DataOutputStream(bytes), request);
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("seguidor que recebe sem parar, antes de atender, conta o líder como vivo")
    void aFollowerStillReceivingDoesNotStartAnElection() throws Exception {
        List<Integer> ports = ClusterHarness.reservePorts(3);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", ports.get(0));
        // Os outros dois membros não existem: o teste faz o papel do líder.
        List<InetSocketAddress> peers = List.of(new InetSocketAddress("127.0.0.1", ports.get(1)),
            new InetSocketAddress("127.0.0.1", ports.get(2)));
        try (ClusterRuntime follower = new ClusterRuntime(new ClusterConfig("f", "g", address, peers),
                dir.resolve("f"), new MemoryBackend());
             Socket socket = new Socket()) {
            socket.connect(address, 5_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] heartbeat = encoded(new RaftWire.Request(RaftWire.APPEND_STREAM, "g", 1_000, "l", "", 0, 0,
                RaftStream.encode(0, List.of())));
            out.write(heartbeat);
            out.flush();
            assertTrue(RaftWire.readResponse(in).accepted());
            long elections = follower.electionsStarted();

            // Cada requisição completa chega junto do começo da seguinte: o servidor continua juntando.
            out.write(heartbeat, 0, 1);
            out.flush();
            for (long waited = 0; waited < STALL_MILLIS; waited += 100) {
                Thread.sleep(100);
                out.write(heartbeat, 1, heartbeat.length - 1);
                out.write(heartbeat, 0, 1);
                out.flush();
            }

            assertEquals(elections, follower.electionsStarted());
        }
    }
}
