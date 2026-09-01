package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Servidor: endpoints HTTP")
class ServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static AxonServer startServer() throws Exception {
        Datastore ds = Datastore.memory();
        return AxonServer.startRandomPort(ds, "secret-testing");
    }

    private static String post(int port, String path, String body, String ns, String db) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (ns != null) {
            b.header("Axon-Ns", ns);
        }
        if (db != null) {
            b.header("Axon-Db", db);
        }
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    @Test
    void healthEVersion() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            HttpResponse<String> h = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, h.statusCode());
            assertTrue(h.body().contains("\"ok\""));

            h = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/version")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, h.statusCode());
        } finally {
            srv.stop();
        }
    }

    @Test
    void sqlCrud() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String create = post(port, "/sql", "CREATE person CONTENT { name: \"Ana\", age: 30 }", "test", "dev");
            AxonValue created = AxonJson.parseDocument(create);
            assertTrue(created.isArray());
            assertEquals(1, created.asArray().size());

            String sel = post(port, "/sql", "SELECT * FROM person", "test", "dev");
            AxonValue rows = AxonJson.parseDocument(sel);
            assertTrue(rows.isArray());
            assertEquals(1, rows.asArray().size());
            assertEquals("Ana", rows.asArray().get(0).asObject().get("name").asString());
        } finally {
            srv.stop();
        }
    }

    @Test
    void correlationHeaderCapturesSagaStep() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            post(port, "/sql", "CREATE orders:o1 CONTENT { total: 150 }", "test", "dev");
            post(port, "/sql", "BEGIN SAGA pedido WITH CORRELATION 'header_corr'", "test", "dev");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/sql"))
                .header("Content-Type", "text/plain")
                .header("Axon-Ns", "test")
                .header("Axon-Db", "dev")
                .header("X-Correlation-Id", "header_corr")
                .POST(HttpRequest.BodyPublishers.ofString("UPDATE orders:o1 SET total = 200"))
                .build();
            HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            AxonValue transaction = AxonJson.parseDocument(
                post(port, "/sql", "SHOW SAGA TRANSACTION pedido 'header_corr'", "test", "dev"));
            assertEquals(1, transaction.asObject().get("steps").asArray().size());
        } finally {
            srv.stop();
        }
    }

    @Test
    void cancelMirrorsParticipantStepsWithoutRepeatingCompensation() throws Exception {
        AxonServer orchestrator = startServer();
        AxonServer participant = startServer();
        try {
            int orchestratorPort = orchestrator.port();
            int participantPort = participant.port();
            String ns = "test";
            String orchestratorDb = "orchestrator";
            String participantDb = "participant";
            String correlationId = "participant_mirror";

            post(orchestratorPort, "/sql", "DEFINE DATABASE LINK \"participant\" CONNECT BY \"ws://127.0.0.1:"
                + participantPort + "/rpc/ws\" WITH ns = \"" + ns + "\" db = \"" + participantDb
                + "\" user = \"\" password = \"\"", ns, orchestratorDb);
            post(orchestratorPort, "/sql", "CREATE SAGA pedido WITH DATABASES 'participant'", ns, orchestratorDb);
            post(orchestratorPort, "/sql", "BEGIN SAGA pedido WITH CORRELATION '" + correlationId + "'", ns, orchestratorDb);

            HttpRequest write = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + participantPort + "/sql"))
                .header("Content-Type", "text/plain")
                .header("Axon-Ns", ns)
                .header("Axon-Db", participantDb)
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString("CREATE orders:o1 CONTENT { total: 200 }"))
                .build();
            assertEquals(200, HTTP.send(write, HttpResponse.BodyHandlers.ofString()).statusCode());

            post(orchestratorPort, "/sql", "CANCEL SAGA pedido WITH CORRELATION '" + correlationId + "'", ns, orchestratorDb);

            AxonValue transaction = AxonJson.parseDocument(post(orchestratorPort, "/sql",
                "SHOW SAGA TRANSACTION pedido '" + correlationId + "'", ns, orchestratorDb));
            AxonValue mirrored = transaction.asObject().get("steps").asArray().get(0);
            assertEquals("REPORTED", mirrored.asObject().get("status").asString());
            assertEquals("PREPARED", mirrored.asObject().get("participant_status").asString());

            AxonValue participantRows = AxonJson.parseDocument(
                post(participantPort, "/sql", "SELECT * FROM orders:o1", ns, participantDb));
            assertEquals(0, participantRows.asArray().size());
        } finally {
            orchestrator.stop();
            participant.stop();
        }
    }

    @Test
    void restTable() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String r = post(port, "/table/person", "{\"name\":\"Bob\",\"age\":21}", "test", "dev");
            assertTrue(r.contains("\"Bob\""));
        } finally {
            srv.stop();
        }
    }

    @Test
    void signinDevolveToken() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String r = post(port, "/signin", "{\"user\":\"root\",\"pass\":\"root\"}", null, null);
            assertTrue(r.contains("\"token\":"));
        } finally {
            srv.stop();
        }
    }

    @Test
    void rpcPing() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String ping = post(port, "/rpc", "{\"id\":1,\"method\":\"ping\",\"params\":[]}", "test", "dev");
            assertTrue(ping.contains("\"result\":true"));
        } finally {
            srv.stop();
        }
    }

    @Test
    void metricsUsamFormatoPrometheusEEstadoDoCluster() throws Exception {
        AxonServer srv = startServer();
        try {
            srv.clusterStatus(() -> new ClusterStatusProvider.Status(
                "node-1", "cluster-1", "node-1", "127.0.0.1:8000", ClusterRole.LEADER,
                7, 42, 3, 5, 3));

            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + srv.port() + "/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/plain; version=0.0.4"));
            assertTrue(response.body().contains("# TYPE axe_requests_total counter"));
            assertTrue(response.body().contains("# TYPE axe_ws_open gauge\naxe_ws_open 0"));
            assertTrue(response.body().contains("axe_cluster_role{role=\"LEADER\"} 1"));
            assertTrue(response.body().contains("axe_cluster_term 7"));
            assertTrue(response.body().contains("axe_cluster_commit_index 42"));
            assertTrue(response.body().contains("axe_cluster_active 3"));
            assertTrue(response.body().contains("axe_cluster_members 5"));
            assertTrue(response.body().contains("axe_cluster_quorum 3"));

            WebSocket socket = HTTP.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + srv.port() + "/rpc/ws"),
                    new WebSocket.Listener() { })
                .join();
            try {
                response = HTTP.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + srv.port() + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertTrue(response.body().contains("axe_ws_open 1"));
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "test").join();
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void exportExigeAuthENaoCriaDatabaseAusente() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "secret", "root", "root", true);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + srv.port() + "/export"))
                .header("Axon-Ns", "missing")
                .header("Axon-Db", "db")
                .GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertTrue(!ds.hasDatabase("missing", "db"));
        } finally {
            srv.stop();
        }
    }

    @Test
    void endpointsDeDadosExigemAutenticacaoQuandoConfigurada() throws Exception {
        AxonServer srv = AxonServer.startRandomPort(Datastore.memory(), "secret", "root", "root", true);
        try {
            int port = srv.port();
            HttpResponse<String> sql = request(port, "/sql", "SELECT * FROM person", "test", "dev", null);
            HttpResponse<String> table = request(port, "/table/person", "{}", "test", "dev", null);
            HttpResponse<String> graphql = request(port, "/graphql", "{\"query\":\"{ person { id } }\"}", "test", "dev", null);
            HttpResponse<String> mcp = request(port, "/mcp/message", "{\"id\":1,\"method\":\"tools/list\"}", "test", "dev", null);

            assertEquals(401, sql.statusCode());
            assertEquals(401, table.statusCode());
            assertEquals(401, graphql.statusCode());
            assertEquals(401, mcp.statusCode());
        } finally {
            srv.stop();
        }
    }

    @Test
    void bearerDeBancoAutorizaSqlETableNoEscopoDoToken() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.authCatalog().defineUser("alice", com.axonbase.core.security.AuthCatalog.Scope.DATABASE,
            "app", "main", "senha", java.util.List.of(), java.util.List.of());
        String token = new AuthService("secret", new UserStore(ds.authCatalog()))
            .signin("alice", "senha", "app", "main", null);
        AxonServer srv = AxonServer.startRandomPort(ds, "secret", "root", "root", true);
        try {
            int port = srv.port();
            HttpResponse<String> sql = request(port, "/sql", "CREATE person CONTENT { name: \"Ana\" }",
                "app", "main", token);
            HttpResponse<String> table = request(port, "/table/person", "{\"name\":\"Bob\"}",
                "app", "main", token);

            assertEquals(200, sql.statusCode());
            assertEquals(200, table.statusCode());
        } finally {
            srv.stop();
        }
    }

    @Test
    void mcpNaoAnunciaNemExecutaFerramentaDeConsultaArbitraria() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            HttpResponse<String> listed = request(port, "/mcp/message",
                "{\"id\":1,\"method\":\"tools/list\"}", "test", "dev", null);
            HttpResponse<String> called = request(port, "/mcp/message",
                "{\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"axon_query\",\"arguments\":{\"query\":\"SELECT * FROM person\"}}}",
                "test", "dev", null);

            assertEquals(200, listed.statusCode());
            assertTrue(!listed.body().contains("axon_query"));
            assertEquals(200, called.statusCode());
            AxonValue result = AxonJson.parseDocument(called.body()).asObject().get("result");
            assertTrue(result.asObject().containsKey("error"));
            assertTrue(result.asObject().get("error").asString().contains("axon_query"));
        } finally {
            srv.stop();
        }
    }

    private static HttpResponse<String> request(int port, String path, String body, String ns, String db,
                                                String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header("Axon-Ns", ns)
            .header("Axon-Db", db)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
