package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.audit.AuditCase;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.SagaLedger;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API de gestão /admin/v1 (E0, P3). */
class AdminV1Test {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER).build();

    private Datastore ds;
    private AxonServer server;
    private int port;
    private String root;
    private String viewer;
    private String operator;
    private String dbUser;

    @BeforeEach
    void subir() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.createDatabase("app", "other");
        ds.authCatalog().defineUser("visao", AuthCatalog.Scope.ROOT, null, null, "pw", List.of("viewer"), List.of());
        ds.authCatalog().defineUser("oper", AuthCatalog.Scope.ROOT, null, null, "pw", List.of("operator"), List.of());
        ds.authCatalog().defineUser("dbu", AuthCatalog.Scope.DATABASE, "app", "main", "pw", List.of("admin"), List.of());
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        port = server.port();
        root = signin("root");
        viewer = signin("visao");
        operator = signin("oper");
        dbUser = rpcSignin("dbu", "app", "main");
    }

    @AfterEach
    void descer() throws Exception {
        server.stop();
    }

    // ---------------- helpers ----------------

    private String signin(String user) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"" + user + "\",\"pass\":\"" + (user.equals("root") ? "pw" : "pw") + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        return AxonJson.parseDocument(r.body()).asObject().get("token").asString();
    }

    private String rpcSignin(String user, String ns, String db) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/rpc"))
            .header("Axon-Ns", ns).header("Axon-Db", db)
            .POST(HttpRequest.BodyPublishers.ofString("{\"id\":1,\"method\":\"signin\",\"params\":[{\"user\":\""
                + user + "\",\"pass\":\"pw\"}]}")).build(), HttpResponse.BodyHandlers.ofString());
        return AxonJson.parseDocument(r.body()).asObject().get("result").asString();
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/admin/v1" + path))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return call("GET", path, token, null);
    }

    private static AxonValue json(HttpResponse<String> r) {
        return AxonJson.parseDocument(r.body());
    }

    private static String errorCode(HttpResponse<String> r) {
        return json(r).asObject().get("error").asObject().get("code").asString();
    }

    private static Session session(String ns, String db) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        return s;
    }

    // ---------------- testes ----------------

    @Test
    void semTokenDa401JsonEErroDesconhecidoDa404() throws Exception {
        HttpResponse<String> r = get("/info", null);
        assertEquals(401, r.statusCode());
        assertEquals("UNAUTHENTICATED", errorCode(r));
        HttpResponse<String> nf = get("/nao-existe", root);
        assertEquals(404, nf.statusCode());
        assertEquals("NOT_FOUND", errorCode(nf));
        HttpResponse<String> bad = call("POST", "/users", root, "{nao json");
        assertEquals(400, bad.statusCode());
        assertEquals("BAD_REQUEST", errorCode(bad));
    }

    @Test
    void infoMostraVersaoEModoSemSegredos() throws Exception {
        HttpResponse<String> r = get("/info", viewer);
        assertEquals(200, r.statusCode());
        var o = json(r).asObject();
        assertEquals(BuildInfo.version(), o.get("version").asString());
        assertEquals("standalone", o.get("mode").asString());
        assertTrue(o.get("uptimeSeconds").isNumber());
        assertFalse(r.body().contains("secret-testing"), r.body());
        assertFalse(o.get("config").asObject().containsKey("secret"));
        assertFalse(o.get("config").asObject().containsKey("password"));
        assertEquals(403, get("/info", dbUser).statusCode());
    }

    @Test
    void namespacesETabelasComCamposEIndices() throws Exception {
        ds.execute("DEFINE TABLE pessoa SCHEMAFULL", session("app", "main"), null);
        ds.execute("DEFINE FIELD nome ON TABLE pessoa TYPE string", session("app", "main"), null);
        ds.execute("DEFINE INDEX idx_nome ON TABLE pessoa COLUMNS nome UNIQUE", session("app", "main"), null);

        var ns = json(get("/namespaces", viewer)).asArray();
        assertEquals("app", ns.get(0).asObject().get("name").asString());
        assertTrue(ns.get(0).asObject().get("databases").asArray().size() >= 2);

        HttpResponse<String> t = get("/databases/app/main/tables", viewer);
        assertEquals(200, t.statusCode());
        var table = json(t).asArray().get(0).asObject();
        assertEquals("pessoa", table.get("name").asString());
        assertEquals("SCHEMAFULL", table.get("schemaMode").asString());
        assertEquals("nome", table.get("fields").asArray().get(0).asObject().get("name").asString());
        assertEquals("string", table.get("fields").asArray().get(0).asObject().get("type").asString());
        assertEquals("idx_nome", table.get("indexes").asArray().get(0).asObject().get("name").asString());

        // usuário de banco vê o próprio banco, mas não outro
        assertEquals(200, get("/databases/app/main/tables", dbUser).statusCode());
        assertEquals(403, get("/databases/app/other/tables", dbUser).statusCode());
        assertEquals(404, get("/databases/app/inexistente/tables", root).statusCode());
    }

    @Test
    void usuariosCriarListarEApagarComPapeis() throws Exception {
        String novo = "{\"name\":\"ana\",\"scope\":\"DATABASE\",\"ns\":\"app\",\"db\":\"main\",\"password\":\"p\\\"w\","
            + "\"roles\":[\"editor\"]}";
        assertEquals(403, call("POST", "/users", viewer, novo).statusCode());
        assertEquals(403, call("POST", "/users", operator, novo).statusCode());
        assertEquals(201, call("POST", "/users", root, novo).statusCode());
        assertNotNull(ds.authCatalog().verify("ana", "p\"w", "app", "main"));

        HttpResponse<String> list = get("/users", viewer);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"ana\""));
        assertFalse(list.body().toLowerCase().contains("hash"), list.body());
        assertTrue(list.body().contains("\"auth\":\"password\""));

        assertEquals(204, call("DELETE", "/users/DATABASE/ana?ns=app&db=main", root, null).statusCode());
        assertEquals(404, call("DELETE", "/users/DATABASE/ana?ns=app&db=main", root, null).statusCode());
        assertEquals(400, call("POST", "/users", root, "{\"name\":\"x y\",\"scope\":\"ROOT\",\"password\":\"p\"}").statusCode());
    }

    @Test
    void tokensDeServicoCriarUsarListarERevogar() throws Exception {
        HttpResponse<String> created = call("POST", "/service-tokens", root,
            "{\"name\":\"agente\",\"scope\":\"ROOT\",\"roles\":[\"viewer\"],\"expiresInDays\":7}");
        assertEquals(201, created.statusCode());
        var o = json(created).asObject();
        String token = o.get("token").asString();
        String id = o.get("id").asString();
        assertTrue(token.startsWith("axsvc_" + id + "_"));

        assertEquals(200, get("/info", token).statusCode());
        // viewer não cria usuário
        assertEquals(403, call("POST", "/users", token,
            "{\"name\":\"z\",\"scope\":\"ROOT\",\"password\":\"p\"}").statusCode());
        // listagem sem segredo
        HttpResponse<String> list = get("/service-tokens", root);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains(id));
        assertFalse(list.body().contains(token.substring(token.lastIndexOf('_') + 1)), list.body());
        assertTrue(list.body().contains("lastUsedAt"));

        assertEquals(204, call("DELETE", "/service-tokens/" + id, root, null).statusCode());
        assertEquals(401, get("/info", token).statusCode());
        assertEquals(404, call("DELETE", "/service-tokens/inexistente", root, null).statusCode());
    }

    @Test
    void clusterStandaloneEComMembros() throws Exception {
        var standalone = json(get("/cluster", viewer)).asObject();
        assertEquals("standalone", standalone.get("mode").asString());
        assertEquals(0, standalone.get("members").asArray().size());

        var dir = Files.createTempDirectory("adm-v1");
        ClusterRuntime rt = new ClusterRuntime(new ClusterConfig("n1", "g",
            new InetSocketAddress("127.0.0.1", 0), List.of()), dir);
        try {
            server.clusterStatus(rt);
            for (int i = 0; i < 80 && rt.role() != ClusterRole.LEADER; i++) {
                Thread.sleep(60);
            }
            assertTrue(rt.addPeer("n2", new InetSocketAddress("127.0.0.1", 9090)));
            var o = json(get("/cluster", viewer)).asObject();
            assertEquals("cluster", o.get("mode").asString());
            assertEquals("n1", o.get("nodeId").asString());
            assertEquals("LEADER", o.get("role").asString());
            assertTrue(o.containsKey("term") && o.containsKey("commitIndex") && o.containsKey("quorum"));
            var member = o.get("members").asArray().get(0).asObject();
            assertEquals("n2", member.get("node").asString());
            assertEquals("127.0.0.1:9090", member.get("address").asString());
            assertTrue(member.containsKey("lagEntries") && member.containsKey("lastContactMs"));
            assertTrue(member.get("active").isBool());
        } finally {
            rt.close();
        }
    }

    @Test
    void seguidorRecusaEscritaComNotLeader() throws Exception {
        ClusterStatusProvider follower = () -> new ClusterStatusProvider.Status("n2", "g", "n1",
            "10.0.0.1:8000", ClusterRole.FOLLOWER, 3, 9, 1, 3, 2);
        server.clusterStatus(follower);
        HttpResponse<String> r = call("POST", "/users", root,
            "{\"name\":\"z\",\"scope\":\"ROOT\",\"password\":\"p\"}");
        assertEquals(503, r.statusCode());
        var err = json(r).asObject().get("error").asObject();
        assertEquals("NOT_LEADER", err.get("code").asString());
        assertEquals("10.0.0.1:8000", err.get("leaderAddress").asString());
        // leitura continua
        assertEquals(200, get("/users", root).statusCode());
    }

    @Test
    void sagasDefinicoesListagemDetalheECancelamento() throws Exception {
        ds.defineSaga("pagamento", new Datastore.SagaDef("pagamento", List.of("estoque", "cobranca")));
        var defs = json(get("/saga-definitions", viewer)).asArray();
        assertEquals("pagamento", defs.get(0).asObject().get("name").asString());
        assertEquals(2, defs.get(0).asObject().get("links").asArray().size());

        SagaLedger ledger = new SagaLedger(ds);
        ledger.ensureTables();
        ds.defineSaga("simples", new Datastore.SagaDef("simples", List.of()));
        ledger.beginSaga("simples", "corr1");

        var sagas = json(get("/sagas?status=RUNNING", viewer)).asArray();
        assertEquals(1, sagas.size());
        assertEquals("corr1", sagas.get(0).asObject().get("correlationId").asString());
        assertEquals("RUNNING", sagas.get(0).asObject().get("status").asString());
        assertEquals(0, json(get("/sagas?status=COMMITTED", viewer)).asArray().size());

        var detail = json(get("/sagas/corr1", viewer)).asObject();
        assertEquals("simples", detail.get("name").asString());
        assertTrue(detail.get("steps").isArray());
        assertEquals(404, get("/sagas/nao-existe", viewer).statusCode());

        assertEquals(403, call("POST", "/sagas/simples/corr1/cancel", viewer, null).statusCode());
        HttpResponse<String> cancel = call("POST", "/sagas/simples/corr1/cancel", operator, null);
        assertEquals(200, cancel.statusCode(), cancel.body());
        assertEquals("FAILED", json(cancel).asObject().get("status").asString());
    }

    @Test
    void auditoriaCasosDecisaoBloqueadosEDefinicoes() throws Exception {
        var cat = ds.requireDatabase(session("app", "main")).auditCatalog();
        cat.addCase("h1", new AuditCase("h1", "joao", "DELETE t", "aud", "WARNING", System.currentTimeMillis()));
        cat.addCase("h2", new AuditCase("h2", "maria", "DROP t", "aud", "DANGER", System.currentTimeMillis()));
        cat.blockUser("maria", "aud", "DROP t", "perigo", "sistema");

        var cases = json(get("/audit/cases?status=PENDING", viewer)).asArray();
        assertEquals(2, cases.size());
        assertTrue(get("/audit/cases", viewer).body().contains("\"classification\":\"DANGER\""));
        var blocked = json(get("/audit/blocked-users", viewer)).asArray();
        assertEquals("maria", blocked.get(0).asObject().get("user").asString());

        String negar = "{\"decision\":\"DENIED\",\"reason\":\"nao pode\"}";
        assertEquals(403, call("POST", "/audit/cases/h1/decision", viewer, negar).statusCode());
        assertEquals(400, call("POST", "/audit/cases/h1/decision", operator, "{\"decision\":\"TALVEZ\",\"reason\":\"x\"}").statusCode());
        assertEquals(404, call("POST", "/audit/cases/zzz/decision", operator, negar).statusCode());
        HttpResponse<String> ok = call("POST", "/audit/cases/h1/decision", operator, negar);
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals("DENIED", cat.getCase("h1").status());
        assertEquals(1, json(get("/audit/cases?status=PENDING", viewer)).asArray().size());
        // já decidido
        assertEquals(409, call("POST", "/audit/cases/h1/decision", operator, negar).statusCode());

        assertEquals(403, call("POST", "/audit/blocked-users/maria/unblock", viewer, null).statusCode());
        assertEquals(204, call("POST", "/audit/blocked-users/maria/unblock", operator, null).statusCode());
        assertFalse(cat.isBlocked("maria"));
        assertEquals(404, call("POST", "/audit/blocked-users/maria/unblock", operator, null).statusCode());

        assertEquals(200, get("/audit/definitions?ns=app&db=main", viewer).statusCode());
        assertEquals(0, json(get("/audit/definitions?ns=app&db=main", viewer)).asArray().size());
        assertEquals(200, get("/audit/operations?ns=app&db=main", viewer).statusCode());
        assertEquals(400, get("/audit/operations", viewer).statusCode());
    }

    @Test
    void regrasDeDadosPorBancoComEscopo() throws Exception {
        assertEquals(200, get("/data-rules?ns=app&db=main", viewer).statusCode());
        assertEquals(0, json(get("/data-rules?ns=app&db=main", viewer)).asArray().size());
        assertEquals(200, get("/data-rules?ns=app&db=main", dbUser).statusCode());
        assertEquals(403, get("/data-rules?ns=app&db=other", dbUser).statusCode());
        assertEquals(400, get("/data-rules", viewer).statusCode());
    }

    @Test
    void truststoresListarEnviarMultipartERemover() throws Exception {
        assertEquals(0, json(get("/truststores", viewer)).asArray().size());
        assertEquals(0, json(get("/certificates/expiring?days=30", viewer)).asArray().size());

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ByteArrayOutputStream jks = new ByteArrayOutputStream();
        ks.store(jks, "senha123".toCharArray());

        String boundary = "----axtest";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "name", "meus");
        part(body, boundary, "password", "senha123");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"t.jks\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(jks.toByteArray());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> viewerTry = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/admin/v1/truststores"))
            .header("Authorization", "Bearer " + viewer)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, viewerTry.statusCode());

        HttpResponse<String> up = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/admin/v1/truststores"))
            .header("Authorization", "Bearer " + root)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, up.statusCode(), up.body());

        var list = json(get("/truststores", viewer)).asArray();
        assertEquals("meus", list.get(0).asObject().get("name").asString());
        assertTrue(list.get(0).asObject().containsKey("loadedAt"));
        assertTrue(list.get(0).asObject().get("certificates").isArray());

        assertEquals(204, call("DELETE", "/truststores/meus", root, null).statusCode());
        assertEquals(404, call("DELETE", "/truststores/meus", root, null).statusCode());
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
            + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
