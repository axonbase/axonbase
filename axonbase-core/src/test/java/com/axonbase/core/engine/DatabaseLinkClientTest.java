package com.axonbase.core.engine;

import com.axonbase.value.AxonValue;
import com.axonbase.value.AxonJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DatabaseLinkClientTest {
    @Test
    void signsInWithLinkCredentialsAndSendsBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/signin")) {
                AxonValue credentials = AxonJson.decode(read(exchange).getBytes(StandardCharsets.UTF_8));
                assertEquals("remote", credentials.asObject().get("user").asString());
                assertEquals("secret", credentials.asObject().get("pass").asString());
                respond(exchange, "{\"token\":\"remote-token\"}");
                return;
            }
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "[{\"id\":1}]");
        });
        try {
            var link = new Datastore.DatabaseLinkDef("remote", url(server), "ns", "db", "remote", "secret");
            List<AxonValue> result = new DatabaseLinkClient().query(link, "SELECT * FROM person");

            assertEquals(1, result.size());
            assertEquals("Bearer remote-token", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void omitsAuthorizationWhenLinkHasNoCredentials() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "[]");
        });
        try {
            var link = new Datastore.DatabaseLinkDef("remote", url(server), "ns", "db", null, null);
            new DatabaseLinkClient().query(link, "SELECT * FROM person");

            assertNull(authorization.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/signin", handler);
        server.createContext("/sql", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
