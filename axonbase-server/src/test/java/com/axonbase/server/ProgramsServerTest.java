package com.axonbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AxonPL pelo servidor: /sql, RPC, /metrics e a rota admin de programas. */
class ProgramsServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Datastore ds;
    private AxonServer server;
    private int port;
    private String token;

    @BeforeEach
    void subir() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("app", "main");
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        port = server.port();
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        token = AxonJson.parseDocument(r.body()).asObject().get("token").asString();
    }

    @AfterEach
    void descer() throws Exception {
        server.stop();
    }

    private HttpResponse<String> sql(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/sql"))
            .header("Authorization", "Bearer " + token).header("Axon-Ns", "app").header("Axon-Db", "main")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rpc(String query) throws Exception {
        String payload = "{\"id\":1,\"method\":\"query\",\"params\":[" + AxonJson.write(AxonValue.str(query)) + "]}";
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/rpc"))
            .header("Authorization", "Bearer " + token).header("Axon-Ns", "app").header("Axon-Db", "main")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void callESelectComFuncaoPorHttpERpc() throws Exception {
        assertEquals(200, sql("CREATE FUNCTION dobro(x) AS $$ return x * 2 $$").statusCode());
        assertEquals(200, sql("CREATE PROCEDURE grava(v) AS $$ db.exec('CREATE it CONTENT {v: $v}', {'v': v})\n"
            + "return db.one('RETURN dobro($v)', {'v': v})\n$$").statusCode());
        assertEquals(200, sql("CALL grava(4)").statusCode());
        HttpResponse<String> sel = sql("SELECT v, dobro(v) AS d FROM it");
        assertEquals(200, sel.statusCode());
        AxonValue rows = AxonJson.parseDocument(sel.body());
        assertEquals(8, rows.asArray().get(0).asObject().get("d").asLong());

        HttpResponse<String> viaRpc = rpc("SELECT dobro(v) AS d FROM it");
        assertEquals(8, AxonJson.parseDocument(viaRpc.body()).asObject().get("result").asArray().get(0)
            .asObject().get("d").asLong());
        HttpResponse<String> callRpc = rpc("CALL grava(5)");
        assertTrue(callRpc.body().contains("result"), callRpc.body());
        assertEquals(2, AxonJson.parseDocument(sql("SELECT * FROM it").body()).asArray().size());
    }

    @Test
    void erroDeProgramaTrazTracebackNoDetail() throws Exception {
        sql("CREATE FUNCTION dividir(a, b) AS $$\nreturn a / b\n$$");
        HttpResponse<String> r = sql("RETURN dividir(1, 0)");
        assertEquals(400, r.statusCode());
        AxonValue body = AxonJson.parseDocument(r.body());
        String detail = body.asObject().get("detail").asString();
        assertTrue(detail.contains("ZeroDivisionError"), detail);
        assertTrue(detail.contains("Traceback") && detail.contains("linha 1"), detail);
        assertFalse(detail.contains(".java"), detail);
    }

    @Test
    void metricasDoPl() throws Exception {
        sql("CREATE FUNCTION f() AS $$ return 1 $$");
        sql("RETURN f()");
        sql("RETURN f()");
        sql("CREATE FUNCTION laco() AS $$\nwhile True:\n    pass\n$$");
        sql("RETURN laco()");
        String m = get("/metrics").body();
        for (String name : new String[] {"axe_pl_calls_total", "axe_pl_errors_total", "axe_pl_seconds_total",
            "axe_pl_steps_total", "axe_pl_limit_errors_total"}) {
            assertTrue(m.contains(name + " "), name);
        }
        assertTrue(metric(m, "axe_pl_calls_total") >= 3, m);
        assertEquals(1, metric(m, "axe_pl_limit_errors_total"));
        assertTrue(metric(m, "axe_pl_steps_total") > 1_000_000);
    }

    private static double metric(String body, String name) {
        for (String line : body.split("\n")) {
            if (line.startsWith(name + " ")) {
                return Double.parseDouble(line.substring(name.length() + 1).trim());
            }
        }
        throw new AssertionError("sem a metrica " + name);
    }

    @Test
    void rotaAdminListaProgramas() throws Exception {
        sql("CREATE FUNCTION f(a int, b = 2) RETURNS int AS $$\n\"\"\"Soma.\"\"\"\nreturn a + b\n$$");
        sql("CREATE PROCEDURE p() AS $$ pass $$");
        sql("CREATE PACKAGE util AS $$\nK = 1\ndef g():\n    return K\n$$");
        HttpResponse<String> r = get("/admin/v1/databases/app/main/programs");
        assertEquals(200, r.statusCode(), r.body());
        AxonValue list = AxonJson.parseDocument(r.body());
        assertEquals(3, list.asArray().size());
        AxonValue f = list.asArray().stream().filter(v -> v.asObject().get("name").asString().equals("f"))
            .findFirst().orElseThrow();
        assertEquals("FUNCTION", f.asObject().get("kind").asString());
        assertEquals("Soma.", f.asObject().get("doc").asString());
        assertEquals(2, f.asObject().get("params").asArray().size());
        assertEquals(1, f.asObject().get("version").asLong());
        AxonValue pkg = list.asArray().stream().filter(v -> v.asObject().get("kind").asString().equals("PACKAGE"))
            .findFirst().orElseThrow();
        assertEquals("g", pkg.asObject().get("functions").asArray().get(0).asString());
    }
}
