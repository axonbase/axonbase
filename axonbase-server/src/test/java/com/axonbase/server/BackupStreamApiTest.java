package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Export e restore de backup em streaming pela API (E0b). */
class BackupStreamApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final class Node implements AutoCloseable {
        final Datastore ds = Datastore.memory();
        final AxonServer server;
        final int port;
        final String root;
        final String operator;

        Node(String encryptKey) throws Exception {
            int free;
            try (ServerSocket s = new ServerSocket(0)) {
                free = s.getLocalPort();
            }
            ds.authCatalog().defineUser("oper", com.axonbase.core.security.AuthCatalog.Scope.ROOT, null, null, "pw",
                List.of("operator"), List.of());
            ServerConfig cfg = ServerConfig.load(Path.of("nao-existe.conf"), Map.of(
                "AXON_PORT", String.valueOf(free), "AXON_S3_ENCRYPT_KEY", encryptKey,
                "AXON_ROOT_USER", "root", "AXON_ROOT_PASSWORD", "pw"));
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

        HttpResponse<byte[]> export(String query, String token) throws Exception {
            return HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/v1/backups/export" + query))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        HttpResponse<String> restore(String query, String token, byte[] body) throws Exception {
            return HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/admin/v1/backups/restore" + query))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() throws Exception {
            server.stop();
        }
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    @Test
    void exportCompletoERestoreEmNoVazio() throws Exception {
        try (Node a = new Node(""); Node b = new Node("")) {
            a.ds.createDatabase("app", "main");
            for (int i = 0; i < 2000; i++) {
                a.ds.metaPut("k" + i, ("v" + i).getBytes(StandardCharsets.UTF_8));
            }
            a.ds.execute("DEFINE TABLE pessoa SCHEMALESS; CREATE pessoa:1 CONTENT {nome:'Ana'};", session(), null);

            assertEquals(401, a.export("", "invalido").statusCode());
            HttpResponse<byte[]> r = a.export("?type=full", a.operator);
            assertEquals(200, r.statusCode());
            assertEquals("full", r.headers().firstValue("X-Axon-Backup-Type").orElse(null));
            long version = Long.parseLong(r.headers().firstValue("X-Axon-Backup-Version").orElseThrow());
            assertTrue(version > 0);
            assertTrue(new String(r.body(), StandardCharsets.UTF_8).startsWith("--axonbase-backup-v1--\n"));

            // restore exige papel admin e confirm=true
            assertEquals(403, b.restore("?confirm=true", b.operator, r.body()).statusCode());
            assertEquals(400, b.restore("", b.root, r.body()).statusCode());
            HttpResponse<String> ok = b.restore("?confirm=true", b.root, r.body());
            assertEquals(200, ok.statusCode(), ok.body());
            // a resposta informa quantas chaves foram aplicadas (o painel mostra esse número)
            java.util.regex.Matcher count = java.util.regex.Pattern.compile("\"restoredKeys\":(\\d+)").matcher(ok.body());
            assertTrue(count.find(), ok.body());
            assertTrue(Long.parseLong(count.group(1)) >= 2000, ok.body());
            assertEquals(2000, b.ds.metaKeys("k").size());
            assertTrue(b.ds.hasRecords());
            // nó com dados recusa sem force e aceita com force
            assertEquals(409, b.restore("?confirm=true", b.root, r.body()).statusCode());
            assertEquals(200, b.restore("?confirm=true&force=true", b.root, r.body()).statusCode());
        }
    }

    @Test
    void incrementalComSinceTrazOQueMudouEDelecoes() throws Exception {
        try (Node a = new Node("")) {
            for (int i = 0; i < 300; i++) {
                a.ds.metaPut("k" + i, new byte[] {(byte) i});
            }
            long mark = Long.parseLong(a.export("", a.root).headers().firstValue("X-Axon-Backup-Version").orElseThrow());
            a.ds.metaPut("k1", new byte[] {42});
            a.ds.metaDelete("k2");
            assertEquals(400, a.export("?type=incremental", a.root).statusCode());
            assertEquals(400, a.export("?type=xyz", a.root).statusCode());
            HttpResponse<byte[]> inc = a.export("?type=incremental&since=" + mark, a.root);
            assertEquals("incremental", inc.headers().firstValue("X-Axon-Backup-Type").orElse(null));
            assertTrue(Long.parseLong(inc.headers().firstValue("X-Axon-Backup-Version").orElseThrow()) > mark);
            String text = new String(inc.body(), StandardCharsets.UTF_8);
            assertTrue(text.contains("!meta|k1\t"));
            assertFalse(text.contains("!meta|k3\t"));
            assertTrue(text.contains("!del\t"));
        }
    }

    @Test
    void exportCriptografadoEmBlocosERestoreReconhece() throws Exception {
        try (Node a = new Node("segredo"); Node b = new Node("segredo"); Node errado = new Node("outra")) {
            for (int i = 0; i < 3000; i++) {
                a.ds.metaPut("k" + i, ("conteudo-" + i).repeat(5).getBytes(StandardCharsets.UTF_8));
            }
            HttpResponse<byte[]> r = a.export("", a.root);
            String head = new String(r.body(), 0, 40, StandardCharsets.US_ASCII);
            assertTrue(head.startsWith("--axonbase-backup-encrypted-v2--"), head);
            assertFalse(new String(r.body(), StandardCharsets.UTF_8).contains("conteudo-"));
            assertEquals(200, b.restore("?confirm=true", b.root, r.body()).statusCode());
            assertEquals(3000, b.ds.metaKeys("k").size());
            assertArrayEquals(("conteudo-7").repeat(5).getBytes(StandardCharsets.UTF_8), b.ds.metaGet("k7").get());
            // senha diferente: 400 sem apagar nada
            errado.ds.metaPut("intacto", new byte[] {1});
            HttpResponse<String> bad = errado.restore("?confirm=true&force=true", errado.root, r.body());
            assertEquals(400, bad.statusCode(), bad.body());
            assertNotNull(errado.ds.metaGet("intacto").orElse(null));
        }
    }

    @Test
    void restoreRecusaCorpoQueNaoEBackup() throws Exception {
        try (Node a = new Node("")) {
            a.ds.metaPut("x", new byte[] {1});
            HttpResponse<String> r = a.restore("?confirm=true&force=true", a.root, "qualquer coisa\n".getBytes());
            assertEquals(400, r.statusCode(), r.body());
            assertTrue(a.ds.metaGet("x").isPresent());
        }
    }

    @Test
    void restoreEmClusterDa409MesmoComForce() throws Exception {
        try (Node a = new Node("")) {
            a.ds.metaPut("k1", "v".getBytes(StandardCharsets.UTF_8));
            byte[] dump = a.export("?type=full", a.operator).body();
            a.server.clusterStatus(() -> new com.axonbase.core.cluster.ClusterStatusProvider.Status("n1", "g", "n1",
                "127.0.0.1:1", com.axonbase.core.cluster.ClusterRole.LEADER, 1, 1, 1, 1, 1));
            HttpResponse<String> r = a.restore("?confirm=true&force=true", a.root, dump);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(a.ds.metaGet("k1").isPresent());
        }
    }
}
