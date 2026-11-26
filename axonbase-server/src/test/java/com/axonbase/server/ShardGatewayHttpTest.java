package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.RaftOptions;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.shard.ShardRequest;
import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.shard.backup.ShardBackupNode;
import com.axonbase.core.shard.backup.ShardJournalHooks;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
                        + ", quando = d\"2026-11-26T10:00:00Z\"");
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
    void backupDoClusterPeloGatewayERestauracaoPelaLinhaDeComando(@TempDir Path dir) throws Exception {
        // um shard que é um cluster Raft de um nó, com disco e journal
        int raftPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            raftPort = socket.getLocalPort();
        }
        RocksDbBackend backend = new RocksDbBackend(dir.resolve("db").toString());
        ShardJournalHooks hooks = ShardJournalHooks.open(backend.clock(), dir.resolve("journal"),
            JournalOptions.defaults());
        ClusterRuntime runtime = new ClusterRuntime(new ClusterConfig("n1", "s0",
            new InetSocketAddress("127.0.0.1", raftPort), List.of()), dir.resolve("raft"), backend,
            RaftOptions.defaults());
        runtime.hooks(hooks);
        Datastore datastore = new Datastore(backend);
        runtime.appliedBatchListener(datastore.appliedBatchListener());
        datastore.commitCoordinator(runtime, "n1");
        AxonServer server = AxonServer.startRandomPort(datastore, "segredo-de-teste");
        server.clusterStatus(runtime);
        server.shardBackup(new ShardBackupNode(runtime, backend, hooks, dir.resolve("backups"),
            Duration.ofSeconds(30)));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (runtime.role() != ClusterRole.LEADER || !runtime.status().ready()) {
            assertTrue(System.nanoTime() < deadline, "o nó não virou líder");
            Thread.sleep(20);
        }
        try (GatewayServer gateway = GatewayMain.start(GatewayMain.Config.from(environment(List.of(server))))) {
            sql(gateway, "DEFINE TABLE pedido SHARD BY cliente");
            sql(gateway, "CREATE pedido SET cliente = 'ana', total = 1");

            Map<String, String> admin = Map.of("Authorization", "Bearer token-de-teste");
            HttpResponse<String> created = post(gateway.port(), "/admin/backup/create", "", admin);
            assertEquals(200, created.statusCode(), created.body());
            Path manifest = dir.resolve("manifesto.json");
            Files.writeString(manifest, created.body());
            assertEquals(200, post(gateway.port(), "/admin/backup/barrier", "", admin).statusCode());

            ShardRestoreMain.run(new String[] {manifest.toString(), dir.resolve("restaurado").toString()});

            try (RocksDbBackend restored = new RocksDbBackend(dir.resolve("restaurado/s0").toString())) {
                Session session = Session.create();
                session.namespace("n");
                session.database("d");
                assertEquals(1, new Datastore(restored).execute("SELECT * FROM pedido", session, null)
                    .asArray().size());
            }
        } finally {
            server.stop();
            runtime.close();
            hooks.close();
            backend.close();
        }
    }

    @Test
    void manutencaoDosShardsExigeAdministradorRoot() throws Exception {
        Datastore datastore = Datastore.memory();
        Session setup = Session.create();
        setup.namespace("n");
        setup.database("d");
        datastore.execute("DEFINE USER dba ON DATABASE PASSWORD \"pw\" ROLES admin", setup, null);
        AxonServer server = AxonServer.startRandomPort(datastore, "segredo-de-teste", "root", "pw", true);
        try {
            String database = new com.axonbase.server.auth.AuthService("segredo-de-teste",
                new com.axonbase.server.auth.UserStore(datastore.authCatalog()))
                .signin("dba", "pw", "n", "d", null);
            String root = AxonJson.parseDocument(post(server.port(), "/signin",
                "{\"user\":\"root\",\"pass\":\"pw\"}", Map.of()).body()).asObject().get("token").asString();
            ShardRequest plain = ShardRequest.of("n", "d", "SELECT * FROM t");
            ShardRequest definitions = ShardRequest.of("n", "d", ShardRequest.DEFINITIONS);

            assertEquals(200, execute(server, plain, database));
            assertEquals(403, execute(server, plain.asSystem(), database));
            assertEquals(403, execute(server, definitions, database));
            assertEquals(403, post(server.port(), "/shard/v1/backup/status", "{}",
                scope("Authorization", "Bearer " + database)).statusCode());
            assertEquals(200, execute(server, plain.asSystem(), root));
            assertEquals(200, execute(server, definitions, root));
        } finally {
            server.stop();
        }
    }

    private static int execute(AxonServer server, ShardRequest request, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/shard/v1/execute"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(com.axonbase.core.shard.ShardCodec.encode(request)));
        scope("Authorization", "Bearer " + token).forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
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
