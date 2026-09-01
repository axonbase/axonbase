package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Servidor: observabilidade e robustez HTTP")
class ObservabilityTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static AxonServer startServer() throws Exception {
        Datastore ds = Datastore.memory();
        return AxonServer.startRandomPort(ds, "secret-obs");
    }

    private static AxonServer startServerAuth() throws Exception {
        Datastore ds = Datastore.memory();
        return AxonServer.startRandomPort(ds, "secret-obs", "root", "root", true);
    }

    private static String get(int port, String path) throws Exception {
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private static HttpResponse<String> getFull(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------
    // 1. Metrics reais do WebSocket
    // ------------------------------------------------------------------

    @Test
    void webSocketMetricsIncrementamCorretamente() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();

            String metrics = get(port, "/metrics");
            assertTrue(metrics.contains("axe_ws_open 0"), metrics);
            assertTrue(metrics.contains("axe_ws_frames_received_total 0"), metrics);
            assertTrue(metrics.contains("axe_ws_frames_sent_total 0"), metrics);
            assertTrue(metrics.contains("axe_ws_response_count_total 0"), metrics);
            assertTrue(metrics.contains("axe_ws_query_errors_total 0"), metrics);
            assertTrue(metrics.contains("axe_ws_not_leader_errors_total 0"), metrics);

            WebSocket socket = HTTP.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/rpc/ws"),
                    new WebSocket.Listener() { })
                .join();
            try {
                metrics = get(port, "/metrics");
                assertTrue(metrics.contains("axe_ws_open 1"), metrics);
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            }
        } finally {
            srv.stop();
        }
    }

    // ------------------------------------------------------------------
    // 2. Timeout de query
    // ------------------------------------------------------------------

    @Test
    void queryTimeoutHttpRetornaErro() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "secret-obs");
        try {
            int port = srv.port();
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/rpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"id\":1,\"method\":\"query\",\"params\":[\"SELECT * FROM SLEEP 30000\"]}"))
                .timeout(Duration.ofSeconds(10))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"error\""), resp.body());
        } finally {
            srv.stop();
        }
    }

    // ------------------------------------------------------------------
    // 3. Rate limit
    // ------------------------------------------------------------------

    @Test
    void rateLimitExcedeDevolve429() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        ServerConfig cfg = new ServerConfig("memory", "secret", "root", "root",
            0, "127.0.0.1", false, "", "", "", "",
            30000L, 300000L, 1, "", "", "", "",
            10000L, 4, 100, "", "", "", "", "", 0L, "", 10, 0, "pt-BR", "", "", "", "");
        AxonServer srv = AxonServer.start(ds, "secret", cfg);
        try {
            int port = srv.port();
            boolean found429 = false;
            for (int i = 0; i < 10; i++) {
                HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 429) {
                    found429 = true;
                    AxonValue body = AxonJson.parseDocument(resp.body());
                    assertEquals(-32029, body.asObject().get("error").asObject().get("code").asLong());
                    break;
                }
            }
            assertTrue(found429, "deveria ter recebido 429 com rate limit de 1 req/s");
        } finally {
            srv.stop();
        }
    }

    // ------------------------------------------------------------------
    // 4. CORS
    // ------------------------------------------------------------------

    @Test
    void corsHeadersPresentesQuandoConfigurado() throws Exception {
        Datastore ds = Datastore.memory();
        ServerConfig cfg = new ServerConfig("memory", "secret", "root", "root",
            0, "127.0.0.1", false, "", "", "", "",
            30000L, 300000L, 0, "http://example.com", "", "", "",
            10000L, 4, 100, "", "", "", "", "", 0L, "", 10, 0, "pt-BR", "", "", "", "");
        AxonServer srv = AxonServer.start(ds, "secret", cfg);
        try {
            int port = srv.port();
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                .header("Origin", "http://example.com")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("http://example.com", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
            assertEquals("true", resp.headers().firstValue("Access-Control-Allow-Credentials").orElse(""));
        } finally {
            srv.stop();
        }
    }

    @Test
    void corsAusenteQuandoNaoConfigurado() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                .header("Origin", "http://example.com")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(resp.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        } finally {
            srv.stop();
        }
    }

    // ------------------------------------------------------------------
    // 5. TLS: sem certificado, sobe normalmente
    // ------------------------------------------------------------------

    @Test
    void servidorSobeSemTlsQuandoNaoConfigurado() throws Exception {
        AxonServer srv = startServer();
        try {
            String health = get(srv.port(), "/health");
            assertTrue(health.contains("\"ok\""));
        } finally {
            srv.stop();
        }
    }

    // ------------------------------------------------------------------
    // 6. Logging filter: correlation id propagado
    // ------------------------------------------------------------------

    @Test
    void correlationIdPropagadoViaHeader() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String customId = "my-custom-request-id-123";
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                .header("X-Request-Id", customId)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(customId, resp.headers().firstValue("X-Request-Id").orElse(""));
        } finally {
            srv.stop();
        }
    }

    @Test
    void correlationIdGeradoQuandoAusente() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            String returned = resp.headers().firstValue("X-Request-Id").orElse("");
            assertFalse(returned.isEmpty());
        } finally {
            srv.stop();
        }
    }

    // ------------------------------------------------------------------
    // 7. Export/Import em seguidor: 307 ou 503 sem aplicar
    // ------------------------------------------------------------------

    @Test
    void exportEmSeguidorNaoCriaDatabase() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "secret", "root", "root", true);
        try {
            srv.clusterStatus(() -> new ClusterStatusProvider.Status(
                "n2", "g", "n1", "10.0.0.1:8001",
                ClusterRole.FOLLOWER, 1, 1, 1, 3, 2));

            String token = doSignin(srv.port(), "root", "root");

            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + srv.port() + "/export"))
                .header("Axon-Ns", "missing-ns")
                .header("Axon-Db", "missing-db")
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(resp.statusCode() == 307 || resp.statusCode() == 503);
            assertFalse(ds.hasDatabase("missing-ns", "missing-db"),
                "seguidor não deve criar database com export");
        } finally {
            srv.stop();
        }
    }

    @Test
    void importEmSeguidorRejeitaSemAplicar() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "secret", "root", "root", true);
        try {
            srv.clusterStatus(() -> new ClusterStatusProvider.Status(
                "n2", "g", "n1", "10.0.0.1:8001",
                ClusterRole.FOLLOWER, 1, 1, 1, 3, 2));

            String token = doSignin(srv.port(), "root", "root");

            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + srv.port() + "/import"))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("CREATE test CONTENT {}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(resp.statusCode() == 307 || resp.statusCode() == 503);
            assertFalse(ds.hasDatabase("n", "d"),
                "seguidor não deve aplicar import " + resp.statusCode());
        } finally {
            srv.stop();
        }
    }

    private static String doSignin(int port, String user, String pass) throws Exception {
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"user\":\"" + user + "\",\"pass\":\"" + pass + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        AxonValue body = AxonJson.parseDocument(resp.body());
        return body.asObject().get("token").asString();
    }
}
