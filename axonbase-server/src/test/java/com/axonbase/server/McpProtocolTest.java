package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O aperto de mão do Model Context Protocol, como um cliente padrão o faz, sem abrir acesso anônimo. */
class McpProtocolTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
        + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"qa\",\"version\":\"1\"}}}";

    private AxonServer server;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("loja", "ops");
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        token = new SecurityHttp(server.port()).rootToken("pw");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private HttpRequest.Builder request(String path, boolean authenticated) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Content-Type", "application/json")
            .header("Axon-Ns", "loja")
            .header("Axon-Db", "ops");
        if (authenticated) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private HttpResponse<String> post(String path, String body, boolean authenticated) throws Exception {
        return HTTP.send(request(path, authenticated).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, AxonValue> object(String json) {
        return AxonJson.parseDocument(json).asObject();
    }

    @Test
    void initializeAnunciaVersaoCapacidadesEServidor() throws Exception {
        HttpResponse<String> response = post("/mcp", INITIALIZE, true);

        assertEquals(200, response.statusCode(), response.body());
        Map<String, AxonValue> message = object(response.body());
        assertEquals("2.0", message.get("jsonrpc").asString());
        Map<String, AxonValue> result = message.get("result").asObject();
        assertEquals("2024-11-05", result.get("protocolVersion").asString());
        assertTrue(result.get("capabilities").asObject().containsKey("tools"));
        assertEquals("axonbase", result.get("serverInfo").asObject().get("name").asString());
    }

    @Test
    void notificacaoEPingSeguemOProtocolo() throws Exception {
        HttpResponse<String> notification = post("/mcp",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", true);
        HttpResponse<String> ping = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}", true);

        assertEquals(202, notification.statusCode(), notification.body());
        assertEquals("", notification.body());
        assertTrue(object(ping.body()).get("result").asObject().isEmpty(), ping.body());
    }

    @Test
    void ferramentaDevolveConteudoEMetodoDesconhecidoEhErroJsonRpc() throws Exception {
        HttpResponse<String> call = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"axon_info\",\"arguments\":{}}}", true);
        HttpResponse<String> unknown = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nada\"}", true);

        Map<String, AxonValue> result = object(call.body()).get("result").asObject();
        assertEquals("text", result.get("content").asArray().get(0).asObject().get("type").asString());
        assertFalse(result.get("isError").asBool(), call.body());
        assertEquals(-32601, object(unknown.body()).get("error").asObject().get("code").asLong());
    }

    @Test
    void apertoDeMaoSemTokenEhRecusado() throws Exception {
        assertEquals(401, post("/mcp", INITIALIZE, false).statusCode());
        assertEquals(401, post("/mcp/message", INITIALIZE, false).statusCode());
    }

    @Test
    void noTransporteSseARespostaChegaPeloCanalAberto() throws Exception {
        HttpResponse<InputStream> stream = HTTP.send(request("/mcp/sse", true).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            String endpoint = CompletableFuture.supplyAsync(() -> dataAfter(reader, "endpoint"))
                .get(10, TimeUnit.SECONDS);
            HttpResponse<String> accepted = post(endpoint, INITIALIZE, true);

            assertEquals(202, accepted.statusCode(), accepted.body());
            String message = CompletableFuture.supplyAsync(() -> dataAfter(reader, "message"))
                .get(10, TimeUnit.SECONDS);
            assertEquals("axonbase", object(message).get("result").asObject()
                .get("serverInfo").asObject().get("name").asString());
        }
    }

    private static String dataAfter(BufferedReader reader, String event) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("event: " + event)) {
                    return reader.readLine().substring("data: ".length());
                }
            }
            throw new AssertionError("canal encerrado sem o evento " + event);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
