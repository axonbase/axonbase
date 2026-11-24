package com.axonbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** Depuração do AxonPL pela API HTTP de /admin/v1: fluxo completo, rollback, permissões e métricas. */
class DebugApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String GRAVA = "CREATE PROCEDURE grava(v) AS $$\n"
        + "db.exec('CREATE it CONTENT {v: $v}', {'v': v})\n"
        + "n = db.count('SELECT * FROM it')\n"
        + "return n + v\n$$";

    private AxonServer server;
    private int port;
    private String token;

    @BeforeEach
    void subir() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        port = server.port();
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        token = AxonJson.parseDocument(r.body()).asObject().get("token").asString();
        assertEquals(200, sql(GRAVA).statusCode());
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

    private HttpResponse<String> call(String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        if (auth) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private AxonValue json(HttpResponse<String> r, int status) {
        assertEquals(status, r.statusCode(), r.body());
        return r.body().isBlank() ? null : AxonJson.parseDocument(r.body());
    }

    private AxonValue iniciar(String body) throws Exception {
        return json(call("POST", "/admin/v1/databases/app/main/programs/grava/debug", body, true), 201);
    }

    /** Segue os eventos (long poll) até a sessão chegar ao estado. */
    private AxonValue esperar(String id, String estado) throws Exception {
        long after = 0;
        for (int i = 0; i < 50; i++) {
            AxonValue ev = json(call("GET", "/admin/v1/debug/sessions/" + id + "/events?after=" + after
                + "&wait=2000", null, true), 200);
            after = ev.asObject().get("next").asLong();
            if (ev.asObject().get("state").asString().equals(estado)) {
                return json(call("GET", "/admin/v1/debug/sessions/" + id, null, true), 200);
            }
        }
        throw new AssertionError("a sessao nao chegou a " + estado);
    }

    private long linhas() throws Exception {
        return AxonJson.parseDocument(sql("SELECT * FROM it").body()).asArray().size();
    }

    @Test
    void fluxoCompletoPausaAvaliaContinuaEReverte() throws Exception {
        AxonValue s = iniciar("{\"args\":[4],\"breakpoints\":[3]}");
        String id = s.asObject().get("id").asString();
        assertEquals("running", s.asObject().get("state").asString());
        assertTrue(s.asObject().get("source").asString().contains("db.count"));

        AxonValue pausada = esperar(id, "paused");
        java.util.Map<String, AxonValue> pilha = pausada.asObject().get("pause").asObject().get("stack").asArray().get(0)
            .asObject();
        assertEquals("grava", pilha.get("function").asString());
        assertEquals(3, pilha.get("line").asLong());
        assertTrue(pilha.get("locals").asArray().stream().anyMatch(v -> v.asObject().get("name").asString()
            .equals("n") && v.asObject().get("value").asString().equals("1")));

        AxonValue ev = json(call("POST", "/admin/v1/debug/sessions/" + id + "/evaluate",
            "{\"expression\":\"v * 10\"}", true), 200);
        assertEquals("40", ev.asObject().get("value").asString());
        json(call("POST", "/admin/v1/debug/sessions/" + id + "/evaluate", "{\"expression\":\"+\"}", true), 400);
        // a escrita feita durante a depuração não aparece para quem está fora dela
        assertEquals(0, linhas());

        json(call("POST", "/admin/v1/debug/sessions/" + id + "/command", "{\"command\":\"continue\"}", true), 200);
        AxonValue fim = esperar(id, "finished");
        assertEquals(5, fim.asObject().get("result").asLong());
        assertFalse(fim.asObject().get("committed").asBool());
        assertEquals(2, fim.asObject().get("profile").asObject().get("queries").asArray().size());
        assertEquals(0, linhas());

        AxonValue lista = json(call("GET", "/admin/v1/debug/sessions", null, true), 200);
        assertEquals(1, lista.asArray().size());
        json(call("DELETE", "/admin/v1/debug/sessions/" + id, null, true), 204);
        json(call("GET", "/admin/v1/debug/sessions/" + id, null, true), 404);
    }

    @Test
    void commitVerdadeiroPersisteParaAdmin() throws Exception {
        AxonValue s = iniciar("{\"args\":[1],\"commit\":true,\"stopOnEntry\":false}");
        AxonValue fim = esperar(s.asObject().get("id").asString(), "finished");
        assertTrue(fim.asObject().get("committed").asBool());
        assertEquals(1, linhas());
    }

    @Test
    void perfilSemParadaExecutaAteOFim() throws Exception {
        AxonValue s = iniciar("{\"args\":[2],\"profile\":true}");
        AxonValue fim = esperar(s.asObject().get("id").asString(), "finished");
        assertEquals(3, fim.asObject().get("profile").asObject().get("lines").asArray().size());
        assertEquals(0, linhas());
    }

    @Test
    void stopEPontosDeParadaPelaApi() throws Exception {
        AxonValue s = iniciar("{\"args\":[2],\"stopOnEntry\":true}");
        String id = s.asObject().get("id").asString();
        AxonValue p = esperar(id, "paused");
        assertEquals(1, p.asObject().get("pause").asObject().get("stack").asArray().get(0).asObject().get("line")
            .asLong());
        AxonValue bp = json(call("PUT", "/admin/v1/debug/sessions/" + id + "/breakpoints", "{\"lines\":[3]}", true),
            200);
        assertEquals(1, bp.asObject().get("breakpoints").asArray().size());
        json(call("POST", "/admin/v1/debug/sessions/" + id + "/command", "{\"command\":\"continue\"}", true), 200);
        esperar(id, "paused");
        json(call("POST", "/admin/v1/debug/sessions/" + id + "/command", "{\"command\":\"stop\"}", true), 200);
        AxonValue fim = json(call("GET", "/admin/v1/debug/sessions/" + id, null, true), 200);
        assertEquals("stopped", fim.asObject().get("state").asString());
        json(call("POST", "/admin/v1/debug/sessions/" + id + "/command", "{\"command\":\"continue\"}", true), 409);
        json(call("POST", "/admin/v1/debug/sessions/" + id + "/command", "{\"command\":\"voar\"}", true), 400);
        assertEquals(0, linhas());
    }

    @Test
    void semTokenEProgramaInexistente() throws Exception {
        json(call("POST", "/admin/v1/databases/app/main/programs/grava/debug", "{}", false), 401);
        json(call("POST", "/admin/v1/databases/app/main/programs/nada/debug", "{}", true), 404);
        json(call("GET", "/admin/v1/debug/sessions/inexistente", null, true), 404);
    }

    @Test
    void metricasExpoemSessoesETimeouts() throws Exception {
        AxonValue s = iniciar("{\"args\":[2],\"breakpoints\":[3]}");
        String id = s.asObject().get("id").asString();
        esperar(id, "paused");
        String m = call("GET", "/metrics", null, true).body();
        assertTrue(m.contains("axe_pl_debug_sessions 1"), m);
        assertTrue(m.contains("axe_pl_debug_timeouts_total 0"), m);
        assertNotNull(m);
        json(call("DELETE", "/admin/v1/debug/sessions/" + id, null, true), 204);
        assertTrue(call("GET", "/metrics", null, true).body().contains("axe_pl_debug_sessions 0"));
    }
}
