package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.shard.ShardInfo;
import com.axonbase.core.shard.ShardMap;
import com.axonbase.core.shard.backup.ClusterManifest;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: rotas de administração do gateway e linha de comando")
class ShardAdminHttpTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String ADMIN = "Bearer token-de-teste";

    private static HttpResponse<String> call(int port, String method, String path, String body, String... headers)
        throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String url(AxonServer server) {
        return "http://127.0.0.1:" + server.port();
    }

    private static Map<String, String> environment(AxonServer first, AxonServer second, String adminToken) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AXON_GATEWAY_SHARDS", "s0=" + url(first) + ";s1=" + url(second));
        env.put("AXON_GATEWAY_PORT", "0");
        env.put("AXON_GATEWAY_ADMIN_TOKEN", adminToken);
        env.put("AXON_GATEWAY_MAP_TTL_MS", "0");
        env.put("AXON_GATEWAY_DEADLINE_MS", "500");
        return env;
    }

    @Test
    void rotasDeAdministracao() throws Exception {
        List<AxonServer> shards = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                shards.add(AxonServer.startRandomPort(Datastore.memory(), "segredo-de-teste"));
            }
            try (GatewayServer gateway = GatewayMain.start(
                GatewayMain.Config.from(environment(shards.get(0), shards.get(1), "token-de-teste")))) {
                int port = gateway.port();

                assertTrue(call(port, "GET", "/health", "").body().contains("\"shards\":2"));
                HttpResponse<String> added = call(port, "POST", "/admin/shard/add",
                    "{\"id\":\"s2\",\"endpoints\":[\"" + url(shards.get(2)) + "\"]}", "Authorization", ADMIN);
                assertEquals(200, added.statusCode(), added.body());
                HttpResponse<String> map = call(port, "GET", "/shard/v1/map", "", "Authorization", ADMIN);
                assertEquals(3, AxonJson.parseDocument(map.body()).asObject().get("shards").asArray().size());

                assertEquals(400, call(port, "POST", "/admin/shard/resume", "", "Authorization", ADMIN).statusCode());
                assertEquals(400, call(port, "POST", "/admin/shard/abort", "", "Authorization", ADMIN).statusCode());
                assertEquals(400, call(port, "POST", "/admin/shard/move", "{}", "Authorization", ADMIN).statusCode());
                assertEquals(404, call(port, "POST", "/admin/shard/outra", "", "Authorization", ADMIN).statusCode());
                assertEquals(405, call(port, "GET", "/admin/shard/move", "", "Authorization", ADMIN).statusCode());
                assertEquals(404, call(port, "POST", "/admin/backup/outra", "", "Authorization", ADMIN).statusCode());
                assertEquals(405, call(port, "GET", "/admin/backup/create", "", "Authorization", ADMIN).statusCode());
                assertEquals(401, call(port, "GET", "/shard/v1/map", "").statusCode());
                assertEquals(405, call(port, "GET", "/sql", "").statusCode());
                HttpResponse<String> capped = call(port, "POST", "/sql", "SELECT * FROM t", "Axon-Ns", "n",
                    "Axon-Db", "d", "Axon-Role-Cap", "viewer");
                assertEquals(400, capped.statusCode(), capped.body());

                // sem journal nos nós, o backup diz o que falta
                HttpResponse<String> backup =
                    call(port, "POST", "/admin/backup/barrier", "", "Authorization", ADMIN);
                assertEquals(400, backup.statusCode(), backup.body());
                assertTrue(backup.body().contains("AXON_PITR"), backup.body());

                HttpResponse<String> signin = call(port, "POST", "/signin", "{\"user\":\"u\",\"pass\":\"p\"}");
                assertEquals(200, signin.statusCode(), signin.body());
                assertTrue(signin.body().contains("token"));
                assertEquals(400, call(port, "POST", "/sql", "SELECT * FROM t", "Axon-Ns", "n", "Axon-Db", "d",
                    "Axon-HLC", "ontem").statusCode());
                HttpResponse<String> ordered = call(port, "POST", "/sql", "CREATE t:1", "Axon-Ns", "n", "Axon-Db",
                    "d", "Axon-HLC", System.currentTimeMillis() + ":0", "X-Correlation-Id", "nenhuma");
                assertEquals(200, ordered.statusCode(), ordered.body());
            }
        } finally {
            for (AxonServer shard : shards) {
                shard.stop();
            }
        }
    }

    @Test
    void semTokenDeAdministracaoAsRotasFicamDesligadas() throws Exception {
        AxonServer first = AxonServer.startRandomPort(Datastore.memory(), "segredo-de-teste");
        AxonServer second = AxonServer.startRandomPort(Datastore.memory(), "segredo-de-teste");
        try (GatewayServer gateway = GatewayMain.start(GatewayMain.Config.from(environment(first, second, "")))) {
            assertEquals(403, call(gateway.port(), "POST", "/admin/shard/move", "{}", "Authorization", "Bearer ")
                .statusCode());
            // o shard base fora do ar: o login avisa
            first.stop();
            assertEquals(503, call(gateway.port(), "POST", "/signin", "{}").statusCode());
        } finally {
            second.stop();
        }
    }

    @Test
    void configuracaoInvalidaDoGatewayERecusada() {
        for (Map<String, String> env : List.of(Map.<String, String>of(),
            Map.of("AXON_GATEWAY_SHARDS", "semigual"),
            Map.of("AXON_GATEWAY_META", "http://a", "AXON_GATEWAY_EXPLICIT_IDS", "talvez"),
            Map.of("AXON_GATEWAY_META", "http://a", "AXON_GATEWAY_PORT", "oito"),
            Map.of("AXON_GATEWAY_META", "http://a", "AXON_GATEWAY_MAP_TTL_MS", "-1"))) {
            assertThrows(IllegalArgumentException.class, () -> GatewayMain.Config.from(env), env.toString());
        }
        GatewayMain.Config config = GatewayMain.Config.from(Map.of("AXON_GATEWAY_SHARDS",
            "s0=http://a,http://b;s1=http://c", "AXON_GATEWAY_EXPLICIT_IDS", "check"));
        assertEquals(List.of("http://a", "http://b"), config.meta());
        assertTrue(config.checkExplicitIds());
        assertEquals(34_000, config.propagation().toMillis());
    }

    @Test
    void restauracaoPelaLinhaDeComandoConfereOsArgumentos(@TempDir Path dir) {
        Path manifest = dir.resolve("m.json");
        new ClusterManifest("id", HlcTimestamp.NONE, 0,
            ShardMap.initial(List.of(new ShardInfo("s0", List.of()))), List.of()).write(manifest);
        String file = manifest.toString();
        String destination = dir.resolve("destino").toString();

        for (String[] invalid : List.of(new String[] {file}, new String[] {file, destination, "--at"},
            new String[] {file, destination, "--outra", "x"}, new String[] {file, destination, "--at", "ontem"},
            new String[] {file, destination, "--source", "s9=a,b"})) {
            assertThrows(IllegalArgumentException.class, () -> ShardRestoreMain.run(invalid));
        }
        ShardRestoreMain.run(new String[] {file, destination, "--at", "2026-11-26T11:32:10-03:00"});
        assertEquals(java.time.Instant.parse("2026-11-26T14:32:10Z").toEpochMilli(),
            ShardRestoreMain.instant("2026-11-26T11:32:10-03:00").physical());
    }
}
