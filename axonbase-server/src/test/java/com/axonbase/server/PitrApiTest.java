package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.journal.PitrJournal;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Restauração a um instante e transferência do journal pela API administrativa")
class PitrApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    private static final class Node implements AutoCloseable {
        final RocksDbBackend backend;
        final PitrJournal journal;
        final Datastore ds;
        final AxonServer server;
        final int port;
        final String root;
        final String operator;

        Node(Path dir, String encryptKey, boolean pitr) throws Exception {
            backend = new RocksDbBackend(dir.toString(), RocksDbBackend.SyncMode.OFF);
            journal = pitr ? PitrJournal.attach(backend, PitrSettings.defaults().directoryFor(backend),
                JournalOptions.defaults()) : null;
            ds = new Datastore(backend);
            ds.createDatabase("app", "main");
            ds.authCatalog().defineUser("oper", com.axonbase.core.security.AuthCatalog.Scope.ROOT, null, null, "pw",
                List.of("operator"), List.of());
            int free;
            try (ServerSocket s = new ServerSocket(0)) {
                free = s.getLocalPort();
            }
            ServerConfig cfg = ServerConfig.load(Path.of("nao-existe.conf"), Map.of(
                "AXON_PORT", String.valueOf(free), "AXON_ROOT_USER", "root", "AXON_ROOT_PASSWORD", "pw",
                "AXON_S3_ENCRYPT_KEY", encryptKey));
            server = AxonServer.start(ds, "secret-testing", cfg);
            server.pitr(journal);
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

        HttpResponse<byte[]> raw(String method, String path, String token, byte[] body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/v1/backups" + path))
                .header("Authorization", "Bearer " + token);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        HttpResponse<String> call(String method, String path) throws Exception {
            return call(method, path, root);
        }

        HttpResponse<String> call(String method, String path, String token) throws Exception {
            return HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/v1/backups" + path))
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }

        String metrics() throws Exception {
            return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        }

        private static Session session() {
            Session s = Session.create();
            s.namespace("app");
            s.database("main");
            return s;
        }

        void sql(String text) {
            ds.execute(text, session(), null);
        }

        List<String> names() {
            AxonValue v = ds.execute("SELECT nome FROM pessoa;", session(), null);
            List<AxonValue> rows = v.asArray();
            if (!rows.isEmpty() && rows.get(0).isArray()) {
                rows = rows.get(0).asArray();
            }
            List<String> out = new ArrayList<>();
            rows.forEach(r -> out.add(r.asObject().get("nome").asString()));
            out.sort(null);
            return out;
        }

        int backup() throws Exception {
            HttpResponse<String> r = call("POST", "/physical");
            assertEquals(200, r.statusCode(), r.body());
            return (int) body(r).asObject().get("id").asLong();
        }

        @Override
        public void close() throws Exception {
            server.stop();
            if (journal != null) {
                journal.close();
            }
            backend.close();
        }
    }

    private static AxonValue body(HttpResponse<String> r) {
        return AxonJson.parseDocument(r.body());
    }

    /** Um instante entre dois commits: nada é gravado no milissegundo devolvido nem nos vizinhos. */
    private static String instantBetweenCommits() throws InterruptedException {
        Thread.sleep(20);
        Instant now = Instant.now();
        Thread.sleep(20);
        return URLEncoder.encode(now.toString(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("restore?at= devolve o estado do instante pedido, em processo, e o servidor segue atendendo")
    void restoreToAnInstant() throws Exception {
        try (Node n = new Node(tmp.resolve("db"), "", true)) {
            n.sql("DEFINE TABLE pessoa SCHEMALESS;");
            n.sql("CREATE pessoa:1 CONTENT {nome: 'ana'};");
            int id = n.backup();
            n.sql("CREATE pessoa:2 CONTENT {nome: 'bia'};");
            String first = instantBetweenCommits();
            n.sql("CREATE pessoa:3 CONTENT {nome: 'caio'};");
            n.sql("DELETE pessoa:1;");
            String second = instantBetweenCommits();
            n.sql("DELETE pessoa;");

            HttpResponse<String> r = n.call("POST", "/physical/" + id + "/restore?confirm=true&at=" + second);

            assertEquals(200, r.statusCode(), r.body());
            assertTrue(body(r).asObject().get("restored").asBool());
            assertTrue(body(r).asObject().get("appliedBatches").asLong() > 0);
            assertEquals(List.of("bia", "caio"), n.names());

            assertEquals(200, n.call("POST", "/physical/" + id + "/restore?confirm=true&at=" + first).statusCode());
            assertEquals(List.of("ana", "bia"), n.names());

            n.sql("CREATE pessoa:9 CONTENT {nome: 'zeca'};");
            assertEquals(List.of("ana", "bia", "zeca"), n.names());
        }
    }

    @Test
    @DisplayName("instantes inválidos, inalcançáveis ou sem confirmação não tocam na base")
    void refusalsLeaveTheDatabaseAlone() throws Exception {
        try (Node n = new Node(tmp.resolve("db"), "", true)) {
            n.sql("DEFINE TABLE pessoa SCHEMALESS;");
            n.sql("CREATE pessoa:1 CONTENT {nome: 'ana'};");
            String beforeBackup = instantBetweenCommits();
            n.sql("CREATE pessoa:2 CONTENT {nome: 'bia'};");
            int id = n.backup();
            String path = "/physical/" + id + "/restore?confirm=true&at=";

            assertEquals(400, n.call("POST", "/physical/" + id + "/restore?at=" + beforeBackup).statusCode());
            assertEquals(400, n.call("POST", path + "ontem").statusCode());
            assertEquals(403, n.call("POST", path + beforeBackup, n.operator).statusCode());
            assertEquals(404, n.call("POST", "/physical/999/restore?confirm=true&at=" + beforeBackup).statusCode());

            HttpResponse<String> tooNew = n.call("POST", path + beforeBackup);
            assertEquals(409, tooNew.statusCode(), tooNew.body());
            assertEquals("PITR_BASE_TOO_NEW", error(tooNew, "code"));

            HttpResponse<String> future = n.call("POST", path + "2999-01-01T00:00:00Z");
            assertEquals(409, future.statusCode(), future.body());
            assertEquals("PITR_UNREACHABLE", error(future, "code"));
            assertTrue(future.body().contains("latest"), future.body());

            assertEquals(List.of("ana", "bia"), n.names());
        }
    }

    private static String error(HttpResponse<String> r, String field) {
        var root = body(r).asObject();
        var err = root.containsKey("error") ? root.get("error").asObject() : root;
        return err.get(field).asString();
    }

    @Test
    @DisplayName("sem o journal ligado, restore?at= responde 409 e o restore físico comum segue valendo")
    void withoutTheJournalPitrIsRefused() throws Exception {
        try (Node n = new Node(tmp.resolve("db"), "", false)) {
            n.sql("DEFINE TABLE pessoa SCHEMALESS;");
            int id = n.backup();

            HttpResponse<String> r = n.call("POST",
                "/physical/" + id + "/restore?confirm=true&at=2026-01-01T00:00:00Z");

            assertEquals(409, r.statusCode(), r.body());
            assertEquals("PITR_DISABLED", error(r, "code"));
            assertEquals(409, n.call("POST", "/journal/seal").statusCode());
            assertFalse(body(n.call("GET", "/journal")).asObject().get("enabled").asBool());
            assertEquals(200, n.call("POST", "/physical/" + id + "/restore?confirm=true").statusCode());
        }
    }

    @Test
    @DisplayName("status, selagem, janelas por backup e métricas")
    void statusSealWindowsAndMetrics() throws Exception {
        try (Node n = new Node(tmp.resolve("db"), "", true)) {
            n.sql("DEFINE TABLE pessoa SCHEMALESS;");
            int id = n.backup();
            n.sql("CREATE pessoa:1 CONTENT {nome: 'ana'};");

            HttpResponse<String> sealed = n.call("POST", "/journal/seal", n.operator);
            assertEquals(200, sealed.statusCode(), sealed.body());
            assertTrue(body(sealed).asObject().get("batches").asLong() >= 1);

            AxonValue status = body(n.call("GET", "/journal", n.operator));
            assertTrue(status.asObject().get("enabled").asBool());
            assertEquals(0L, status.asObject().get("droppedBatches").asLong());
            assertFalse(status.asObject().get("segments").asArray().isEmpty());
            AxonValue segment = status.asObject().get("segments").asArray().get(0);
            assertTrue(segment.asObject().get("name").asString().endsWith(".axj"));
            assertTrue(segment.asObject().containsKey("completeAt"));

            AxonValue windows = body(n.call("GET", "/journal/windows", n.operator));
            AxonValue window = windows.asObject().get("windows").asArray().get(0);
            assertEquals(id, window.asObject().get("backupId").asLong());
            Instant from = Instant.parse(window.asObject().get("from").asString());
            Instant to = Instant.parse(window.asObject().get("to").asString());
            assertFalse(to.isBefore(from));

            AxonValue backups = body(n.call("GET", "/physical", n.operator));
            assertTrue(backups.asObject().get("backups").asArray().get(0).asObject().containsKey("stampedAt"));

            String metrics = n.metrics();
            assertTrue(metrics.contains("axe_pitr_enabled 1"), "sem a métrica de PITR");
            assertTrue(metrics.contains("axe_pitr_dropped_batches_total 0"));
            assertTrue(metrics.contains("axe_hlc_rejected_total 0"));
            assertEquals(401, n.call("GET", "/journal", "invalido").statusCode());
        }
    }

    /** Perda total do nó: o backup físico e os segmentos vão para outro motor e restauram a um instante. */
    private void transferAndRestore(String key) throws Exception {
        try (Node origin = new Node(tmp.resolve("origin" + key.length()), key, true);
             Node target = new Node(tmp.resolve("target" + key.length()), key, true)) {
            origin.sql("DEFINE TABLE pessoa SCHEMALESS;");
            origin.sql("CREATE pessoa:1 CONTENT {nome: 'ana'};");
            int id = origin.backup();
            origin.sql("CREATE pessoa:2 CONTENT {nome: 'bia'};");
            String instant = instantBetweenCommits();
            origin.sql("CREATE pessoa:3 CONTENT {nome: 'caio'};");
            assertEquals(200, origin.call("POST", "/journal/seal").statusCode());

            for (AxonValue file : body(origin.call("GET", "/physical/" + id + "/files")).asObject().get("files")
                    .asArray()) {
                String path = file.asObject().get("path").asString();
                byte[] bytes = origin.raw("GET", "/physical/files?path=" + path, origin.root, null).body();
                assertEquals(200, target.raw("PUT", "/physical/files?source=dr&path=" + path, target.root, bytes)
                    .statusCode());
            }
            for (AxonValue segment : body(origin.call("GET", "/journal")).asObject().get("segments").asArray()) {
                String name = segment.asObject().get("name").asString();
                HttpResponse<byte[]> got = origin.raw("GET", "/journal/segments/" + name, origin.operator, null);
                assertEquals(200, got.statusCode());
                long plain = Long.parseLong(got.headers().firstValue("X-Axon-Size").orElseThrow());
                assertEquals(segment.asObject().get("sizeBytes").asLong(), plain);
                if (key.isEmpty()) {
                    assertEquals(plain, got.body().length);
                } else {
                    assertNotEquals(plain, got.body().length, "o corpo sai cifrado");
                }
                assertEquals(403, target.raw("PUT", "/journal/segments/" + name + "?source=dr", target.operator,
                    got.body()).statusCode(), "gravar exige admin");
                HttpResponse<byte[]> put = target.raw("PUT", "/journal/segments/" + name + "?source=dr",
                    target.root, got.body());
                assertEquals(200, put.statusCode(), new String(put.body(), StandardCharsets.UTF_8));
            }
            assertFalse(body(target.call("GET", "/journal?source=dr")).asObject().get("segments").asArray().isEmpty());

            HttpResponse<String> r = target.call("POST",
                "/physical/" + id + "/restore?confirm=true&source=dr&at=" + instant);

            assertEquals(200, r.statusCode(), r.body());
            assertEquals(List.of("ana", "bia"), target.names());
            assertEquals(200, target.call("DELETE", "/journal/imports/dr").statusCode());
            assertEquals(404, target.call("GET", "/journal?source=dr").statusCode());
        }
    }

    @Test
    @DisplayName("backup e journal levados a outro motor restauram o instante pedido")
    void disasterRecoveryOnAnotherNode() throws Exception {
        transferAndRestore("");
    }

    @Test
    @DisplayName("com a chave de cifra, os segmentos saem cifrados e voltam íntegros")
    void encryptedTransfer() throws Exception {
        transferAndRestore("chave-de-teste-do-journal");
    }

    @Test
    @DisplayName("nomes inválidos, origem ausente, corpo corrompido e segmento inexistente são recusados")
    void segmentRequestsAreValidated() throws Exception {
        try (Node n = new Node(tmp.resolve("db"), "", true)) {
            n.sql("DEFINE TABLE pessoa SCHEMALESS;");
            n.call("POST", "/journal/seal");
            String name = body(n.call("GET", "/journal")).asObject().get("segments").asArray().get(0)
                .asObject().get("name").asString();
            byte[] raw = n.raw("GET", "/journal/segments/" + name, n.root, null).body();

            assertEquals(400, n.call("GET", "/journal/segments/segredo.axj").statusCode());
            assertTrue(n.call("GET", "/journal/segments/..%2Fx").statusCode() >= 400, "caminho nunca sai da pasta");
            assertEquals(404, n.call("GET", "/journal/segments/seg-000000000000001-0000000001.axj").statusCode());
            assertEquals(400, n.raw("PUT", "/journal/segments/" + name, n.root, raw).statusCode(), "sem source");
            assertEquals(400, n.raw("PUT", "/journal/segments/" + name + "?source=..", n.root, raw).statusCode());
            byte[] corrupted = raw.clone();
            corrupted[raw.length / 2] ^= 0x11;
            assertEquals(400, n.raw("PUT", "/journal/segments/" + name + "?source=dr", n.root, corrupted)
                .statusCode());
            assertEquals(403, n.call("DELETE", "/journal/segments/" + name, n.operator).statusCode());
            assertEquals(200, n.call("DELETE", "/journal/segments/" + name).statusCode());
            assertEquals(404, n.call("DELETE", "/journal/segments/" + name).statusCode());
            assertEquals(404, n.call("DELETE", "/journal/imports/nao-existe").statusCode());
        }
    }

    @Test
    @DisplayName("as opções vêm do ambiente, com os limites do plano")
    void settingsFromEnvironment() {
        assertFalse(PitrSettings.from(Map.<String, String>of()::get).enabled());
        PitrSettings on = PitrSettings.from(Map.of("AXON_PITR", "on", "AXON_PITR_RETENTION_DAYS", "90",
            "AXON_PITR_SEGMENT_SECONDS", "600", "AXON_PITR_DIR", "/tmp/journal")::get);
        assertTrue(on.enabled());
        assertEquals(35, on.options().retention().toDays(), "retenção de até 35 dias");
        assertEquals(30, on.options().maxSegmentAge().toSeconds(), "segmentos fecham em no máximo 30 s");
        assertEquals(Path.of("/tmp/journal"), on.directory());
        PitrSettings defaults = PitrSettings.from(Map.of("AXON_PITR", "true", "AXON_PITR_RETENTION_DAYS", "x")::get);
        assertEquals(7, defaults.options().retention().toDays());
        assertEquals(25, defaults.options().maxSegmentAge().toSeconds());
    }
}
