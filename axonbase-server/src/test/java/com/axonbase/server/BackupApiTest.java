package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.backup.S3SyncManager;
import com.axonbase.core.backup.S3SyncManagerTest;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Estado, histórico e API de backup (E0, P4). */
class BackupApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Datastore ds;
    private S3SyncManagerTest.InMemoryBackupStore store;
    private S3SyncManager manager;
    private AxonServer server;
    private int port;
    private String root;
    private String operator;
    private final AtomicInteger stamp = new AtomicInteger();

    @BeforeEach
    void subir() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.authCatalog().defineUser("oper", com.axonbase.core.security.AuthCatalog.Scope.ROOT, null, null, "pw",
            List.of("operator"), List.of());
        store = new S3SyncManagerTest.InMemoryBackupStore();
        manager = new S3SyncManager(ds, store, "b", "pref", 3_600_000L, 5, "", () -> null,
            () -> String.format("20260101-0000%02d", stamp.incrementAndGet()));
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        server.backups(manager);
        port = server.port();
        root = signin("root");
        operator = signin("oper");
    }

    @AfterEach
    void descer() throws Exception {
        server.stop();
        manager.close();
    }

    private String signin(String user) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"" + user + "\",\"pass\":\"pw\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        return AxonJson.parseDocument(r.body()).asObject().get("token").asString();
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/admin/v1" + path))
            .header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static AxonValue json(HttpResponse<String> r) {
        return AxonJson.parseDocument(r.body());
    }

    private AxonValue awaitIdle() throws Exception {
        for (int i = 0; i < 100; i++) {
            AxonValue st = json(call("GET", "/backups/status", root, null));
            if (!st.asObject().get("running").asBool()) {
                return st;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("backup não terminou");
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    // ---------------- estado no manager ----------------

    @Test
    void managerGuardaEstadoEHistoricoDeExecucoes() {
        var events = new CopyOnWriteArrayList<String>();
        manager.listener((type, data) -> events.add(type + ":" + data.get("type")));
        assertTrue(manager.backupNow());
        var st = manager.status();
        assertNotNull(st.lastSuccessAtMillis());
        assertNull(st.lastError());
        assertEquals("full", st.lastType());
        assertTrue(st.lastSizeBytes() > 0);
        assertTrue(st.lastDurationMs() >= 0);
        assertFalse(st.running());
        assertEquals(1, manager.runsOk());
        assertEquals(List.of("backup.finished:full"), events);

        var entries = manager.listBackupEntries();
        assertEquals(1, entries.size());
        assertEquals("cluster-20260101-000001.axbak", entries.get(0).name());
        assertEquals("full", entries.get(0).type());
        assertTrue(entries.get(0).sizeBytes() > 0);
    }

    @Test
    void falhaDeUploadRegistraErroEEvento() {
        var events = new ArrayList<String>();
        manager.listener((type, data) -> events.add(type));
        var quebrado = new S3SyncManager(ds, new S3SyncManagerTest.InMemoryBackupStore() {
            @Override
            public void upload(String bucket, String key, java.io.InputStream data) {
                throw new RuntimeException("disco cheio");
            }
        }, "b", "p", 1000, 5, "", () -> null, () -> "20260101-000009");
        quebrado.listener((type, data) -> events.add(type + ":" + data.get("error")));
        assertFalse(quebrado.backupNow());
        var st = quebrado.status();
        assertTrue(st.lastError().contains("disco cheio"));
        assertNotNull(st.lastErrorAtMillis());
        assertEquals(1, quebrado.runsFailed());
        assertEquals(List.of("backup.failed:disco cheio"), events.stream().filter(e -> e.startsWith("backup.failed")).toList()
            .stream().map(e -> e.replaceAll("RuntimeException: ", "")).toList());
    }

    // ---------------- API ----------------

    @Test
    void statusListaEDisparoAssincrono() throws Exception {
        var st = json(call("GET", "/backups/status", root, null)).asObject();
        assertTrue(st.get("configured").asBool());
        assertFalse(st.get("running").asBool());
        assertTrue(st.get("lastSuccessAt").isNull() || st.get("lastSuccessAt").isNone());

        assertEquals(403, call("POST", "/backups", sem(), "{\"type\":\"auto\"}").statusCode());
        HttpResponse<String> trig = call("POST", "/backups", operator, "{\"type\":\"full\"}");
        assertEquals(202, trig.statusCode(), trig.body());
        assertNotNull(json(trig).asObject().get("runId").asString());

        AxonValue after = awaitIdle();
        assertEquals("full", after.asObject().get("lastType").asString());
        assertTrue(after.asObject().get("lastSizeBytes").asLong() > 0);
        assertFalse(after.asObject().get("lastSuccessAt").isNull());

        var list = json(call("GET", "/backups", operator, null)).asArray();
        assertEquals(1, list.size());
        assertEquals("full", list.get(0).asObject().get("type").asString());
        assertNotNull(list.get(0).asObject().get("createdAt").asString());

        assertEquals(400, call("POST", "/backups", operator, "{\"type\":\"xyz\"}").statusCode());

        String metrics = HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
            .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(metrics.contains("axe_backup_runs_total{result=\"success\"} 1"), metrics);
        assertTrue(metrics.contains("axe_backup_last_success_timestamp_seconds 17"), metrics);
        assertFalse(metrics.contains("axe_backup_last_size_bytes 0\n"), metrics);
    }

    private String sem() throws Exception {
        ds.authCatalog().defineUser("visao", com.axonbase.core.security.AuthCatalog.Scope.ROOT, null, null, "pw",
            List.of("viewer"), List.of());
        return signin("visao");
    }

    @Test
    void viewerNaoLeBackupsEBackupNaoConfiguradoDa409NoDisparo() throws Exception {
        assertEquals(403, call("GET", "/backups", sem(), null).statusCode());
        Datastore d2 = Datastore.memory();
        AxonServer s2 = AxonServer.startRandomPort(d2, "secret-testing", "root", "pw", true);
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + s2.port() + "/signin"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            String t = AxonJson.parseDocument(r.body()).asObject().get("token").asString();
            HttpResponse<String> st = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + s2.port() + "/admin/v1/backups/status"))
                .header("Authorization", "Bearer " + t).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(AxonJson.parseDocument(st.body()).asObject().get("configured").asBool());
            HttpResponse<String> trig = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + s2.port() + "/admin/v1/backups"))
                .header("Authorization", "Bearer " + t)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, trig.statusCode());
        } finally {
            s2.stop();
        }
    }

    @Test
    void restaurarExigeConfirmEArmazenamentoVazioOuForce() throws Exception {
        ds.execute("CREATE t:1 CONTENT {v: 1}", session(), null);
        assertTrue(manager.backupNow());
        String name = manager.listBackupEntries().get(0).name();
        String path = "/backups/" + name + "/restore";

        assertEquals(403, call("POST", path, operator, "{\"confirm\":true}").statusCode());
        assertEquals(400, call("POST", path, root, "{}").statusCode());
        assertEquals(400, call("POST", path, root, "{\"confirm\":false}").statusCode());
        // armazenamento tem registros: recusa sem force
        assertEquals(409, call("POST", path, root, "{\"confirm\":true}").statusCode());

        ds.execute("CREATE t:2 CONTENT {v: 2}", session(), null);
        HttpResponse<String> ok = call("POST", path, root, "{\"confirm\":true,\"force\":true}");
        assertEquals(200, ok.statusCode(), ok.body());
        AxonValue rows = ds.execute("SELECT * FROM t", session(), null);
        assertEquals(1, rows.asArray().size());

        assertEquals(404, call("POST", "/backups/nao-existe/restore", root, "{\"confirm\":true,\"force\":true}").statusCode());
    }
}
