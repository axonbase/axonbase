package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Buffer de logs e rotas /admin/v1/logs (E0b). */
class LogsApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private AxonServer server;
    private String root;
    private int port;

    @BeforeEach
    void subir() throws Exception {
        AxonLog.buffer(new LogBuffer(5));
        server = AxonServer.startRandomPort(Datastore.memory(), "secret-testing", "root", "pw", true);
        port = server.port();
        root = AxonJson.parseDocument(HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
            HttpResponse.BodyHandlers.ofString()).body()).asObject().get("token").asString();
    }

    @AfterEach
    void descer() throws Exception {
        server.stop();
        AxonLog.buffer(new LogBuffer(AxonLog.envCapacity()));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var b = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/admin/v1" + path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bufferCircularDescartaAsMaisAntigas() {
        LogBuffer b = new LogBuffer(3);
        for (int i = 0; i < 5; i++) {
            b.add(new LogBuffer.Entry(1000 + i, "info", "e" + i, "m", Map.of()));
        }
        List<LogBuffer.Entry> all = b.snapshot(100, null, 0);
        assertEquals(List.of("e2", "e3", "e4"), all.stream().map(LogBuffer.Entry::event).toList());
        assertEquals(List.of("e3", "e4"), b.snapshot(2, null, 0).stream().map(LogBuffer.Entry::event).toList());
        assertEquals(List.of("e4"), b.snapshot(100, null, 1004).stream().map(LogBuffer.Entry::event).toList());
    }

    @Test
    void filtraPorNivelMinimo() {
        LogBuffer b = new LogBuffer(10);
        b.add(new LogBuffer.Entry(1, "info", "a", "", Map.of()));
        b.add(new LogBuffer.Entry(2, "warn", "b", "", Map.of()));
        b.add(new LogBuffer.Entry(3, "error", "c", "", Map.of()));
        assertEquals(List.of("b", "c"), b.snapshot(10, "warn", 0).stream().map(LogBuffer.Entry::event).toList());
        assertEquals(List.of("c"), b.snapshot(10, "error", 0).stream().map(LogBuffer.Entry::event).toList());
    }

    @Test
    void axonLogAlimentaOBufferEARotaDevolveNoFormatoDoContrato() throws Exception {
        assertEquals(401, get("/logs", null).statusCode());
        AxonLog.log("warn", "meu_evento", "algo aconteceu", Map.of("ns", "app", "duration_ms", 12));
        HttpResponse<String> r = get("/logs?level=warn&limit=10", root);
        assertEquals(200, r.statusCode());
        List<AxonValue> arr = AxonJson.parseDocument(r.body()).asArray();
        AxonValue last = arr.get(arr.size() - 1);
        assertEquals("meu_evento", last.asObject().get("event").asString());
        assertEquals("warn", last.asObject().get("level").asString());
        assertEquals("algo aconteceu", last.asObject().get("msg").asString());
        assertNotNull(last.asObject().get("ts"));
        assertEquals("app", last.asObject().get("fields").asObject().get("ns").asString());
        assertEquals(400, get("/logs?since=ontem", root).statusCode());
        assertEquals(400, get("/logs?limit=abc", root).statusCode());
    }

    @Test
    void streamEntregaLinhasNovas() throws Exception {
        assertEquals(401, get("/logs/stream", null).statusCode());
        HttpResponse<java.io.InputStream> stream = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/admin/v1/logs/stream"))
            .header("Authorization", "Bearer " + root).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
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
        assertTrue(lines.poll(5, TimeUnit.SECONDS).startsWith(":"));
        AxonLog.log("info", "stream_evento", "via sse");
        String data = null;
        for (int i = 0; i < 20 && data == null; i++) {
            String l = lines.poll(5, TimeUnit.SECONDS);
            if (l != null && l.startsWith("data: ") && l.contains("stream_evento")) {
                data = l.substring(6);
            }
        }
        assertNotNull(data);
        assertEquals("stream_evento", AxonJson.parseDocument(data).asObject().get("event").asString());
    }
}
