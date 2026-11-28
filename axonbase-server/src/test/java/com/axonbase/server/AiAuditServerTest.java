package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.audit.AiProviderClient;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auditoria por IA pelo servidor, com um provedor de IA simulado em loopback. */
class AiAuditServerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String RULES = "{\"warning\":[{\"type\":\"require_where\"},"
        + "{\"type\":\"keyword\",\"words\":[\"CREATE USER\"]}],"
        + "\"danger\":[{\"type\":\"command\",\"commands\":[\"DROP TABLE\"]}]}";

    private HttpServer provider;
    private Datastore ds;
    private AxonServer server;
    private String root;

    @BeforeEach
    void setUp() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            Map<String, AxonValue> message = Map.of("content", AxonValue.str(RULES));
            AxonValue body = AxonValue.object(Map.of("choices", AxonValue.array(java.util.List.of(
                AxonValue.object(Map.of("message", AxonValue.object(message)))))));
            byte[] out = AxonJson.encode(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        provider.start();

        ds = Datastore.memory();
        ds.createDatabase("app", "aud");
        ds.aiProvider(new AiProviderClient("openai", "simulado", "chave-de-teste",
            "http://127.0.0.1:" + provider.getAddress().getPort()));
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);

        HttpResponse<String> r = post("/signin", null, "{\"user\":\"root\",\"pass\":\"pw\"}");
        root = AxonJson.parseDocument(r.body()).asObject().get("token").asString();

        ok(root, "CREATE AI AUDIT prot SET WARNING WHEN 'delete sem where' SET DANGER WHEN 'drop table'");
        ok(root, "CREATE c:1 CONTENT {dono: 'aud1'}");
        ok(root, "CREATE c:2 CONTENT {dono: 'outro'}");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        provider.stop(0);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Axon-Ns", "app").header("Axon-Db", "aud");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private void ok(String token, String sql) throws Exception {
        HttpResponse<String> r = post("/sql", token, sql);
        assertEquals(200, r.statusCode(), r.body());
    }

    private String createAudited(String name, String extra) throws Exception {
        ok(root, "CREATE USER " + name + " ON DATABASE PASSWORD 'Test-pw-1' ROLES editor" + extra
            + " AUDITED BY prot");
        HttpResponse<String> r = post("/rpc", null, "{\"id\":1,\"method\":\"signin\",\"params\":[{\"user\":\""
            + name + "\",\"pass\":\"Test-pw-1\"}]}");
        return AxonJson.parseDocument(r.body()).asObject().get("result").asString();
    }

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("aud");
        return s;
    }

    private int rows() {
        return ds.execute("SELECT * FROM c", local(), null).asArray().size();
    }

    private String pendingHash() {
        var cases = ds.requireDatabase(local()).auditCatalog().listCases();
        assertEquals(1, cases.size());
        return cases.get(0).hash();
    }

    private HttpResponse<String> approve(String hash) throws Exception {
        return post("/sql", root, "SET AUDIT CASE \"" + hash + "\" AUTHORIZED REASON \"ok\"");
    }

    @Test
    void comandoPerigosoDeUsuarioAuditadoEBloqueado() throws Exception {
        String token = createAudited("aud1", "");
        HttpResponse<String> r = post("/sql", token, "DROP TABLE c");
        assertNotEquals(200, r.statusCode(), r.body());
        assertEquals(2, rows());
        pendingHash();
    }

    @Test
    void aprovacaoNaoDaAoAutorOPoderDoAprovador() throws Exception {
        String token = createAudited("aud1", "");
        HttpResponse<String> r = post("/sql", token,
            "CREATE USER intruso ON ROOT PASSWORD 'Test-pw-1' ROLES admin");
        assertNotEquals(200, r.statusCode(), r.body());
        HttpResponse<String> approved = approve(pendingHash());
        assertNotEquals(200, approved.statusCode(), approved.body());
        assertNull(ds.authCatalog().user("intruso", null, null));
    }

    @Test
    void aprovacaoExecutaComAsDataRulesDoAutor() throws Exception {
        ok(root, "CREATE DATA RULE meus APPLY dono = $auth.id");
        String token = createAudited("aud1", " APPLY DATA RULE meus");
        assertNotEquals(200, post("/sql", token, "DELETE c").statusCode());
        HttpResponse<String> approved = approve(pendingHash());
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals(1, rows());
        assertNotNull(ds.execute("SELECT * FROM c:2", local(), null));
        assertEquals("outro", ds.execute("RETURN c:2.dono", local(), null).asString());
    }

    @Test
    void administradorAuditadoNaoAlteraAuditoriaPelaGestaoNemPorSignup() throws Exception {
        ok(root, "CREATE USER chefe ON ROOT PASSWORD 'Test-pw-1' ROLES admin AUDITED BY prot");
        String chefe = AxonJson.parseDocument(
            post("/signin", null, "{\"user\":\"chefe\",\"pass\":\"Test-pw-1\"}").body())
            .asObject().get("token").asString();
        var cases = ds.requireDatabase(local()).auditCatalog();
        cases.blockUser("bia", "prot", "DELETE c", "perigo", "sys");

        assertEquals(403, post("/admin/v1/audit/blocked-users/bia/unblock", chefe, "{}").statusCode());
        assertEquals(403, post("/admin/v1/service-tokens", chefe, "{}").statusCode());
        HttpResponse<String> dropped = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.port() + "/admin/v1/users/ROOT/chefe"))
            .header("Authorization", "Bearer " + chefe).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, dropped.statusCode(), dropped.body());
        post("/rpc", chefe, "{\"id\":2,\"method\":\"signup\",\"params\":[{\"user\":\"livre\","
            + "\"pass\":\"Test-pw-1\",\"ns\":\"app\",\"db\":\"aud\"}]}");

        assertTrue(cases.isBlocked("bia"));
        assertNotNull(ds.authCatalog().user("chefe", null, null));
        assertNull(ds.authCatalog().user("livre", "app", "aud"));
    }

    @Test
    void casoDeAutorRemovidoNaoExecuta() throws Exception {
        String token = createAudited("aud1", "");
        assertNotEquals(200, post("/sql", token, "DELETE c").statusCode());
        String hash = pendingHash();
        ok(root, "DROP USER aud1");
        assertNotEquals(200, approve(hash).statusCode());
        assertEquals(2, rows());
    }
}
