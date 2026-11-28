package com.axonbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O caminho de uma leitura não passa pela trava do consenso: ela serializaria todas as leituras de um nó em cluster,
 * disputando com a replicação, e um nó sozinho não paga esse custo.
 */
@DisplayName("Servidor: leitura em cluster sem esperar o consenso")
class ClusterReadLockTest {

    @TempDir
    Path dir;

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("uma leitura por /sql é respondida com a trava do consenso ocupada")
    void readsDoNotWaitForTheConsensusMonitor() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        ClusterConfig config = new ClusterConfig("n1", "g", new InetSocketAddress("127.0.0.1", freePort()), List.of());
        try (ClusterRuntime node = new ClusterRuntime(config, dir, new MemoryBackend())) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (node.role() != ClusterRole.LEADER && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(node.role() == ClusterRole.LEADER, "o nó sozinho não se tornou líder");
            AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
            try {
                server.clusterStatus(node);
                Field field = ClusterRuntime.class.getDeclaredField("node");
                field.setAccessible(true);
                Object consensus = field.get(node);
                HttpRequest read = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/sql"))
                    .header("Axon-Ns", "n")
                    .header("Axon-Db", "d")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("SELECT * FROM t"))
                    .build();

                synchronized (consensus) {
                    HttpResponse<String> response =
                        HttpClient.newHttpClient().send(read, HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode(), response.body());
                }
            } finally {
                server.stop();
            }
        }
    }
}
