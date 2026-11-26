package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.cluster.ReadConsistency;
import com.axonbase.core.cluster.StaleReadException;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Servidor: consistência de leitura no cluster")
class ClusterReadEndpointTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Nó de cluster de mentira: registra o que o transporte pediu e recusa quando mandado. */
    private static final class Node implements ClusterStatusProvider {
        final List<ReadConsistency> asked = new ArrayList<>();
        ClusterRole role = ClusterRole.FOLLOWER;
        boolean stale;

        @Override
        public Status status() {
            return new Status("n2", "g", "n1", "10.0.0.1:8000", role, 7, 42, 1, 3, 2);
        }

        @Override
        public void awaitRead(ReadConsistency consistency) {
            asked.add(consistency);
            if (stale) {
                throw new StaleReadException("nó atrasado");
            }
        }
    }

    private final Node node = new Node();
    private Datastore ds;
    private AxonServer server;

    @BeforeEach
    void start() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("n", "d");
        server = AxonServer.startRandomPort(ds, "secret-testing");
        server.clusterStatus(node);
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
    }

    private HttpResponse<String> send(String method, String path, String body, String consistency) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Content-Type", "application/json")
            .header("Axon-Ns", "n")
            .header("Axon-Db", "d")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (consistency != null) {
            request.header("Axon-Read-Consistency", consistency);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("/sql repassa a consistência pedida e, num seguidor, aplica o padrão quando nada é pedido")
    void sqlPassesTheRequestedConsistency() throws Exception {
        assertEquals(200, send("POST", "/sql", "SELECT * FROM t", "linearizable").statusCode());
        assertEquals(200, send("POST", "/sql", "SELECT * FROM t", "max-lag=250").statusCode());
        assertEquals(200, send("POST", "/sql", "SELECT * FROM t", null).statusCode());

        assertEquals(java.util.Arrays.asList(ReadConsistency.LINEARIZABLE, ReadConsistency.maxLag(250), null),
            node.asked);
    }

    @Test
    @DisplayName("leitura que o nó não consegue atender volta 503 STALE_READ com o líder")
    void sqlReportsStaleReads() throws Exception {
        node.stale = true;
        HttpResponse<String> response = send("POST", "/sql", "SELECT * FROM t", "linearizable");

        assertEquals(503, response.statusCode());
        Map<String, AxonValue> body = AxonJson.parseDocument(response.body()).asObject();
        assertEquals("STALE_READ", body.get("kind").asString());
        assertEquals("10.0.0.1:8000", body.get("leader_address").asString());
        assertEquals(503, send("GET", "/table/t", "", null).statusCode());
    }

    @Test
    @DisplayName("texto de consistência inválido é erro do cliente")
    void sqlRejectsUnknownConsistency() throws Exception {
        HttpResponse<String> response = send("POST", "/sql", "SELECT * FROM t", "forte");

        assertEquals(400, response.statusCode());
        assertTrue(node.asked.isEmpty());
    }

    @Test
    @DisplayName("escrita não passa pela barreira de leitura")
    void writesAreRedirectedNotBarriered() throws Exception {
        node.stale = true;
        assertEquals(307, send("POST", "/sql", "CREATE t CONTENT {x: 1}", "linearizable").statusCode());
        assertTrue(node.asked.isEmpty());
    }

    @Test
    @DisplayName("no líder sem consistência pedida o /sql não paga a análise extra nem a barreira")
    void leaderWithoutRequestSkipsTheBarrier() throws Exception {
        node.role = ClusterRole.LEADER;
        node.stale = true;
        assertEquals(200, send("POST", "/sql", "SELECT * FROM t", null).statusCode());
        assertEquals(503, send("POST", "/sql", "SELECT * FROM t", "linearizable").statusCode());
    }

    @Test
    @DisplayName("no RPC a consistência vem no campo consistency e a recusa é um erro estruturado")
    void rpcHonoursConsistencyField() {
        RpcDispatcher rpc = new RpcDispatcher(ds);
        rpc.clusterStatus(() -> node.status());
        rpc.readBarrier(node::awaitRead);
        Session session = Session.create();
        session.namespace("n");
        session.database("d");

        rpc.dispatch("{\"id\":1,\"method\":\"query\",\"consistency\":\"max-lag=10\","
            + "\"params\":[\"SELECT * FROM t\"]}", session);
        rpc.dispatch("{\"id\":2,\"method\":\"select\",\"params\":[\"t\"]}", session);
        rpc.dispatch("{\"id\":3,\"method\":\"ping\"}", session);
        assertEquals(java.util.Arrays.asList(ReadConsistency.maxLag(10), null), node.asked);

        node.stale = true;
        Map<String, AxonValue> error = AxonJson.parseDocument(rpc.dispatch(
            "{\"id\":4,\"method\":\"query\",\"params\":[\"SELECT * FROM t\"]}", session))
            .asObject().get("error").asObject();
        assertEquals(RpcDispatcher.STALE_READ, error.get("code").asLong());
        assertEquals("STALE_READ", error.get("kind").asString());
        assertEquals("10.0.0.1:8000", error.get("leader_address").asString());

        String invalid = rpc.dispatch("{\"id\":5,\"method\":\"query\",\"consistency\":\"forte\","
            + "\"params\":[\"SELECT * FROM t\"]}", session);
        assertTrue(invalid.contains("-32602"), invalid);
    }
}
