package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.shard.ShardCodec;
import com.axonbase.core.shard.ShardRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: a rota interna tem o registro de atividade e o prazo de consulta do /sql")
class ShardRouteActivityTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SECRET = "segredo-do-gateway";
    /** Várias varreduras sem índice na mesma requisição: dura bem mais do que o prazo ou o tempo de cancelar. */
    private static final String HEAVY = "SELECT * FROM t WHERE v = 5;".repeat(400);

    private static AxonServer shard(long queryTimeoutMillis) throws Exception {
        Datastore datastore = Datastore.memory();
        datastore.createDatabase("n", "d");
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        for (int base = 0; base < 20_000; base += 500) {
            StringBuilder insert = new StringBuilder("INSERT INTO t [");
            for (int i = base; i < base + 500; i++) {
                insert.append(i > base ? "," : "").append("{id: ").append(i).append(", v: ").append(i % 10).append('}');
            }
            datastore.execute(insert.append(']').toString(), session, null);
        }
        AxonServer server = AxonServer.start(datastore, "segredo-de-teste",
            ServerConfig.load(Path.of("nao-existe.conf"), Map.of("AXON_PORT", "0", "AXON_REQUIRE_AUTH", "false",
                "AXON_QUERY_TIMEOUT", String.valueOf(queryTimeoutMillis))));
        server.shardSecret(SECRET);
        return server;
    }

    private static HttpResponse<String> execute(AxonServer server, String sql) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/shard/v1/execute"))
            .header("Axon-Ns", "n").header("Axon-Db", "d").header("Axon-Shard-Secret", SECRET)
            .POST(HttpRequest.BodyPublishers.ofByteArray(ShardCodec.encode(ShardRequest.of("n", "d", sql)))).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void consultaDaRotaTemOPrazoDoSql() throws Exception {
        AxonServer server = shard(200);
        try {
            HttpResponse<String> response = execute(server, HEAVY);

            assertEquals(400, response.statusCode());
            assertEquals(1, server.activity().cancelledTotal());
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("escrita que estoura o prazo responde desfecho desconhecido, e não uma falha definitiva")
    void escritaQueEstouraOPrazoTemDesfechoDesconhecido() throws Exception {
        AxonServer server = shard(200);
        try {
            HttpResponse<String> response = execute(server, "UPDATE t SET x = 1 WHERE v = 5;".repeat(400));

            assertEquals(504, response.statusCode(), response.body());
            assertTrue(response.body().contains("OUTCOME_UNKNOWN"), response.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void consultaDaRotaApareceNaAtividadeEPodeSerCancelada() throws Exception {
        AxonServer server = shard(30_000);
        try {
            CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return execute(server, HEAVY);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            List<ActivityRegistry.Running> running = server.activity().running();
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (running.isEmpty() && !pending.isDone() && System.nanoTime() < limit) {
                Thread.sleep(10);
                running = server.activity().running();
            }

            assertEquals(1, running.size());
            assertEquals("shard", running.get(0).protocol());
            assertTrue(server.activity().cancel(running.get(0).id(), "teste"));
            assertEquals(400, pending.get(15, TimeUnit.SECONDS).statusCode());
            assertEquals(0, server.activity().runningCount());
        } finally {
            server.stop();
        }
    }
}
