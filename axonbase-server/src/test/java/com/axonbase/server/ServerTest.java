package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Servidor: endpoints HTTP")
class ServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static AxonServer startServer() throws Exception {
        Datastore ds = Datastore.memory();
        return AxonServer.startRandomPort(ds, "secret-testing");
    }

    private static String post(int port, String path, String body, String ns, String db) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (ns != null) {
            b.header("Axon-Ns", ns);
        }
        if (db != null) {
            b.header("Axon-Db", db);
        }
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    @Test
    void healthEVersion() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            HttpResponse<String> h = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, h.statusCode());
            assertTrue(h.body().contains("\"ok\""));

            h = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/version")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, h.statusCode());
        } finally {
            srv.stop();
        }
    }

    @Test
    void sqlCrud() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String create = post(port, "/sql", "CREATE person CONTENT { name: \"Ana\", age: 30 }", "test", "dev");
            AxonValue created = AxonJson.parseDocument(create);
            assertTrue(created.isArray());
            assertEquals(1, created.asArray().size());

            String sel = post(port, "/sql", "SELECT * FROM person", "test", "dev");
            AxonValue rows = AxonJson.parseDocument(sel);
            assertTrue(rows.isArray());
            assertEquals(1, rows.asArray().size());
            assertEquals("Ana", rows.asArray().get(0).asObject().get("name").asString());
        } finally {
            srv.stop();
        }
    }

    @Test
    void restTable() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String r = post(port, "/table/person", "{\"name\":\"Bob\",\"age\":21}", "test", "dev");
            assertTrue(r.contains("\"Bob\""));
        } finally {
            srv.stop();
        }
    }

    @Test
    void signinDevolveToken() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String r = post(port, "/signin", "{\"user\":\"root\",\"pass\":\"root\"}", null, null);
            assertTrue(r.contains("\"token\":"));
        } finally {
            srv.stop();
        }
    }

    @Test
    void rpcPing() throws Exception {
        AxonServer srv = startServer();
        try {
            int port = srv.port();
            String ping = post(port, "/rpc", "{\"id\":1,\"method\":\"ping\",\"params\":[]}", "test", "dev");
            assertTrue(ping.contains("\"result\":true"));
        } finally {
            srv.stop();
        }
    }
}