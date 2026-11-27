package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** O nome de tabela escrito na consulta não pode virar comando no servidor do DATABASE LINK. */
@DisplayName("DATABASE LINK: nome de tabela hostil não é encaminhado")
class DatabaseLinkInjectionTest {

    private static final String HOSTILE = "`t; DEFINE USER evil ON ROOT PASSWORD 'Test-pw-1' ROLES OWNER`";

    @Test
    void nadaChegaAoServidorRemoto() throws IOException {
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
        try {
            Datastore ds = Datastore.memory();
            Session s = Session.create();
            s.namespace("n");
            s.database("d");
            ds.execute("DEFINE DATABASE LINK \"remoto\" CONNECT BY \"http://127.0.0.1:"
                + server.getAddress().getPort() + "\" WITH ns = \"n\" db = \"d\"", s, null);

            for (String sql : List.of(
                "SELECT * FROM remoto." + HOSTILE,
                "SELECT * FROM remoto." + HOSTILE + ":1",
                "CREATE remoto." + HOSTILE + ":1 SET a = 1",
                "UPDATE remoto." + HOSTILE + ":1 SET a = 1",
                "DELETE remoto." + HOSTILE + ":1")) {
                assertThrows(RuntimeException.class, () -> ds.execute(sql, s, null), sql);
            }

            assertEquals(List.of(), received);
            ds.execute("SELECT * FROM remoto.person", s, null);
            assertEquals(1, received.size());
        } finally {
            server.stop(0);
        }
    }
}
