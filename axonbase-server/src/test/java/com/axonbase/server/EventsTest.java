package com.axonbase.server;

import com.axonbase.core.audit.AuditCase;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Eventos SSE, webhook e logs JSON unificados (E0, P5). */
class EventsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void barramentoEntregaEventoNoFormatoDoContrato() {
        EventBus bus = new EventBus();
        var got = new CopyOnWriteArrayList<String>();
        AutoCloseable sub = bus.subscribe(got::add);
        bus.publish("backup.finished", Map.of("name", "x"));
        AxonValue ev = AxonJson.parseDocument(got.get(0));
        assertEquals("backup.finished", ev.asObject().get("type").asString());
        assertNotNull(ev.asObject().get("time").asString());
        assertEquals("x", ev.asObject().get("data").asObject().get("name").asString());
        try {
            sub.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        bus.publish("backup.finished", Map.of());
        assertEquals(1, got.size());
    }

    @Test
    void catalogoDeAuditoriaEmiteCasoPendenteEBloqueio() {
        Datastore ds = Datastore.memory();
        var events = new CopyOnWriteArrayList<String>();
        ds.eventListener((type, data) -> events.add(type + ":" + data.get("ns") + "/" + data.get("db")));
        ds.createDatabase("app", "main");
        var cat = ds.requireDatabase(sessao()).auditCatalog();
        cat.addCase("h", new AuditCase("h", "joao", "DELETE t", "a", "WARNING", System.currentTimeMillis()));
        cat.blockUser("joao", "a", "DROP t", "perigo", "sys");
        assertEquals(List.of("audit.case.pending:app/main", "audit.user.blocked:app/main"), events);
    }

    private static com.axonbase.core.Session sessao() {
        var s = com.axonbase.core.Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    @Test
    void sseExigeTokenEEntregaEventosPublicados() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            String base = "http://127.0.0.1:" + server.port();
            assertEquals(401, HTTP.send(HttpRequest.newBuilder().uri(URI.create(base + "/admin/v1/events")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
            String token = AxonJson.parseDocument(HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(base + "/signin"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).body()).asObject().get("token").asString();

            HttpResponse<java.io.InputStream> stream = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(base + "/admin/v1/events")).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            assertTrue(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = new Thread(() -> {
                try (var br = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) {
                        lines.add(l);
                    }
                } catch (Exception ignored) {
                    // conexão encerrada
                }
            });
            reader.setDaemon(true);
            reader.start();
            // o servidor confirma a assinatura antes de publicar
            String first = lines.poll(5, TimeUnit.SECONDS);
            assertNotNull(first, "sem comentário inicial");
            assertTrue(first.startsWith(":"), first);

            server.events().publish("audit.case.pending", Map.of("hash", "abc"));
            String data = null;
            for (int i = 0; i < 10 && data == null; i++) {
                String l = lines.poll(5, TimeUnit.SECONDS);
                if (l != null && l.startsWith("data: ")) {
                    data = l.substring(6);
                }
            }
            assertNotNull(data);
            AxonValue ev = AxonJson.parseDocument(data);
            assertEquals("audit.case.pending", ev.asObject().get("type").asString());
            assertEquals("abc", ev.asObject().get("data").asObject().get("hash").asString());
        } finally {
            server.stop();
        }
    }

    @Test
    void webhookAssinaCorpoComHmacSha256() throws Exception {
        BlockingQueue<String[]> received = new LinkedBlockingQueue<>();
        HttpServer hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/hook", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new String[] {body, ex.getRequestHeaders().getFirst("X-Axon-Signature")});
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        hook.start();
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            server.webhook("http://127.0.0.1:" + hook.getAddress().getPort() + "/hook", "segredo");
            server.events().publish("backup.failed", Map.of("error", "boom"));
            String[] got = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(got, "webhook não chamado");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("segredo".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(got[0].getBytes(StandardCharsets.UTF_8)));
            assertEquals("sha256=" + expected, got[1]);
            assertEquals("backup.failed", AxonJson.parseDocument(got[0]).asObject().get("type").asString());
        } finally {
            server.stop();
            hook.stop(0);
        }
    }

    @Test
    void mudancaDeLiderGeraEvento() throws Exception {
        AtomicReference<String> leader = new AtomicReference<>("n1");
        ClusterStatusProvider provider = () -> new ClusterStatusProvider.Status("n2", "g", leader.get(),
            leader.get() + ":8000", ClusterRole.FOLLOWER, 3, 9, 1, 3, 2);
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            BlockingQueue<String> seen = new LinkedBlockingQueue<>();
            server.events().subscribe(seen::add);
            server.leaderWatchIntervalMs(50);
            server.clusterStatus(provider);
            Thread.sleep(200);
            leader.set("n3");
            String ev = null;
            for (int i = 0; i < 20 && ev == null; i++) {
                String s = seen.poll(500, TimeUnit.MILLISECONDS);
                if (s != null && s.contains("leader.changed") && s.contains("\"n3\"")) {
                    ev = s;
                }
            }
            assertNotNull(ev, "leader.changed não emitido");
            assertEquals("n1", AxonJson.parseDocument(ev).asObject().get("data").asObject().get("previous").asString());
        } finally {
            server.stop();
        }
    }

    @Test
    void logDeRequisicaoSegueOContratoJson() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            String token = AxonJson.parseDocument(HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/signin"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).body()).asObject().get("token").asString();
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + server.port() + "/sql"))
                .header("Authorization", "Bearer " + token).header("Axon-Ns", "n").header("Axon-Db", "d")
                .header("X-Request-Id", "req-42")
                .POST(HttpRequest.BodyPublishers.ofString("SELECT * FROM t")).build(), HttpResponse.BodyHandlers.ofString());
            Thread.sleep(100);
        } finally {
            System.setOut(original);
            server.stop();
        }
        String line = captured.toString(StandardCharsets.UTF_8).lines()
            .filter(l -> l.contains("req-42")).findFirst().orElseThrow();
        Map<String, AxonValue> o = AxonJson.parseDocument(line).asObject();
        assertEquals("info", o.get("level").asString());
        assertEquals("http_request", o.get("event").asString());
        assertEquals("req-42", o.get("request_id").asString());
        assertEquals("root", o.get("user").asString());
        assertEquals("n", o.get("ns").asString());
        assertEquals("d", o.get("db").asString());
        assertTrue(o.get("duration_ms").isNumber());
        assertEquals(200, o.get("status").asLong());
        assertEquals("POST", o.get("method").asString());
        assertNotNull(o.get("time"));
    }

    @Test
    void linhaDeLogEscapaEOmiteCamposVazios() {
        String line = AxonLog.json("error", "x", "msg \"com aspas\"", Map.of("error", "a\nb", "user", ""));
        Map<String, AxonValue> o = AxonJson.parseDocument(line).asObject();
        assertEquals("msg \"com aspas\"", o.get("msg").asString());
        assertEquals("a\nb", o.get("error").asString());
        assertTrue(!o.containsKey("user"));
    }
}
