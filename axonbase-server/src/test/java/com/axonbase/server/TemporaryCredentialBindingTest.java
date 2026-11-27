package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A credencial temporária vale para o usuário e o escopo do primeiro uso, e só por 60 segundos. */
class TemporaryCredentialBindingTest {

    private static final String FINGERPRINT = "SHA256:certificado";

    private RpcDispatcher rpc;
    private String credencial;

    @BeforeEach
    void setUp() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.createDatabase("app", "outro");
        for (String db : List.of("main", "outro")) {
            ds.authCatalog().defineCertificateUser("12345678901", AuthCatalog.Scope.DATABASE, "app", db,
                "icp_brasil", FINGERPRINT, List.of("viewer"), List.of());
        }
        AuthService auth = new AuthService("segredo", new UserStore(ds.authCatalog()));
        rpc = new RpcDispatcher(ds, auth, true);
        credencial = auth.issueTemporaryCredential("12345678901", "icp_brasil", FINGERPRINT);
    }

    private String entrar(String usuario, String db) {
        Session s = Session.create();
        s.namespace("app");
        s.database(db);
        return rpc.dispatch("{\"id\":1,\"method\":\"signin\",\"params\":[{\"user\":\"" + usuario
            + "\",\"pass\":\"" + credencial + "\"}]}", s);
    }

    @Test
    void mesmoUsuarioEEscopoReconectamDentroDoPrazo() {
        assertFalse(entrar("12345678901", "main").contains("\"error\""));
        assertFalse(entrar("12345678901", "main").contains("\"error\""));
    }

    @Test
    void outroUsuarioNaoReaproveitaACredencialJaUsada() {
        assertFalse(entrar("12345678901", "main").contains("\"error\""));

        String resposta = entrar("qualquer", "main");

        assertTrue(resposta.contains("\"error\""), resposta);
    }

    @Test
    void outroBancoNaoReaproveitaACredencialJaUsada() {
        assertFalse(entrar("12345678901", "main").contains("\"error\""));

        String resposta = entrar("12345678901", "outro");

        assertTrue(resposta.contains("\"error\""), resposta);
    }

    /** Espera o prazo real da credencial: cerca de um minuto. */
    @Test
    void depoisDeSessentaSegundosACredencialNaoVale() throws InterruptedException {
        assertFalse(entrar("12345678901", "main").contains("\"error\""));
        Thread.sleep(61_000);

        String resposta = entrar("12345678901", "main");

        assertTrue(resposta.contains("\"error\""), resposta);
    }
}
