package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.CertificateChallengeService;
import com.axonbase.server.auth.TrustStoreRegistry;
import com.axonbase.server.auth.UserStore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** certificate.complete: o usuário vem do certificado; sem identidade no certificado só vale usuário com FINGERPRINT. */
class CertificateIdentityRpcTest {

    @TempDir
    static Path dir;

    private static TestCertificates.Identity pessoa;
    private static TestCertificates.Identity semDocumento;
    private static Path truststore;

    private Datastore ds;
    private RpcDispatcher rpc;

    @BeforeAll
    static void certificados() throws Exception {
        TestCertificates ca = new TestCertificates(dir);
        pessoa = ca.issue("pessoa", "CN=PESSOA A:11111111111");
        semDocumento = ca.issue("semdoc", "CN=SEM DOCUMENTO");
        truststore = ca.truststore();
    }

    @BeforeEach
    void setUp() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("app", "main");
        usuario("11111111111", "main", null);
        usuario("99999999999", "main", null);
        usuario("22222222222", "main", TestCertificates.fingerprint(semDocumento.certificate()));
        usuario("22222222222", "outro", null);
        TrustStoreRegistry stores = new TrustStoreRegistry();
        stores.register("icp_brasil", truststore.toString(), TestCertificates.PASSWORD.toCharArray(),
            "ICPBRASIL", List.of());
        AuthService auth = new AuthService("segredo", new UserStore(ds.authCatalog()));
        // a autoridade de teste não publica revogação; este teste trata de identidade
        rpc = new RpcDispatcher(ds, auth, true, 0, stores, new CertificateChallengeService(true));
    }

    private void usuario(String nome, String db, String fingerprint) {
        ds.authCatalog().defineCertificateUser(nome, AuthCatalog.Scope.DATABASE, "app", db, "icp_brasil",
            fingerprint, List.of("viewer"), List.of());
    }

    private static Session sessao(String db) {
        Session s = Session.create();
        s.namespace("app");
        s.database(db);
        return s;
    }

    private String completar(TestCertificates.Identity identidade, String usuario) throws Exception {
        Session s = sessao("main");
        AxonValue desafio = AxonJson.parseDocument(rpc.dispatch(
            "{\"id\":1,\"method\":\"certificate.begin\",\"params\":[{\"store\":\"icp_brasil\"}]}", s))
            .asObject().get("result");
        String id = desafio.asObject().get("id").asString();
        String valor = desafio.asObject().get("challenge").asString();
        Signature assinatura = Signature.getInstance("SHA256withRSA");
        assinatura.initSign(identidade.key());
        assinatura.update(valor.getBytes(StandardCharsets.US_ASCII));
        String assinado = Base64.getEncoder().encodeToString(assinatura.sign());
        String cadeia = Base64.getEncoder().encodeToString(identidade.certificate().getEncoded());
        return rpc.dispatch("{\"id\":2,\"method\":\"certificate.complete\",\"params\":[{\"id\":\"" + id
            + "\",\"challenge\":\"" + valor + "\",\"store\":\"icp_brasil\",\"user\":\"" + usuario
            + "\",\"signature\":\"" + assinado + "\",\"chain\":[\"" + cadeia + "\"]}]}", s);
    }

    @Test
    void certificadoDeUmaPessoaNaoEmiteCredencialParaOutra() throws Exception {
        String resposta = completar(pessoa, "99999999999");

        assertTrue(resposta.contains("\"error\""), resposta);
        assertFalse(resposta.contains("axontc_"), resposta);
    }

    @Test
    void certificadoEmiteCredencialParaOProprioTitular() throws Exception {
        String resposta = completar(pessoa, "11111111111");

        assertTrue(resposta.contains("axontc_"), resposta);
    }

    @Test
    void certificadoSemDocumentoExigeFingerprintNoUsuario() throws Exception {
        String resposta = completar(semDocumento, "99999999999");

        assertTrue(resposta.contains("\"error\""), resposta);
        assertFalse(resposta.contains("axontc_"), resposta);
    }

    @Test
    void certificadoSemDocumentoEntraSoNoUsuarioComOFingerprint() throws Exception {
        String resposta = completar(semDocumento, "22222222222");
        assertTrue(resposta.contains("axontc_"), resposta);
        String credencial = AxonJson.parseDocument(resposta).asObject().get("result").asString();

        // há um homônimo sem FINGERPRINT em outro banco: a credencial não vale para ele
        String login = rpc.dispatch("{\"id\":3,\"method\":\"signin\",\"params\":[{\"user\":\"22222222222\","
            + "\"pass\":\"" + credencial + "\"}]}", sessao("outro"));

        assertTrue(login.contains("\"error\""), login);
    }
}
