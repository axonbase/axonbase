package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.ServiceTokenStore;
import com.axonbase.server.auth.UserStore;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Segurança do núcleo (E0): root configurável, defaults inseguros, tokens de serviço, RBAC HTTP. */
class CoreSecurityTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER).build();

    // ---------------- configuração ----------------

    @Test
    void rootUserEPasswordVemDoAmbiente() throws Exception {
        ServerConfig c = ServerConfig.load(Path.of("nao-existe.conf"),
            Map.of("AXON_ROOT_USER", "admin", "AXON_ROOT_PASSWORD", "s3gredo!"));
        assertEquals("admin", c.user());
        assertEquals("s3gredo!", c.password());
        ServerConfig padrao = ServerConfig.load(Path.of("nao-existe.conf"), Map.of());
        assertEquals("root", padrao.user());
        assertEquals("root", padrao.password());
    }

    @Test
    void recusaDefaultsInsegurosForaDoLoopback() {
        ServerConfig base = ServerConfig.defaults();
        // loopback: tudo bem
        assertNull(ServerConfig.insecureDefaultsProblem(base, Map.of()));
        ServerConfig exposto = withBind(base, "0.0.0.0");
        assertNotNull(ServerConfig.insecureDefaultsProblem(exposto, Map.of()));
        // escape explícito
        assertNull(ServerConfig.insecureDefaultsProblem(exposto,
            Map.of("AXON_ALLOW_INSECURE_DEFAULTS", "true")));
        // senha e segredo fortes
        ServerConfig forte = new ServerConfig(exposto.path(), "segredo-forte-de-verdade", "root", "outra-senha",
            exposto.port(), "0.0.0.0", true, "", "", "", "", 30000L, 300000L, 0, "", "", "", "",
            10000L, 4, 100, "", "", "", "", "", 0L, "", 10, 0, "pt-BR", "", "", "", "",
            false, "auth", false, 65536);
        assertNull(ServerConfig.insecureDefaultsProblem(forte, Map.of()));
        // sem autenticação exigida o servidor já é aberto por escolha
        ServerConfig semAuth = new ServerConfig(exposto.path(), exposto.secret(), "root", "root",
            exposto.port(), "0.0.0.0", false, "", "", "", "", 30000L, 300000L, 0, "", "", "", "",
            10000L, 4, 100, "", "", "", "", "", 0L, "", 10, 0, "pt-BR", "", "", "", "",
            false, "auth", false, 65536);
        assertNull(ServerConfig.insecureDefaultsProblem(semAuth, Map.of()));
    }

    @Test
    void recusaSenhaRootEmBrancoForaDoLoopback() {
        ServerConfig base = ServerConfig.defaults();
        ServerConfig exposto = new ServerConfig(base.path(), "segredo-forte-de-verdade", "root", "",
            base.port(), "0.0.0.0", true, "", "", "", "", 30000L, 300000L, 0, "", "", "", "",
            10000L, 4, 100, "", "", "", "", "", 0L, "", 10, 0, "pt-BR", "", "", "", "",
            false, "auth", false, 65536);
        assertNotNull(ServerConfig.insecureDefaultsProblem(exposto, Map.of()));
        ServerConfig espacos = new ServerConfig(base.path(), "segredo-forte-de-verdade", "root", "   ",
            base.port(), "0.0.0.0", true, "", "", "", "", 30000L, 300000L, 0, "", "", "", "",
            10000L, 4, 100, "", "", "", "", "", 0L, "", 10, 0, "pt-BR", "", "", "", "",
            false, "auth", false, 65536);
        assertNotNull(ServerConfig.insecureDefaultsProblem(espacos, Map.of()));
    }

    private static ServerConfig withBind(ServerConfig b, String bind) {
        return new ServerConfig(b.path(), b.secret(), b.user(), b.password(), b.port(), bind,
            b.requireAuth(), "", "", "", "", 30000L, 300000L, 0, "", "", "", "",
            10000L, 4, 100, "", "", "", "", "", 0L, "", 10, 0, "pt-BR", "", "", "", "",
            false, "auth", false, 65536);
    }

    // ---------------- tokens de serviço ----------------

    @Test
    void tokenDeServicoGuardaSoHashEAutenticaPorJti() {
        Datastore ds = Datastore.memory();
        AuthService auth = new AuthService("s", new UserStore(ds.authCatalog()));
        ServiceTokenStore store = auth.serviceTokens();

        var created = store.create("agente", "ROOT", null, null, List.of("operator"), 30);
        assertTrue(created.token().startsWith("axsvc_" + created.id() + "_"));

        Map<String, Object> claims = auth.claims(created.token());
        assertNotNull(claims);
        assertEquals("operator", claims.get("roles"));
        assertEquals("ROOT", claims.get("scope"));
        assertEquals(created.id(), claims.get("jti"));

        // o segredo não aparece em nenhuma listagem
        String secret = created.token().substring(("axsvc_" + created.id() + "_").length());
        assertFalse(store.list().toString().contains(secret));
        assertNotNull(store.list().get(0).lastUsedAt());

        // segredo errado
        assertNull(auth.claims("axsvc_" + created.id() + "_errado"));
        assertNull(auth.claims("axsvc_inexistente_xxx"));

        // revogado dá nulo
        assertTrue(store.revoke(created.id()));
        assertNull(auth.claims(created.token()));
    }

    @Test
    void tokenDeServicoExpiradoNaoAutentica() {
        Datastore ds = Datastore.memory();
        AuthService auth = new AuthService("s", new UserStore(ds.authCatalog()));
        var created = auth.serviceTokens().create("curto", "ROOT", null, null, List.of("viewer"), -1);
        assertNull(auth.claims(created.token()));
    }

    @Test
    void tokenDeServicoSobreviveAReinicioQuandoPersistido() {
        Datastore ds = Datastore.memory();
        AuthService a1 = new AuthService("s", new UserStore(ds.authCatalog()));
        a1.serviceTokens().persistTo(ds);
        var created = a1.serviceTokens().create("persistente", "ROOT", null, null, List.of("admin"), 10);

        AuthService a2 = new AuthService("s", new UserStore(ds.authCatalog()));
        a2.serviceTokens().persistTo(ds);
        assertNotNull(a2.claims(created.token()));
    }

    @Test
    void jwtRevogadoPorJtiDeixaDeValer() {
        Datastore ds = Datastore.memory();
        AuthService auth = new AuthService("s", new UserStore(ds.authCatalog()));
        auth.ensureRoot("root", "pw");
        String jwt = auth.signin("root", "pw");
        Map<String, Object> claims = auth.claims(jwt);
        assertNotNull(claims);
        auth.revokeJwt(String.valueOf(claims.get("jti")), ((Number) claims.get("exp")).longValue());
        assertNull(auth.claims(jwt));
    }

    // ---------------- HTTP ----------------

    private static String signin(int port, String user, String pass) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"" + user + "\",\"pass\":\"" + pass + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        return AxonJson.parseDocument(r.body()).asObject().get("token").asString();
    }

    private static HttpResponse<String> post(int port, String path, String token, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void adminClusterEShardMoveExigemAdminEShardMoveDa501() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        ds.authCatalog().defineUser("visao", AuthCatalog.Scope.ROOT, null, null, "pw",
            List.of("viewer"), List.of());
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            String viewer = signin(server.port(), "visao", "pw");
            String root = signin(server.port(), "root", "pw");
            String body = "{\"node\":\"n2\",\"address\":\"127.0.0.1:9090\",\"ns\":\"n\",\"db\":\"d\",\"to\":\"n2\"}";

            assertEquals(403, post(server.port(), "/admin/cluster/join", viewer, body).statusCode());
            assertEquals(403, post(server.port(), "/admin/cluster/leave", viewer, body).statusCode());
            assertEquals(403, post(server.port(), "/admin/shard/move", viewer, body).statusCode());

            // admin passa da autorização (sem cluster configurado dá 503)
            assertEquals(503, post(server.port(), "/admin/cluster/join", root, body).statusCode());
            assertEquals(501, post(server.port(), "/admin/shard/move", root, body).statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void healthContinuaPublico() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
        } finally {
            server.stop();
        }
    }
}
