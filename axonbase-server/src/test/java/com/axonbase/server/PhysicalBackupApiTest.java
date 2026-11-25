package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Backup físico pela API administrativa (criar, listar, verificar, restaurar em processo)")
class PhysicalBackupApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    private static final class Node implements AutoCloseable {
        final RocksDbBackend backend;
        final Datastore ds;
        final AxonServer server;
        final int port;
        final String root;
        final String operator;

        Node(Path dir) throws Exception {
            backend = new RocksDbBackend(dir.toString(), RocksDbBackend.SyncMode.OFF);
            ds = new Datastore(backend);
            ds.createDatabase("app", "main");
            ds.authCatalog().defineUser("oper", com.axonbase.core.security.AuthCatalog.Scope.ROOT, null, null, "pw",
                List.of("operator"), List.of());
            int free;
            try (ServerSocket s = new ServerSocket(0)) {
                free = s.getLocalPort();
            }
            ServerConfig cfg = ServerConfig.load(Path.of("nao-existe.conf"), Map.of(
                "AXON_PORT", String.valueOf(free), "AXON_ROOT_USER", "root", "AXON_ROOT_PASSWORD", "pw"));
            server = AxonServer.start(ds, "secret-testing", cfg);
            port = server.port();
            root = signin("root");
            operator = signin("oper");
        }

        String signin(String user) throws Exception {
            return AxonJson.parseDocument(HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"" + user + "\",\"pass\":\"pw\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).body()).asObject().get("token").asString();
        }

        HttpResponse<String> call(String method, String path, String token) throws Exception {
            return HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/v1/backups" + path))
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }

        long count() {
            Session s = Session.create();
            s.namespace("app");
            s.database("main");
            AxonValue v = ds.execute("SELECT count() AS n FROM pessoa GROUP ALL;", s, null);
            List<AxonValue> rows = v.asArray();
            // o resultado pode vir como lista de linhas ou embrulhado em lista de resultados por statement
            if (!rows.isEmpty() && rows.get(0).isArray()) {
                rows = rows.get(0).asArray();
            }
            return rows.isEmpty() ? 0 : rows.get(0).asObject().get("n").asLong();
        }

        void insert(int from, int to) {
            Session s = Session.create();
            s.namespace("app");
            s.database("main");
            for (int i = from; i < to; i++) {
                ds.execute("CREATE pessoa:" + i + " CONTENT {nome: 'p" + i + "'};", s, null);
            }
        }

        @Override
        public void close() throws Exception {
            server.stop();
            backend.close();
        }
    }

    private static AxonValue body(HttpResponse<String> r) {
        return AxonJson.parseDocument(r.body());
    }

    @Test
    @DisplayName("cria, lista, verifica e aplica retenção")
    void createListVerifyPurge() throws Exception {
        try (Node n = new Node(tmp.resolve("db"))) {
            n.ds.execute("DEFINE TABLE pessoa SCHEMALESS;", sessionOf(n), null);
            n.insert(0, 50);
            assertEquals(401, n.call("POST", "/physical", "invalido").statusCode());
            HttpResponse<String> created = n.call("POST", "/physical", n.operator);
            assertEquals(200, created.statusCode(), created.body());
            int id = (int) body(created).asObject().get("id").asLong();
            assertTrue(body(created).asObject().get("sizeBytes").asLong() > 0);
            assertTrue(body(created).asObject().get("version").asLong() > 0);

            n.insert(50, 60);
            n.call("POST", "/physical", n.operator);
            n.call("POST", "/physical", n.operator);

            AxonValue list = body(n.call("GET", "/physical", n.operator));
            assertEquals(3, list.asObject().get("backups").asArray().size());

            HttpResponse<String> verify = n.call("POST", "/physical/" + id + "/verify", n.operator);
            assertEquals(200, verify.statusCode(), verify.body());
            assertTrue(body(verify).asObject().get("ok").asBool());
            assertEquals(404, n.call("POST", "/physical/9999/verify", n.operator).statusCode());

            assertEquals(403, n.call("DELETE", "/physical?keep=1", n.operator).statusCode(), "retenção exige admin");
            assertEquals(200, n.call("DELETE", "/physical?keep=1", n.root).statusCode());
            assertEquals(1, body(n.call("GET", "/physical", n.operator)).asObject().get("backups").asArray().size());
        }
    }

    @Test
    @DisplayName("restore em processo devolve a base ao estado do backup e o servidor segue atendendo")
    void restoreInProcess() throws Exception {
        try (Node n = new Node(tmp.resolve("db"))) {
            n.ds.execute("DEFINE TABLE pessoa SCHEMALESS;", sessionOf(n), null);
            n.insert(0, 100);
            int id = (int) body(n.call("POST", "/physical", n.operator)).asObject().get("id").asLong();
            n.insert(100, 250);
            assertEquals(250, n.count());

            assertEquals(400, n.call("POST", "/physical/" + id + "/restore", n.root).statusCode(), "sem confirm");
            assertEquals(403, n.call("POST", "/physical/" + id + "/restore?confirm=true", n.operator).statusCode());
            assertEquals(404, n.call("POST", "/physical/9999/restore?confirm=true", n.root).statusCode());
            assertEquals(250, n.count(), "as recusas não tocam na base");

            HttpResponse<String> restored = n.call("POST", "/physical/" + id + "/restore?confirm=true", n.root);
            assertEquals(200, restored.statusCode(), restored.body());
            assertTrue(body(restored).asObject().get("restored").asBool());
            assertEquals(100, n.count(), "voltou ao estado do backup");

            n.insert(1000, 1010); // continua gravando
            assertEquals(110, n.count());
            String again = n.signin("root"); // o catálogo de usuários também voltou e o root existe
            assertEquals(200, n.call("GET", "/physical", again).statusCode());
        }
    }

    @Test
    @DisplayName("armazenamento em memória não tem backup físico")
    void memoryBackendIsRefused() throws Exception {
        Datastore ds = Datastore.memory();
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        ServerConfig cfg = ServerConfig.load(Path.of("nao-existe.conf"), Map.of(
            "AXON_PORT", String.valueOf(free), "AXON_ROOT_USER", "root", "AXON_ROOT_PASSWORD", "pw"));
        AxonServer server = AxonServer.start(ds, "secret-testing", cfg);
        try {
            String tok = AxonJson.parseDocument(HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/signin"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).body()).asObject().get("token").asString();
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/admin/v1/backups/physical"))
                .header("Authorization", "Bearer " + tok).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, r.statusCode(), r.body());
        } finally {
            server.stop();
        }
    }

    private static Session sessionOf(Node n) {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }
}
