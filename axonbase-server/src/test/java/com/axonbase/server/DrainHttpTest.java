package com.axonbase.server;

import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Servidor: drenar o líder sem erro visível ao cliente HTTP")
@Timeout(180)
class DrainHttpTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, ClusterRuntime> runtimes = new LinkedHashMap<>();
    private final Map<String, AxonServer> servers = new LinkedHashMap<>();

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        List<String> ids = List.of("n1", "n2", "n3");
        Map<String, InetSocketAddress> raft = new LinkedHashMap<>();
        for (String id : ids) {
            raft.put(id, new InetSocketAddress("127.0.0.1", freePort()));
        }
        for (String id : ids) {
            List<InetSocketAddress> peers = ids.stream().filter(o -> !o.equals(id)).map(raft::get).toList();
            MemoryBackend backend = new MemoryBackend();
            Datastore ds = new Datastore(backend);
            ClusterRuntime runtime = new ClusterRuntime(new ClusterConfig(id, "g", raft.get(id), peers),
                dir.resolve(id), backend);
            runtime.appliedBatchListener(ds.appliedBatchListener());
            ds.commitCoordinator(runtime, id);
            AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
            server.clusterStatus(runtime);
            runtimes.put(id, runtime);
            servers.put(id, server);
        }
        StringBuilder urls = new StringBuilder();
        servers.forEach((id, server) -> urls.append(id).append("=http://127.0.0.1:").append(server.port())
            .append(','));
        Map<String, String> env = Map.of("AXON_CLUSTER_HTTP_PEERS", urls.toString(), "AXON_DRAIN_GRACE_MS", "100");
        servers.values().forEach(server -> server.operations(env::get));
        await("sem líder", () -> leader() != null && runtimes.get(leader()).status().ready());
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AxonServer server : servers.values()) {
            server.stop();
        }
        for (ClusterRuntime runtime : runtimes.values()) {
            runtime.close();
        }
    }

    private String leader() {
        return runtimes.entrySet().stream().filter(e -> e.getValue().role() == ClusterRole.LEADER)
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static void await(String message, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(20);
        }
    }

    private HttpResponse<String> send(String node, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + servers.get(node).port() + path))
            .timeout(Duration.ofSeconds(30)).header("Axon-Ns", "n").header("Axon-Db", "d");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, AxonValue> json(HttpResponse<String> response) {
        return AxonJson.parseDocument(response.body()).asObject();
    }

    @Test
    @DisplayName("com o líder drenado, escritas contínuas em qualquer nó continuam sem erro")
    void drainingTheLeaderIsInvisible() throws Exception {
        String first = leader();
        assertEquals(200, send(first, "POST", "/sql", "DEFINE TABLE item").statusCode());
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger sequence = new AtomicInteger();
        Set<Integer> acknowledged = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<String> visible = new ConcurrentLinkedQueue<>();
        List<Thread> clients = new ArrayList<>();
        for (String node : servers.keySet()) {
            clients.add(Thread.ofPlatform().start(() -> {
                while (running.get()) {
                    int id = sequence.incrementAndGet();
                    try {
                        while (running.get()) {
                            HttpResponse<String> response =
                                send(node, "POST", "/sql", "CREATE item:" + id + " CONTENT { n: 1 }");
                            if (response.statusCode() == 200) {
                                acknowledged.add(id);
                                break;
                            }
                            // 503 e 307 sem seguir: nada foi executado, repete
                            if (response.statusCode() != 503 && response.statusCode() != 307) {
                                visible.add(node + " " + response.statusCode() + " " + response.body());
                                return;
                            }
                            Thread.sleep(10);
                        }
                    } catch (Exception failure) {
                        visible.add(node + " " + failure);
                        return;
                    }
                }
            }));
        }
        try {
            await("escritas não começaram: " + visible, () -> acknowledged.size() > 30 || !visible.isEmpty());

            HttpResponse<String> accepted = send(first, "POST", "/admin/v1/cluster/drain", null);
            assertEquals(202, accepted.statusCode(), accepted.body());
            await("a drenagem não terminou", () -> servers.get(first).drain().status().state()
                != NodeDrain.State.DRAINING);

            NodeDrain.Status status = servers.get(first).drain().status();
            assertEquals(NodeDrain.State.DRAINED, status.state(), status.reason());
            assertNotEquals(first, leader());
            assertEquals(503, send(first, "GET", "/ready", null).statusCode());
            assertEquals("DRAINED", json(send(first, "GET", "/admin/v1/cluster/drain", null)).get("state").asString());
            int before = acknowledged.size();
            await("escritas pararam: " + visible, () -> acknowledged.size() > before + 30 || !visible.isEmpty());
        } finally {
            running.set(false);
            for (Thread client : clients) {
                client.join(30_000);
            }
        }
        assertTrue(visible.isEmpty(), "erros visíveis: " + visible);
        assertEquals(200, send(first, "DELETE", "/admin/v1/cluster/drain", null).statusCode());
        await("o nó não voltou a ficar pronto", () -> {
            try {
                return send(first, "GET", "/ready", null).statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    @DisplayName("saúde, transferência e verificação de versões pela API")
    void operationsApi() throws Exception {
        String first = leader();
        Map<String, AxonValue> health = json(send(first, "GET", "/admin/v1/cluster/health", null));
        assertEquals("LEADER", health.get("role").asString());
        assertEquals(3, health.get("protocol").asLong());
        assertEquals(2, health.get("peers").asArray().size());

        String follower = servers.keySet().stream().filter(id -> !id.equals(first)).findFirst().orElseThrow();
        assertEquals(409, send(follower, "POST", "/admin/v1/cluster/transfer-leadership", null).statusCode());
        HttpResponse<String> moved = send(first, "POST", "/admin/v1/cluster/transfer-leadership", null);
        assertEquals(200, moved.statusCode(), moved.body());
        assertNotEquals(first, json(moved).get("leader").asString());

        HttpResponse<String> check = send(first, "GET",
            "/admin/v1/cluster/upgrade-check?target=0.10.0&members=n1=0.10.1,n2=0.10.1", null);
        assertEquals(200, check.statusCode(), check.body());
        assertEquals(false, json(check).get("allowed").asBool());
        HttpResponse<String> fromOldLine = send(first, "GET",
            "/admin/v1/cluster/upgrade-check?target=1.0.0&members=n1=0.10.1,n2=0.10.1", null);
        assertEquals(false, json(fromOldLine).get("allowed").asBool(), fromOldLine.body());
        assertTrue(fromOldLine.body().contains("pare os três nós"), fromOldLine.body());
    }
}
