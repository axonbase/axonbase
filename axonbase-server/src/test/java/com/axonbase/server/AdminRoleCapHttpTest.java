package com.axonbase.server;

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

/** Axon-Role-Cap rebaixa também a API de gestão, e registrar truststore exige o papel de administrador. */
class AdminRoleCapHttpTest {

    private static final String TOKEN_BODY = "{\"name\":\"cap\"}";
    private static final String TRUSTSTORE_BODY =
        "{\"name\":\"x\",\"path\":\"/nao/existe.jks\",\"password\":\"segredo\"}";

    private AxonServer server;
    private SecurityHttp http;
    private String root;

    @BeforeEach
    void setUp() throws Exception {
        Datastore ds = Datastore.memory();
        ds.authCatalog().defineUser("visao", AuthCatalog.Scope.ROOT, null, null, "pw",
            List.of("viewer"), List.of());
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        http = new SecurityHttp(server.port());
        root = http.rootToken("pw");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private int post(String path, String token, String cap, String body) throws Exception {
        return cap == null
            ? http.post(path, body, "Authorization", "Bearer " + token).statusCode()
            : http.post(path, body, "Authorization", "Bearer " + token, "Axon-Role-Cap", cap).statusCode();
    }

    private HttpResponse<String> get(String path, String token, String cap) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Authorization", "Bearer " + token).header("Axon-Role-Cap", cap).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void tetoViewerOuOperatorNaoCriaTokenDeServico() throws Exception {
        assertEquals(403, post("/admin/v1/service-tokens", root, "viewer", TOKEN_BODY));
        assertEquals(403, post("/admin/v1/service-tokens", root, "operator", TOKEN_BODY));
        HttpResponse<String> list = get("/admin/v1/service-tokens", root, "viewer");
        assertEquals(403, list.statusCode(), list.body());
    }

    @Test
    void tetoViewerAindaLeASituacaoDoNo() throws Exception {
        HttpResponse<String> info = get("/admin/v1/info", root, "viewer");

        assertEquals(200, info.statusCode(), info.body());
        AxonJson.parseDocument(info.body());
    }

    @Test
    void semTetoORootContinuaCriandoToken() throws Exception {
        assertEquals(201, post("/admin/v1/service-tokens", root, null, TOKEN_BODY));
    }

    @Test
    void rootViewerNaoRegistraTruststore() throws Exception {
        String visao = AxonJson.parseDocument(http.post("/signin", "{\"user\":\"visao\",\"pass\":\"pw\"}").body())
            .asObject().get("token").asString();

        assertEquals(403, post("/admin/truststores", visao, null, TRUSTSTORE_BODY));
        assertEquals(403, post("/admin/truststores", root, "viewer", TRUSTSTORE_BODY));
    }
}
