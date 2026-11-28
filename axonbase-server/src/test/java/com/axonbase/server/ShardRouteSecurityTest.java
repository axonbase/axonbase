package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.shard.ShardCodec;
import com.axonbase.core.shard.ShardRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Shards: a rota interna só existe em nó de shard")
class ShardRouteSecurityTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static int post(AxonServer server, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Axon-Ns", "n").header("Axon-Db", "d")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void noDeShardExigeOSegredoDoGateway() throws Exception {
        AxonServer server = AxonServer.startRandomPort(Datastore.memory(), "segredo-de-teste");
        server.shardSecret("segredo-do-gateway");
        try {
            byte[] query = ShardCodec.encode(ShardRequest.of("n", "d", "SELECT * FROM t"));

            assertEquals(401, post(server, "/shard/v1/execute", query));
            assertEquals(401, post(server, "/shard/v1/execute", query, "Axon-Shard-Secret", "outro"));
            assertEquals(401, post(server, "/shard/v1/backup/status", new byte[] {'{', '}'}));
            assertEquals(200, post(server, "/shard/v1/execute", query, "Axon-Shard-Secret", "segredo-do-gateway"));
        } finally {
            server.stop();
        }
    }

    @Test
    void gatewaySemSegredoNaoSobe() {
        GatewayMain.Config config = GatewayMain.Config.from(
            java.util.Map.of("AXON_GATEWAY_META", "http://127.0.0.1:1", "AXON_GATEWAY_PORT", "0"));

        assertThrows(IllegalArgumentException.class, () -> GatewayMain.start(config));
    }

    @Test
    void noQueNaoEShardNaoTemARotaInterna() throws Exception {
        AxonServer server = AxonServer.startRandomPort(Datastore.memory(), "segredo-de-teste");
        try {
            byte[] query = ShardCodec.encode(ShardRequest.of("n", "d", "SELECT * FROM t"));

            assertEquals(404, post(server, "/shard/v1/execute", query));
            assertEquals(404, post(server, "/shard/v1/backup/status", new byte[] {'{', '}'}));
        } finally {
            server.stop();
        }
    }
}
