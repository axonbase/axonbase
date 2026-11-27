package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** signup cria identidade: exige administrador que cubra o destino, sessão que escreva e nome ainda livre. */
class SignupAuthorizationHttpTest {

    private Datastore ds;
    private AxonServer server;
    private SecurityHttp http;

    @BeforeEach
    void setUp() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("loja", "ops");
        ds.createDatabase("outro", "base");
        usuario("editor", "loja", "ops", "editor");
        usuario("chefe", "loja", "ops", "admin");
        usuario("dono", "outro", "base", "admin");
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        http = new SecurityHttp(server.port());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private void usuario(String nome, String ns, String db, String papel) {
        ds.authCatalog().defineUser(nome, AuthCatalog.Scope.DATABASE, ns, db, "pw", List.of(papel), List.of());
    }

    private String signup(String token, String user, String ns, String db, String... extra) throws Exception {
        String[] headers = new String[6 + extra.length];
        System.arraycopy(SecurityHttp.bearer(token, "loja", "ops"), 0, headers, 0, 6);
        System.arraycopy(extra, 0, headers, 6, extra.length);
        return http.post("/rpc", "{\"id\":1,\"method\":\"signup\",\"params\":[{\"user\":\"" + user
            + "\",\"pass\":\"nova\",\"ns\":\"" + ns + "\",\"db\":\"" + db + "\"}]}", headers).body();
    }

    @Test
    void semPapelDeAdminNaoCriaUsuario() throws Exception {
        String r = signup(http.signin("loja", "ops", "editor", "pw"), "intruso", "loja", "ops");

        assertTrue(r.contains("\"error\""), r);
        assertNull(ds.authCatalog().user("intruso", "loja", "ops"));
    }

    @Test
    void adminDeUmBancoNaoCriaNemTrocaSenhaEmOutro() throws Exception {
        String chefe = http.signin("loja", "ops", "chefe", "pw");

        String criar = signup(chefe, "intruso", "outro", "base");
        String trocar = signup(chefe, "dono", "outro", "base");

        assertTrue(criar.contains("\"error\""), criar);
        assertTrue(trocar.contains("\"error\""), trocar);
        assertNull(ds.authCatalog().user("intruso", "outro", "base"));
        assertNotNull(ds.authCatalog().verify("dono", "pw", "outro", "base"));
    }

    @Test
    void usuarioExistenteNaoEhSobrescrito() throws Exception {
        String r = signup(http.rootToken("pw"), "dono", "outro", "base");

        assertTrue(r.contains("\"error\""), r);
        AuthCatalog.User dono = ds.authCatalog().verify("dono", "pw", "outro", "base");
        assertNotNull(dono);
        assertEquals(List.of("admin"), dono.roles());
    }

    @Test
    void sessaoSomenteLeituraNaoCria() throws Exception {
        String r = signup(http.rootToken("pw"), "intruso", "loja", "ops", "Axon-Role-Cap", "viewer");

        assertTrue(r.contains("\"error\""), r);
        assertNull(ds.authCatalog().user("intruso", "loja", "ops"));
    }

    @Test
    void adminDoBancoCriaUsuarioNovo() throws Exception {
        String r = signup(http.signin("loja", "ops", "chefe", "pw"), "novo", "loja", "ops");

        assertFalse(r.contains("\"error\""), r);
        assertNotNull(ds.authCatalog().verify("novo", "nova", "loja", "ops"));
    }
}
