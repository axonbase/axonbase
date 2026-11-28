package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /certificate/login sobre TLS com certificado do cliente: o parâmetro user não troca a identidade do certificado. */
class CertificateLoginHttpsTest {

    @TempDir
    static Path dir;

    private static TestCertificates.Identity servidor;
    private static TestCertificates.Identity pessoa;
    private static TestCertificates.Identity semDocumento;
    private static Path truststore;

    private AxonServer server;
    private int port;

    @BeforeAll
    static void certificados() throws Exception {
        TestCertificates ca = new TestCertificates(dir);
        servidor = ca.issue("servidor", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1");
        pessoa = ca.issue("pessoa", "CN=PESSOA A:11111111111");
        semDocumento = ca.issue("semdoc", "CN=SEM DOCUMENTO");
        truststore = ca.truststore();
    }

    @BeforeEach
    void setUp() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        for (String nome : List.of("11111111111", "99999999999")) {
            ds.authCatalog().defineCertificateUser(nome, AuthCatalog.Scope.DATABASE, "app", "main", "icp_brasil",
                null, List.of("viewer"), List.of());
        }
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        ServerConfig config = ServerConfig.load(Path.of("nao-existe.conf"), Map.of(
            "AXON_PORT", String.valueOf(port),
            "AXON_TLS_CERT", servidor.file().toString(),
            "AXON_TLS_KEY", TestCertificates.PASSWORD,
            "AXON_TLS_CA", truststore.toString(),
            // a autoridade de teste não publica revogação; este teste trata de identidade
            "AXON_CERT_ALLOW_UNKNOWN_REVOCATION", "true"));
        server = AxonServer.start(ds, "secret-testing", config);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private String abrir(TestCertificates.Identity cliente, String query) throws Exception {
        KeyManagerFactory chaves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        chaves.init(cliente.keyStore(), TestCertificates.PASSWORD.toCharArray());
        KeyStore confiaveis = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(truststore)) {
            confiaveis.load(in, TestCertificates.PASSWORD.toCharArray());
        }
        TrustManagerFactory confianca = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        confianca.init(confiaveis);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(chaves.getKeyManagers(), confianca.getTrustManagers(), null);
        HttpClient http = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder().uri(URI.create(
            "https://localhost:" + port + "/certificate/login/icp_brasil?ns=app&db=main" + query)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    @Test
    void parametroUserNaoSubstituiAIdentidadeDoCertificado() throws Exception {
        String pagina = abrir(pessoa, "&user=99999999999");

        assertFalse(pagina.contains("axontc_"), pagina);
    }

    @Test
    void semParametroEmiteParaOTitularDoCertificado() throws Exception {
        String pagina = abrir(pessoa, "");

        assertTrue(pagina.contains("axontc_"), pagina);
        assertTrue(pagina.contains("11111111111"), pagina);
    }

    @Test
    void certificadoSemDocumentoNaoAssumeUsuarioSemFingerprint() throws Exception {
        String pagina = abrir(semDocumento, "&user=99999999999");

        assertFalse(pagina.contains("axontc_"), pagina);
    }
}
