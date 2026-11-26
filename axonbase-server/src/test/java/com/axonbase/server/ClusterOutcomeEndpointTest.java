package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.CommitCoordinator;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.cluster.CommitOutcomeUnknownException;
import com.axonbase.core.cluster.QuorumUnavailableException;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Servidor: desfecho desconhecido é diferente de falha")
class ClusterOutcomeEndpointTest {

    private static Datastore failing(Supplier<RuntimeException> failure) {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        ds.commitCoordinator(new CommitCoordinator() {
            @Override
            public long confirm(String node, CommittedBatch batch) {
                throw failure.get();
            }
        }, "n1");
        return ds;
    }

    private static HttpResponse<String> post(AxonServer server, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Axon-Ns", "n").header("Axon-Db", "d")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("no /sql o desfecho desconhecido volta 504 com status UNKNOWN; a falta de quórum segue como erro")
    void sqlDistinguishesUnknownFromFailure() throws Exception {
        AxonServer unknown = AxonServer.startRandomPort(
            failing(() -> new CommitOutcomeUnknownException("g")), "secret-testing");
        AxonServer refused = AxonServer.startRandomPort(
            failing(() -> new QuorumUnavailableException("g")), "secret-testing");
        try {
            HttpResponse<String> response = post(unknown, "/sql", "CREATE t CONTENT {x: 1}");
            assertEquals(504, response.statusCode(), response.body());
            Map<String, AxonValue> body = AxonJson.parseDocument(response.body()).asObject();
            assertEquals("UNKNOWN", body.get("status").asString());
            assertEquals("OUTCOME_UNKNOWN", body.get("kind").asString());
            assertTrue(body.get("detail").asString().contains("desconhecido"), response.body());

            HttpResponse<String> failure = post(refused, "/sql", "CREATE t CONTENT {x: 1}");
            assertEquals(400, failure.statusCode(), failure.body());
            assertEquals("ERR", AxonJson.parseDocument(failure.body()).asObject().get("status").asString());
        } finally {
            unknown.stop();
            refused.stop();
        }
    }

    @Test
    @DisplayName("no RPC o desfecho desconhecido tem código próprio, na consulta e no commit explícito")
    void rpcHasItsOwnCode() {
        RpcDispatcher rpc = new RpcDispatcher(failing(() -> new CommitOutcomeUnknownException("g")));
        Session session = Session.create();
        session.namespace("n");
        session.database("d");

        expectUnknown(rpc.dispatch("{\"id\":1,\"method\":\"query\",\"params\":[\"CREATE t CONTENT {x: 1}\"]}",
            session));
        rpc.dispatch("{\"id\":2,\"method\":\"begin\"}", session);
        rpc.dispatch("{\"id\":3,\"method\":\"query\",\"params\":[\"CREATE t CONTENT {x: 2}\"]}", session);
        expectUnknown(rpc.dispatch("{\"id\":4,\"method\":\"commit\"}", session));
        expectUnknown(rpc.dispatch("{\"id\":5,\"method\":\"create\",\"params\":[\"t\",{\"x\":3}]}", session));
    }

    private static void expectUnknown(String response) {
        Map<String, AxonValue> error = AxonJson.parseDocument(response).asObject().get("error").asObject();
        assertEquals(RpcDispatcher.OUTCOME_UNKNOWN, error.get("code").asLong(), response);
        assertEquals("OUTCOME_UNKNOWN", error.get("kind").asString());
    }
}
