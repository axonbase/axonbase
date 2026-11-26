package com.axonbase.core.partition.cold;

import com.axonbase.core.backup.S3BackupClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A camada fria sobre o cliente S3 do backup, contra um servidor HTTP mínimo no mesmo processo (envio, leitura,
 * remoção e listagem paginada). Não confere a assinatura: isso é papel do teste contra um MinIO real.
 */
@DisplayName("Camada fria: S3")
class S3ColdStoreTest {

    private static final int PAGE = 2;

    @TempDir
    Path tmp;

    private final NavigableMap<String, byte[]> objects = new ConcurrentSkipListMap<>();
    private HttpServer server;
    private ColdStore store;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String uri = "s3://frio/motor?endpoint=http://127.0.0.1:" + server.getAddress().getPort()
            + "&access=teste&secret=teste&auth=basic";
        S3BackupClient client = S3BackupClient.fromUri(uri);
        store = new S3ColdStore(client, client.bucket(uri), client.key(uri));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/frio".length());
        String key = path.startsWith("/") ? path.substring(1) : path;
        String method = exchange.getRequestMethod();
        if (method.equals("PUT")) {
            objects.put(key, exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, new byte[0]);
        } else if (method.equals("DELETE")) {
            objects.remove(key);
            respond(exchange, 204, null);
        } else if (key.isEmpty()) {
            respond(exchange, 200, listing(exchange.getRequestURI().getRawQuery()));
        } else {
            byte[] found = objects.get(key);
            respond(exchange, found == null ? 404 : 200, found == null ? new byte[0] : found);
        }
    }

    private byte[] listing(String rawQuery) {
        String prefix = "";
        String after = "";
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            if (kv[0].equals("prefix")) {
                prefix = value;
            } else if (kv[0].equals("continuation-token")) {
                after = value;
            }
        }
        String wanted = prefix;
        List<String> page = objects.tailMap(after, false).keySet().stream()
            .filter(k -> k.startsWith(wanted)).limit(PAGE + 1L).toList();
        boolean truncated = page.size() > PAGE;
        StringBuilder xml = new StringBuilder("<ListBucketResult>");
        page.stream().limit(PAGE).forEach(k -> xml.append("<Contents><Key>").append(k).append("</Key></Contents>"));
        xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            xml.append("<NextContinuationToken>").append(page.get(PAGE - 1)).append("</NextContinuationToken>");
        }
        return xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body == null || body.length == 0 ? -1 : body.length);
        if (body != null && body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @Test
    @DisplayName("envia e baixa um arquivo em fluxo, sob o prefixo configurado")
    void uploadAndDownload() throws IOException {
        byte[] data = new byte[300_000];
        new Random(7).nextBytes(data);
        Path source = Files.write(tmp.resolve("origem"), data);

        store.upload("parts/n/d/t/1.axc", source);

        assertTrue(objects.containsKey("motor/parts/n/d/t/1.axc"));
        assertTrue(store.download("parts/n/d/t/1.axc", tmp.resolve("copia")));
        assertArrayEquals(data, Files.readAllBytes(tmp.resolve("copia")));
        assertFalse(store.download("parts/nao", tmp.resolve("nada")));
    }

    @Test
    @DisplayName("lista todas as páginas e apaga")
    void listsEveryPageAndDeletes() throws IOException {
        for (int i = 0; i < 7; i++) {
            store.write("parts/" + i, ("v" + i).getBytes(StandardCharsets.UTF_8));
        }
        store.write("holds/a.json", "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(7, store.list("parts/").size());
        assertEquals("parts/0", store.list("parts/").get(0));
        store.delete("parts/0");
        assertEquals(6, store.list("parts/").size());
        assertTrue(store.read("parts/0").isEmpty());
        assertEquals("v1", new String(store.read("parts/1").orElseThrow(), StandardCharsets.UTF_8));
    }
}
