package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sem proxy confiável configurado, X-Forwarded-For não troca o endereço usado no limite de requisições. */
class RateLimitForwardedHttpTest {

    @Test
    void variarXForwardedForNaoContornaOLimite() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        ServerConfig config = ServerConfig.load(Path.of("nao-existe.conf"),
            Map.of("AXON_PORT", String.valueOf(port), "AXON_RATE_LIMIT", "2"));
        AxonServer server = AxonServer.start(Datastore.memory(), "secret-testing", config);
        try {
            HttpClient http = HttpClient.newHttpClient();
            int recusadas = 0;
            for (int i = 0; i < 20; i++) {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                    .header("X-Forwarded-For", "203.0.113." + i).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 429) {
                    recusadas++;
                }
            }
            assertTrue(recusadas > 0, "a rajada de um só endereço precisa ser limitada");
        } finally {
            server.stop();
        }
    }
}
