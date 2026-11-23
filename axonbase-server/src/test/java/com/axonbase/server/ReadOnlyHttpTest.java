package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Somente leitura, teto de papel (Axon-Role-Cap) e escopo do USE pelos caminhos HTTP. */
class ReadOnlyHttpTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Datastore ds;
    private AxonServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("loja", "ops");
        ds.createDatabase("outro", "base");
        Session s = Session.create();
        s.namespace("loja");
        s.database("ops");
        ds.execute("CREATE itens:1 SET v = 1", s, null);
        Session o = Session.create();
        o.namespace("outro");
        o.database("base");
        ds.execute("CREATE secreto:1 SET v = 'x'", o, null);
        ds.authCatalog().defineUser("visao", AuthCatalog.Scope.DATABASE, "loja", "ops", "pw",
            List.of("viewer"), List.of());
        ds.authCatalog().defineUser("editor", AuthCatalog.Scope.DATABASE, "loja", "ops", "pw",
            List.of("operator"), List.of());
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        port = server.port();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private String signin(String user) throws Exception {
        if (user.equals("root")) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            return AxonJson.parseDocument(r.body()).asObject().get("token").asString();
        }
        // token de escopo DATABASE com papel explícito, como o de um token de serviço
        String roles = user.equals("visao") ? "viewer" : "operator";
        return com.axonbase.server.auth.Jwt.sign("secret-testing", java.util.Map.of(
            "id", user, "jti", "j-" + user, "typ", "access", "scope", "DATABASE",
            "ns", "loja", "db", "ops", "roles", roles), 3600_000L).value();
    }

    private HttpResponse<String> call(String path, String token, String cap, String ns, String db, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Axon-Ns", ns).header("Axon-Db", db);
        if (cap != null) {
            b.header("Axon-Role-Cap", cap);
        }
        return HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sql(String token, String cap, String body) throws Exception {
        return call("/sql", token, cap, "loja", "ops", body);
    }

    private int itens() {
        Session s = Session.create();
        s.namespace("loja");
        s.database("ops");
        return ds.execute("SELECT * FROM itens", s, null).asArray().size();
    }

    @Test
    void viewerNaoEscrevePorSql() throws Exception {
        String t = signin("visao");
        assertEquals(400, sql(t, null, "CREATE itens:2 SET v = 2").statusCode());
        assertEquals(400, sql(t, null, "UPDATE itens:1 SET v = 9").statusCode());
        assertEquals(400, sql(t, null, "DELETE itens:1").statusCode());
        assertEquals(400, sql(t, null, "RETURN kv::set('k', 1)").statusCode());
        assertEquals(1, itens());
        assertEquals(200, sql(t, null, "SELECT * FROM itens").statusCode());
    }

    @Test
    void viewerNaoEscrevePorRpcHttp() throws Exception {
        String t = signin("visao");
        HttpResponse<String> r = call("/rpc", t, null, "loja", "ops",
            "{\"id\":1,\"method\":\"query\",\"params\":[\"DELETE itens:1\"]}");
        assertTrue(r.body().contains("error"), r.body());
        assertEquals(1, itens());
    }

    @Test
    void viewerNaoEscrevePorTableEImport() throws Exception {
        String t = signin("visao");
        HttpResponse<String> r = call("/table/itens", t, null, "loja", "ops", "{\"v\":3}");
        assertTrue(r.statusCode() >= 400, r.body());
        assertTrue(call("/import", t, null, "loja", "ops", "DELETE itens:1;").statusCode() >= 400);
        assertEquals(1, itens());
    }

    @Test
    void operatorSemTetoEscreve() throws Exception {
        String t = signin("editor");
        assertEquals(200, sql(t, null, "CREATE itens:3 SET v = 3").statusCode());
        assertEquals(2, itens());
    }

    @Test
    void tetoViewerImpedeEscritaDeRoot() throws Exception {
        String root = signin("root");
        assertEquals(400, sql(root, "viewer", "DELETE itens:1").statusCode());
        assertEquals(1, itens());
        assertEquals(200, sql(root, "viewer", "SELECT * FROM itens").statusCode());
        assertEquals(200, sql(root, null, "CREATE itens:4 SET v = 4").statusCode());
    }

    @Test
    void tetoOperatorImpedeCreateUserMasMantemDados() throws Exception {
        String root = signin("root");
        assertEquals(400, sql(root, "operator",
            "DEFINE USER x ON DATABASE PASSWORD \"p\"").statusCode());
        assertEquals(200, sql(root, "operator", "CREATE itens:5 SET v = 5").statusCode());
        assertEquals(200, sql(root, null, "DEFINE USER y ON DATABASE PASSWORD \"p\"").statusCode());
    }

    @Test
    void valorDesconhecidoDoTetoDevolve400() throws Exception {
        String root = signin("root");
        assertEquals(400, sql(root, "admin", "SELECT * FROM itens").statusCode());
        assertEquals(400, sql(root, "xyz", "SELECT * FROM itens").statusCode());
    }

    @Test
    void useNaoTrocaParaOutroBancoPorSql() throws Exception {
        String t = signin("editor");
        HttpResponse<String> r = sql(t, null, "USE outro base; SELECT * FROM secreto");
        assertEquals(400, r.statusCode());
        assertTrue(!r.body().contains("\"x\""), r.body());
    }
}
