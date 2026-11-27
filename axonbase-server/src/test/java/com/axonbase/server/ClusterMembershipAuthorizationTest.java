package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mudar os membros do cluster vale para o nó inteiro: exige administrador de escopo ROOT. */
class ClusterMembershipAuthorizationTest {

    private static final String BODY = "{\"node\":\"n2\",\"address\":\"127.0.0.1:9090\"}";

    private AxonServer server;
    private SecurityHttp http;

    @BeforeEach
    void setUp() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("loja", "ops");
        ds.authCatalog().defineUser("chefebanco", AuthCatalog.Scope.DATABASE, "loja", "ops", "pw",
            List.of("admin"), List.of());
        ds.authCatalog().defineUser("chefens", AuthCatalog.Scope.NAMESPACE, "loja", null, "pw",
            List.of("admin"), List.of());
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        http = new SecurityHttp(server.port());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private int status(String path, String token) throws Exception {
        return http.post(path, BODY, SecurityHttp.bearer(token, "loja", "ops")).statusCode();
    }

    @Test
    void adminDeBancoNaoMudaOsMembros() throws Exception {
        String token = http.signin("loja", "ops", "chefebanco", "pw");

        assertEquals(403, status("/admin/cluster/join", token));
        assertEquals(403, status("/admin/cluster/leave", token));
    }

    @Test
    void adminDeNamespaceNaoMudaOsMembros() throws Exception {
        String token = http.signin("loja", "ops", "chefens", "pw");

        assertEquals(403, status("/admin/cluster/join", token));
        assertEquals(403, status("/admin/cluster/leave", token));
    }

    @Test
    void rootPassaDaAutorizacao() throws Exception {
        // sem cluster configurado a rota responde 503 depois de autorizar
        assertEquals(503, status("/admin/cluster/join", http.rootToken("pw")));
    }
}
