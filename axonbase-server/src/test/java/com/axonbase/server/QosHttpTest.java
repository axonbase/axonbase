package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Servidor: prioridade por tipo de carga")
@Timeout(60)
class QosHttpTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Datastore ds;
    private AxonServer server;
    private String base;
    private CountDownLatch release;
    private CompletableFuture<Void> holder;

    @BeforeEach
    void setUp() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("n", "d");
        ds.execute("CREATE item:1 CONTENT { x: 1 }", session(), null);
        server = AxonServer.startRandomPort(ds, "secret-testing");
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (release != null) {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        server.stop();
    }

    private static Session session() {
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        return session;
    }

    private void enable() {
        Map<String, String> env = Map.of("AXON_QOS", "auto", "AXON_QOS_ANALYTICAL_CONCURRENCY", "1",
            "AXON_QOS_ANALYTICAL_QUEUE", "0");
        server.qos(env::get);
    }

    /** Ocupa a única vaga analítica, como uma consulta longa de outro cliente. */
    private void holdAnalyticalSlot() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holder = CompletableFuture.runAsync(() -> {
            try (var ticket = server.workload().admit("SELECT * FROM item", session())) {
                holding.countDown();
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(holding.await(10, TimeUnit.SECONDS));
    }

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
            .header("Axon-Ns", "n").header("Axon-Db", "d");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private String metrics() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).build(),
            HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    @DisplayName("sem AXON_QOS nada muda")
    void disabledByDefault() throws Exception {
        server.qos(name -> null);

        assertNull(server.workload());
        assertEquals(200, post("/sql", "SELECT * FROM item").statusCode());
        assertTrue(metrics().contains("axe_qos_enabled 0"));
    }

    @Test
    @DisplayName("analítica sem vaga recebe 503 repetível e a transação continua")
    void rejectsAnalyticalOnly() throws Exception {
        enable();
        holdAnalyticalSlot();

        HttpResponse<String> refused = post("/sql", "SELECT * FROM item");
        HttpResponse<String> write = post("/sql", "CREATE item:2 CONTENT { x: 2 }");
        HttpResponse<String> point = post("/sql", "SELECT * FROM item:1");

        assertEquals(503, refused.statusCode(), refused.body());
        assertEquals("1", refused.headers().firstValue("Retry-After").orElse(""));
        assertEquals("QOS_REJECTED", AxonJson.parseDocument(refused.body()).asObject().get("kind").asString());
        assertEquals(200, write.statusCode(), write.body());
        assertEquals(200, point.statusCode(), point.body());
        String metrics = metrics();
        assertTrue(metrics.contains("axe_qos_rejected_total{reason=\"queue_full\"} 1"), metrics);
        assertTrue(metrics.contains("axe_qos_running{class=\"analytical\"} 1"), metrics);
    }

    @Test
    @DisplayName("o cliente pode pedir que uma leitura pequena seja tratada como analítica")
    void headerAsksForAnalytical() throws Exception {
        enable();
        holdAnalyticalSlot();

        HttpResponse<String> refused = post("/sql", "SELECT * FROM item:1", "Axon-Workload", "analytical");

        assertEquals(503, refused.statusCode(), refused.body());
    }

    @Test
    @DisplayName("a listagem de tabela por REST também respeita as vagas")
    void tableEndpointIsGoverned() throws Exception {
        enable();
        holdAnalyticalSlot();

        HttpResponse<String> refused = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/table/item"))
            .header("Axon-Ns", "n").header("Axon-Db", "d").build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(503, refused.statusCode(), refused.body());
    }

    @Test
    @DisplayName("no RPC a recusa tem código próprio e o campo workload vale")
    void rpcRefusal() throws Exception {
        enable();
        holdAnalyticalSlot();

        HttpResponse<String> refused = post("/rpc",
            "{\"id\":1,\"method\":\"query\",\"workload\":\"analytical\",\"params\":[\"SELECT * FROM item:1\"]}");

        Map<String, AxonValue> error = AxonJson.parseDocument(refused.body()).asObject().get("error").asObject();
        assertEquals(-32015, error.get("code").asLong(), refused.body());
    }
}
