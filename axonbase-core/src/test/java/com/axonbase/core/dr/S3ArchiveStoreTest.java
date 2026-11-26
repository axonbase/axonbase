package com.axonbase.core.dr;

import com.axonbase.common.AxonError;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Um S3 de mentira, com listagem em páginas de dois objetos, como um balde grande de verdade. */
@DisplayName("DR: arquivamento em S3")
class S3ArchiveStoreTest {

    private static final int PAGE = 2;

    @TempDir
    Path dir;

    private final Map<String, byte[]> objects = new ConcurrentSkipListMap<>();
    private final List<String> authorizations = new java.util.concurrent.CopyOnWriteArrayList<>();
    private HttpServer server;
    private ArchiveStore store;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.createContext("/", this::serve);
        server.start();
        store = new S3ArchiveStore(new S3BackupClient(
            "http://127.0.0.1:" + server.getAddress().getPort(), "", "acesso", "segredo-de-teste"), "balde");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String path = exchange.getRequestURI().getPath().substring(1);
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (query.containsKey("list-type")) {
                list(exchange, query);
                return;
            }
            String key = path.substring("balde/".length());
            if (exchange.getRequestMethod().equals("PUT")) {
                objects.put(key, exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            byte[] body = objects.get(key);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private void list(HttpExchange exchange, Map<String, String> query) throws IOException {
        String prefix = query.getOrDefault("prefix", "");
        List<String> matching = objects.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        int from = Integer.parseInt(query.getOrDefault("continuation-token", "pagina=0").split("=")[1]);
        StringBuilder xml = new StringBuilder("<ListBucketResult>");
        matching.stream().skip(from).limit(PAGE)
            .forEach(k -> xml.append("<Contents><Key>").append(k).append("</Key></Contents>"));
        if (from + PAGE < matching.size()) {
            xml.append("<IsTruncated>true</IsTruncated><NextContinuationToken>pagina=").append(from + PAGE)
                .append("</NextContinuationToken>");
        }
        byte[] body = xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                out.put(parts[0], URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private Path file(String name, int bytes) throws IOException {
        byte[] content = new byte[bytes];
        new java.util.Random(name.hashCode()).nextBytes(content);
        return Files.write(dir.resolve(name), content);
    }

    @Test
    @DisplayName("grava, lista todas as páginas e baixa os mesmos bytes, com requisições assinadas")
    void roundTripWithPagination() throws IOException {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String key = "axon-dr/e1/seg-" + i;
            store.put(key, file("f" + i, 100_000 + i));
            expected.add(key);
        }
        store.put("outro/x", file("x", 1));

        assertEquals(expected, store.list("axon-dr/e1/"));
        Path downloaded = dir.resolve("baixado/seg-6");
        store.get("axon-dr/e1/seg-6", downloaded);
        assertArrayEquals(Files.readAllBytes(dir.resolve("f6")), Files.readAllBytes(downloaded));
        assertTrue(authorizations.stream().allMatch(a -> a.startsWith("AWS4-HMAC-SHA256")), authorizations.toString());
        assertTrue(authorizations.stream().noneMatch(a -> a.contains("segredo-de-teste")));
    }

    @Test
    @DisplayName("um objeto inexistente é erro e não cria arquivo")
    void missingObject() {
        Path target = dir.resolve("nada");

        assertThrows(AxonError.class, () -> store.get("axon-dr/nao-existe", target));

        assertFalse(Files.exists(target));
        assertEquals(List.of(), store.list("axon-dr/"));
    }

    @Test
    @DisplayName("o arquivamento em diretório recusa chaves que saem dele")
    void directoryStoreRejectsEscapes() throws IOException {
        DirectoryArchiveStore local = new DirectoryArchiveStore(dir.resolve("raiz"));
        Path source = file("origem", 10);

        assertThrows(IllegalArgumentException.class, () -> local.put("../fora", source));
        assertThrows(IllegalArgumentException.class, () -> local.put("", source));
        local.put("a/b", source);
        assertEquals(List.of("a/b"), local.list("a/"));
        assertEquals(List.of(), new DirectoryArchiveStore(dir.resolve("vazia")).list(""));
    }

    @Test
    @DisplayName("a cifra protege o objeto e a chave errada não abre")
    void encryption() throws IOException {
        ArchiveStore sealed = new EncryptedArchiveStore(store, "chave-certa");
        Path source = file("claro", 200_000);

        sealed.put("k", source);

        byte[] stored = objects.get("k");
        assertFalse(java.util.Arrays.equals(Files.readAllBytes(source), stored));
        sealed.get("k", dir.resolve("aberto"));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(dir.resolve("aberto")));
        assertThrows(RuntimeException.class,
            () -> new EncryptedArchiveStore(store, "chave-errada").get("k", dir.resolve("nao-abre")));
        store.put("sem-cifra", source);
        assertThrows(RuntimeException.class, () -> sealed.get("sem-cifra", dir.resolve("nao-abre-2")));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedArchiveStore(store, " "));
    }
}
