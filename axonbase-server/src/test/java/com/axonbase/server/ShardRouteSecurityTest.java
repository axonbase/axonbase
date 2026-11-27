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

@DisplayName("Shards: a rota interna só existe em nó de shard")
class ShardRouteSecurityTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static int post(AxonServer server, String path, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Axon-Ns", "n").header("Axon-Db", "d")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
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
