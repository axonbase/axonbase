package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revogação no login por certificado, nas rotas HTTP e RPC. As listas de revogação vêm de um servidor HTTP local,
 * criado pelo próprio teste: nada sai da máquina.
 */
class CertificateRevocationHttpsTest {

    private enum Rota {
        HTTP, RPC
    }

    private static final String OPCAO = "AXON_CERT_ALLOW_UNKNOWN_REVOCATION";

    @TempDir
    static Path dir;

    private static HttpServer revogacao;
    private static ExecutorService atendentes;
    private static final Map<String, byte[]> listas = new ConcurrentHashMap<>();
    private static final AtomicInteger consultasOcsp = new AtomicInteger();
    private static final CountDownLatch encerramento = new CountDownLatch(1);

    private static Path truststore;
    private static TestCertificates.Identity servidor;
    private static TestCertificates.Identity valido;
    private static TestCertificates.Identity revogado;
    private static TestCertificates.Identity semInformacao;
    private static TestCertificates.Identity listaAusente;
    private static TestCertificates.Identity listaRecusada;
    private static TestCertificates.Identity listaLenta;
    private static TestCertificates.Identity ocspForaERevogado;
    private static TestCertificates.Identity validoSobIntermediaria;
    private static TestCertificates.Identity revogadoSobIntermediariaSemInformacao;
    private static TestCertificates.Identity semInformacaoSobIntermediariaRevogada;
    private static TestCertificates.Identity vencido;
    private static TestCertificates.Identity deOutraAutoridade;

    @BeforeAll
    static void preparar() throws Exception {
        revogacao = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        revogacao.createContext("/", troca -> {
            byte[] lista = listas.get(troca.getRequestURI().getPath());
            if (lista == null) {
                troca.sendResponseHeaders(404, -1);
            } else {
                troca.sendResponseHeaders(200, lista.length);
                try (OutputStream out = troca.getResponseBody()) {
                    out.write(lista);
                }
            }
            troca.close();
        });
        revogacao.createContext("/ocsp", troca -> {
            consultasOcsp.incrementAndGet();
            troca.sendResponseHeaders(500, -1);
            troca.close();
        });
        revogacao.createContext("/lenta", troca -> {
            try {
                encerramento.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            troca.close();
        });
        atendentes = Executors.newCachedThreadPool();
        revogacao.setExecutor(atendentes);
        revogacao.start();
        int porta = revogacao.getAddress().getPort();
        int portaFechada;
        try (ServerSocket livre = new ServerSocket(0)) {
            portaFechada = livre.getLocalPort();
        }

        TestCertificates raiz = new TestCertificates(dir);
        truststore = raiz.truststore();
        servidor = raiz.issue("servidor", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1");

        valido = pessoa(raiz, "valido", pontoDeLista(porta, "/valido.crl"));
        listas.put("/valido.crl", raiz.crl());
        revogado = pessoa(raiz, "revogado", pontoDeLista(porta, "/revogado.crl"));
        listas.put("/revogado.crl", raiz.crl(revogado.certificate()));
        semInformacao = pessoa(raiz, "seminfo");
        listaAusente = pessoa(raiz, "ausente", pontoDeLista(porta, "/nao-existe.crl"));
        listaRecusada = pessoa(raiz, "recusada", pontoDeLista(portaFechada, "/fora.crl"));
        listaLenta = pessoa(raiz, "lenta", pontoDeLista(porta, "/lenta.crl"));
        ocspForaERevogado = pessoa(raiz, "ocspfora", pontoDeLista(porta, "/crl-sem-ocsp.crl"),
            "-ext", "AuthorityInfoAccess=ocsp:uri:http://127.0.0.1:" + porta + "/ocsp");
        listas.put("/crl-sem-ocsp.crl", raiz.crl(ocspForaERevogado.certificate()));

        TestCertificates boa = raiz.intermediate("intboa", separar(pontoDeLista(porta, "/raiz-boa.crl")));
        listas.put("/raiz-boa.crl", raiz.crl());
        validoSobIntermediaria = pessoa(boa, "validoint", pontoDeLista(porta, "/intboa.crl"));
        listas.put("/intboa.crl", boa.crl());

        TestCertificates muda = raiz.intermediate("intmuda");
        revogadoSobIntermediariaSemInformacao = pessoa(muda, "revogadoint", pontoDeLista(porta, "/intmuda.crl"));
        listas.put("/intmuda.crl", muda.crl(revogadoSobIntermediariaSemInformacao.certificate()));

        TestCertificates cassada = raiz.intermediate("intcassada", separar(pontoDeLista(porta, "/raiz-cassada.crl")));
        semInformacaoSobIntermediariaRevogada = pessoa(cassada, "seminfoint");
        // a intermediária é o segundo certificado da cadeia de quem ela emitiu
        listas.put("/raiz-cassada.crl", raiz.crl((java.security.cert.X509Certificate)
            semInformacaoSobIntermediariaRevogada.keyStore().getCertificateChain("seminfoint")[1]));

        vencido = pessoa(raiz, "vencido", "-startdate", "-10d");
        Path outroDir = Files.createDirectory(dir.resolve("outra"));
        deOutraAutoridade = pessoa(new TestCertificates(outroDir), "estranho");
    }

    @AfterAll
    static void encerrar() {
        encerramento.countDown();
        if (revogacao != null) {
            revogacao.stop(0);
        }
        if (atendentes != null) {
            atendentes.shutdownNow();
        }
    }

    /** Opção do keytool e valor num só texto; {@link #separar} os divide. */
    private static String pontoDeLista(int porta, String caminho) {
        return "-ext CRLDistributionPoints=uri:http://127.0.0.1:" + porta + caminho;
    }

    private static TestCertificates.Identity pessoa(TestCertificates autoridade, String alias, String... extensoes)
        throws Exception {
        return autoridade.issue(alias, "CN=PESSOA " + alias + ":11111111111", separar(extensoes));
    }

    /** Divide os argumentos escritos como "opção valor". */
    private static String[] separar(String... argumentos) {
        List<String> saida = new ArrayList<>();
        for (String argumento : argumentos) {
            saida.addAll(List.of(argumento.split(" ")));
        }
        return saida.toArray(String[]::new);
    }

    @Test
    void certificadoRevogadoERecusado() throws Exception {
        recusado(revogado, false);
    }

    @Test
    void certificadoRevogadoERecusadoMesmoComAOpcao() throws Exception {
        recusado(revogado, true);
    }

    @Test
    void certificadoNaoRevogadoEntra() throws Exception {
        aceito(valido, false);
    }

    @Test
    void cadeiaComIntermediariaNaoRevogadaEntra() throws Exception {
        aceito(validoSobIntermediaria, false);
    }

    @Test
    void semInformacaoDeRevogacaoERecusadoPorPadrao() throws Exception {
        recusado(semInformacao, false);
    }

    @Test
    void semInformacaoDeRevogacaoEntraComAOpcao() throws Exception {
        aceito(semInformacao, true);
    }

    @Test
    void listaQueNaoExisteERecusadaPorPadrao() throws Exception {
        recusado(listaAusente, false);
    }

    @Test
    void servicoForaDoArERecusadoPorPadrao() throws Exception {
        recusado(listaRecusada, false);
    }

    @Test
    void servicoForaDoArEntraComAOpcao() throws Exception {
        aceito(listaRecusada, true);
    }

    @Test
    void ocspForaDoArNaoEscondeARevogacaoDaLista() throws Exception {
        int antes = consultasOcsp.get();

        recusado(ocspForaERevogado, true);

        assertTrue(consultasOcsp.get() > antes, "o serviço OCSP não foi consultado");
    }

    @Test
    void folhaRevogadaERecusadaAindaQueAIntermediariaNaoSejaVerificavel() throws Exception {
        recusado(revogadoSobIntermediariaSemInformacao, true);
    }

    @Test
    void intermediariaRevogadaERecusadaAindaQueAFolhaNaoSejaVerificavel() throws Exception {
        recusado(semInformacaoSobIntermediariaRevogada, true);
    }

    @Test
    void certificadoVencidoContinuaRecusadoComAOpcao() throws Exception {
        // só RPC: no HTTP o próprio TLS já derruba a conexão de um certificado vencido
        String resposta = tentar(Rota.RPC, vencido, true);

        assertFalse(resposta.contains("axontc_"), resposta);
    }

    @Test
    void autoridadeDesconhecidaContinuaRecusadaComAOpcao() throws Exception {
        String resposta = tentar(Rota.RPC, deOutraAutoridade, true);

        assertFalse(resposta.contains("axontc_"), resposta);
    }

    @Test
    void servicoQueNaoRespondeNaoPrendeOLogin() throws Exception {
        long inicio = System.nanoTime();

        String resposta = tentar(Rota.RPC, listaLenta, false);

        Duration duracao = Duration.ofNanos(System.nanoTime() - inicio);
        assertFalse(resposta.contains("axontc_"), resposta);
        // o padrão do JDK é 15 segundos por consulta
        assertTrue(duracao.compareTo(Duration.ofSeconds(14)) < 0, "login levou " + duracao);
    }

    private static void recusado(TestCertificates.Identity cliente, boolean opcao) throws Exception {
        for (Rota rota : Rota.values()) {
            String resposta = tentar(rota, cliente, opcao);
            assertFalse(resposta.contains("axontc_"), rota + ": " + resposta);
        }
    }

    private static void aceito(TestCertificates.Identity cliente, boolean opcao) throws Exception {
        for (Rota rota : Rota.values()) {
            String resposta = tentar(rota, cliente, opcao);
            assertTrue(resposta.contains("axontc_"), rota + ": " + resposta);
        }
    }

    /** Sobe um servidor novo (a emissão tem intervalo mínimo por usuário), tenta o login e devolve a resposta. */
    private static String tentar(Rota rota, TestCertificates.Identity cliente, boolean opcao) throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.authCatalog().defineCertificateUser("11111111111", AuthCatalog.Scope.DATABASE, "app", "main",
            "icp_brasil", null, List.of("viewer"), List.of());
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        Map<String, String> env = new HashMap<>(Map.of(
            "AXON_PORT", String.valueOf(port),
            "AXON_TLS_CERT", servidor.file().toString(),
            "AXON_TLS_KEY", TestCertificates.PASSWORD,
            "AXON_TLS_CA", truststore.toString()));
        if (opcao) {
            env.put(OPCAO, "true");
        }
        AxonServer server = AxonServer.start(ds, "secret-testing",
            ServerConfig.load(Path.of("nao-existe.conf"), env));
        try {
            return rota == Rota.HTTP ? porHttp(port, cliente) : porRpc(port, cliente);
        } finally {
            server.stop();
        }
    }

    private static String porHttp(int port, TestCertificates.Identity cliente) throws Exception {
        KeyManagerFactory chaves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        chaves.init(cliente.keyStore(), TestCertificates.PASSWORD.toCharArray());
        HttpResponse<String> r = cliente(chaves.getKeyManagers()).send(HttpRequest.newBuilder().uri(URI.create(
            "https://localhost:" + port + "/certificate/login/icp_brasil?ns=app&db=main")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() == 200, r.statusCode() + " " + r.body());
        return r.body();
    }

    private static String porRpc(int port, TestCertificates.Identity identidade) throws Exception {
        HttpClient http = cliente(null);
        String inicio = rpc(http, port,
            "{\"id\":1,\"method\":\"certificate.begin\",\"params\":[{\"store\":\"icp_brasil\"}]}");
        AxonValue desafio = AxonJson.parseDocument(inicio).asObject().get("result");
        String id = desafio.asObject().get("id").asString();
        String valor = desafio.asObject().get("challenge").asString();
        Signature assinatura = Signature.getInstance("SHA256withRSA");
        assinatura.initSign(identidade.key());
        assinatura.update(valor.getBytes(StandardCharsets.US_ASCII));
        String assinado = Base64.getEncoder().encodeToString(assinatura.sign());
        List<String> cadeia = new ArrayList<>();
        String alias = identidade.keyStore().aliases().nextElement();
        for (Certificate certificado : identidade.keyStore().getCertificateChain(alias)) {
            cadeia.add("\"" + Base64.getEncoder().encodeToString(certificado.getEncoded()) + "\"");
        }
        return rpc(http, port, "{\"id\":2,\"method\":\"certificate.complete\",\"params\":[{\"id\":\"" + id
            + "\",\"challenge\":\"" + valor + "\",\"store\":\"icp_brasil\",\"user\":\"11111111111\","
            + "\"signature\":\"" + assinado + "\",\"chain\":[" + String.join(",", cadeia) + "]}]}");
    }

    private static String rpc(HttpClient http, int port, String corpo) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder()
            .uri(URI.create("https://localhost:" + port + "/rpc"))
            .header("Axon-Ns", "app").header("Axon-Db", "main")
            .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() == 200, r.statusCode() + " " + r.body());
        return r.body();
    }

    private static HttpClient cliente(KeyManager[] chaves) throws Exception {
        KeyStore confiaveis = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(truststore)) {
            confiaveis.load(in, TestCertificates.PASSWORD.toCharArray());
        }
        TrustManagerFactory confianca = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        confianca.init(confiaveis);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(chaves, confianca.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).build();
    }
}
