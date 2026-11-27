package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** O certificado do cliente é opcional por padrão e obrigatório com AXON_TLS_CLIENT_AUTH=require. */
class TlsClientAuthHttpsTest {

    /** Equivale à variável AXON_TLS_CLIENT_AUTH, para o teste poder defini-la. */
    private static final String PROPERTY = "axon.tls.clientAuth";

    @TempDir
    static Path dir;

    private static TestCertificates.Identity servidor;
    private static TestCertificates.Identity cliente;
    private static Path truststore;

    private AxonServer server;

    @BeforeAll
    static void certificados() throws Exception {
        TestCertificates ca = new TestCertificates(dir);
        servidor = ca.issue("servidor", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1");
        cliente = ca.issue("cliente", "CN=CLIENTE");
        truststore = ca.truststore();
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty(PROPERTY);
        if (server != null) {
            server.stop();
        }
    }

    private int iniciar(Map<String, String> extra) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        Map<String, String> env = new HashMap<>(Map.of(
            "AXON_PORT", String.valueOf(port),
            "AXON_TLS_CERT", servidor.file().toString(),
            "AXON_TLS_KEY", TestCertificates.PASSWORD,
            "AXON_TLS_CA", truststore.toString()));
        env.putAll(extra);
        server = AxonServer.start(Datastore.memory(), "secret-testing",
            ServerConfig.load(Path.of("nao-existe.conf"), env));
        return port;
    }

    private static int health(int port, TestCertificates.Identity identidade) throws Exception {
        KeyManager[] chaves = null;
        if (identidade != null) {
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(identidade.keyStore(), TestCertificates.PASSWORD.toCharArray());
            chaves = factory.getKeyManagers();
        }
        KeyStore confiaveis = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(truststore)) {
            confiaveis.load(in, TestCertificates.PASSWORD.toCharArray());
        }
        TrustManagerFactory confianca = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        confianca.init(confiaveis);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(chaves, confianca.getTrustManagers(), null);
        HttpClient http = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).build();
        return http.send(HttpRequest.newBuilder().uri(URI.create("https://localhost:" + port + "/health")).GET()
            .build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void porPadraoOCertificadoDoClienteEhOpcional() throws Exception {
        int port = iniciar(Map.of());

        assertEquals(200, health(port, null));
    }

    @Test
    void comRequireSoEntraQuemApresentaCertificado() throws Exception {
        System.setProperty(PROPERTY, "require");
        int port = iniciar(Map.of());

        assertThrows(IOException.class, () -> health(port, null));
        assertEquals(200, health(port, cliente));
    }

    @Test
    void requireComPortaSemTlsImpedeASubida() {
        System.setProperty(PROPERTY, "require");

        assertThrows(IllegalArgumentException.class, () -> iniciar(Map.of("AXON_PLAIN_PORT", "18999")));
    }
}
