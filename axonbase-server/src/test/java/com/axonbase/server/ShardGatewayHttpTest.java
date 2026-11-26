package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: gateway e nós de shard por HTTP")
class ShardGatewayHttpTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpResponse<String> post(int port, String path, String body, Map<String, String> headers)
        throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> scope(String... extra) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Axon-Ns", "n");
        headers.put("Axon-Db", "d");
        for (int i = 0; i < extra.length; i += 2) {
            headers.put(extra[i], extra[i + 1]);
        }
        return headers;
    }

    private static Map<String, String> environment(List<AxonServer> shards) {
        Map<String, String> env = new LinkedHashMap<>();
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < shards.size(); i++) {
            spec.append(i == 0 ? "" : ";").append('s').append(i).append("=http://127.0.0.1:")
                .append(shards.get(i).port());
        }
        env.put("AXON_GATEWAY_SHARDS", spec.toString());
        env.put("AXON_GATEWAY_PORT", "0");
        env.put("AXON_GATEWAY_ADMIN_TOKEN", "token-de-teste");
        env.put("AXON_GATEWAY_MAP_TTL_MS", "0");
        env.put("AXON_GATEWAY_DEADLINE_MS", "2000");
        return env;
    }

    private static AxonValue sql(GatewayServer gateway, String script) throws Exception {
        HttpResponse<String> response = post(gateway.port(), "/sql", script, scope());
        assertEquals(200, response.statusCode(), response.body());
        return AxonJson.parseDocument(response.body());
    }

    @Test
    void consultaEscritaEMovimentacaoPeloGatewayHttp() throws Exception {
        List<AxonServer> shards = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                shards.add(AxonServer.startRandomPort(Datastore.memory(), "segredo-de-teste"));
            }
            try (GatewayServer gateway = GatewayMain.start(GatewayMain.Config.from(environment(shards)))) {
                sql(gateway, "DEFINE TABLE pedido SHARD BY cliente");
                for (int i = 0; i < 40; i++) {
                    sql(gateway, "CREATE pedido SET cliente = 'c" + i + "', total = " + i
                        + ", quando = d'2026-11-26T10:00:00Z'");
                }

                AxonValue sums = sql(gateway, "SELECT count(), sum(total) FROM pedido GROUP ALL");
                assertEquals(AxonValue.num(40), sums.asArray().get(0).asObject().get("count"));
                assertEquals(AxonValue.num(780), sums.asArray().get(0).asObject().get("sum"));
                AxonValue top = sql(gateway, "SELECT VALUE total FROM pedido ORDER BY total DESC LIMIT 3");
                assertEquals(List.of(AxonValue.num(39), AxonValue.num(38), AxonValue.num(37)), top.asArray());

                HttpResponse<String> moved = post(gateway.port(), "/admin/shard/move",
                    "{\"slots\":\"0-699\",\"target\":\"s2\"}", Map.of("Authorization", "Bearer token-de-teste"));
                // a movimentação espera o tempo de uma requisição antes de cada passo destrutivo
                assertEquals(200, moved.statusCode(), moved.body());
                assertEquals(AxonValue.num(40), sql(gateway, "SELECT count() FROM pedido GROUP ALL").asArray().get(0)
                    .asObject().get("count"));

                HttpResponse<String> refused = post(gateway.port(), "/sql", "UPDATE pedido SET total = 1", scope());
                assertEquals(400, refused.statusCode());
                assertTrue(refused.body().contains("REFUSED"), refused.body());
                assertEquals(401, post(gateway.port(), "/admin/shard/move", "{}",
                    Map.of("Authorization", "Bearer errado")).statusCode());
                assertTrue(post(gateway.port(), "/sql", "SELECT count() FROM pedido GROUP ALL", scope())
                    .headers().firstValue("Axon-HLC").isPresent());
            }
        } finally {
            for (AxonServer shard : shards) {
                shard.stop();
            }
        }
    }

    @Test
    void rotaInternaDoShardExigeCredencialEOMesmoBancoAutorizado() throws Exception {
        AxonServer server = AxonServer.startRandomPort(Datastore.memory(), "segredo-de-teste", "root", "pw", true);
        try {
            assertEquals(401, post(server.port(), "/shard/v1/execute", "x", scope()).statusCode());
            String token = AxonJson.parseDocument(post(server.port(), "/signin",
                "{\"user\":\"root\",\"pass\":\"pw\"}", Map.of()).body()).asObject().get("token").asString();
            Map<String, String> authorized = scope("Authorization", "Bearer " + token);

            assertEquals(400, post(server.port(), "/shard/v1/execute", "x", authorized).statusCode());
            byte[] other = com.axonbase.core.shard.ShardCodec.encode(
                com.axonbase.core.shard.ShardRequest.of("outro", "d", "SELECT * FROM t"));
            HttpRequest.Builder request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/shard/v1/execute"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(other));
            authorized.forEach(request::header);

            assertEquals(403, HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop();
        }
    }
}
