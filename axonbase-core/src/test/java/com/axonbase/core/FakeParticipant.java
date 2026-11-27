package com.axonbase.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Participante remoto de mentira: guarda o que recebe em {@code /sql} e responde uma lista vazia. */
final class FakeParticipant implements AutoCloseable {

    private final HttpServer server;
    private final List<String> received;

    private FakeParticipant(HttpServer server, List<String> received) {
        this.server = server;
        this.received = received;
    }

    static FakeParticipant start() throws IOException {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sql", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new FakeParticipant(server, received);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
