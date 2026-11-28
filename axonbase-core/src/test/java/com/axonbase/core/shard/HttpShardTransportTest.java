package com.axonbase.core.shard;

import com.axonbase.core.cluster.CommitOutcomeUnknownException;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.shard.backup.BackupHttp;
import com.axonbase.core.shard.backup.BackupMember;
import com.axonbase.core.shard.backup.HttpBackupMember;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: transporte HTTP entre gateway e nós")
class HttpShardTransportTest {

    private final ShardNode node = new ShardNode(new Datastore(new MemoryBackend()), null, HybridClock.system());
    private final List<String> seenCredentials = new ArrayList<>();
    private final AtomicBoolean dropAnswer = new AtomicBoolean();
    private final AtomicInteger signins = new AtomicInteger();
    private HttpServer server;
    private HttpClient http;
    private String url;

    /** Um nó de backup de mentira, que registra o que pediram. */
    private static final class FakeBackup implements BackupMember {
        final List<String> calls = new ArrayList<>();

        @Override
        public String node() {
            return "n1";
        }

        @Override
        public Status status() {
            return new Status(true, new HlcTimestamp(100, 2));
        }

        @Override
        public Barrier barrier(HlcTimestamp atLeast) {
            calls.add("barrier " + atLeast.physical() + ":" + atLeast.logical());
            return new Barrier(7, new HlcTimestamp(101, 0), "inc");
        }

        @Override
        public Copy backup(long minIndex) {
            calls.add("backup " + minIndex);
            return new Copy("n1", "/b", 3, 6, "/j", "inc");
        }

        @Override
        public void seal(long baseIndex, long upToIndex) {
            calls.add("seal " + baseIndex + " " + upToIndex);
            if (upToIndex < 0) {
                throw new NotLeaderException("n2", "10.0.0.2:9000");
            }
        }
    }

    private final FakeBackup backup = new FakeBackup();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ShardHttp.EXECUTE, this::execute);
        server.createContext(BackupHttp.PREFIX, this::backup);
        server.createContext("/signin", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("certa")) {
                answer(exchange, 200, "{\"token\":\"t" + signins.incrementAndGet() + "\"}");
            } else {
                answer(exchange, 401, "{}");
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        http.close();
    }

    private void execute(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        seenCredentials.add(authorization);
        ShardRequest request = ShardCodec.decodeRequest(exchange.getRequestBody().readAllBytes(), authorization);
        try {
            byte[] reply = ShardCodec.encode(node.handle(request));
            if (dropAnswer.get()) {
                // executou, e a conexão cai antes da resposta
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        } catch (RuntimeException e) {
            ShardHttp.Failure failure = ShardHttp.failure(e);
            answer(exchange, failure.status(), failure.body());
        }
    }

    private void backup(HttpExchange exchange) throws IOException {
        String action = exchange.getRequestURI().getPath().substring(BackupHttp.PREFIX.length());
        AxonValue in = AxonJson.parseDocument(
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        try {
            AxonValue out = BackupHttp.serve(backup, action, in);
            answer(exchange, out == null ? 404 : 200, out == null ? "{}" : AxonJson.write(out));
        } catch (RuntimeException e) {
            ShardHttp.Failure failure = ShardHttp.failure(e);
            answer(exchange, failure.status(), failure.body());
        }
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String deadEndpoint() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    @Test
    void passaAoProximoNoQuandoUmNaoAtendeERepassaACredencialDoCliente() throws IOException {
        ShardClient client = HttpShards.client(new ShardInfo("s0", List.of(deadEndpoint(), url)), http,
            Duration.ofSeconds(5), () -> "Bearer do-gateway");

        client.execute(ShardRequest.of("n", "d", "CREATE t:1 SET a = 1"));
        ShardReply reply = client.execute(ShardRequest.of("n", "d", "SELECT VALUE a FROM t")
            .withAuthorization("Bearer do-cliente"));

        assertEquals(List.of(AxonValue.num(1)), reply.value().asArray());
        assertEquals(List.of("Bearer do-gateway", "Bearer do-cliente"), seenCredentials);
    }

    @Test
    void semCredencialPropriaNaoAcrescentaNenhuma() {
        HttpShards.client(new ShardInfo("s0", List.of(url)), http, Duration.ofSeconds(5), null)
            .execute(ShardRequest.of("n", "d", "SELECT * FROM t"));

        assertEquals(java.util.Collections.singletonList(null), seenCredentials);
    }

    @Test
    void erroDoShardChegaComoExcecao() {
        ShardClient client = HttpShards.client(new ShardInfo("s0", List.of(url)), http, Duration.ofSeconds(5), null);

        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> client.execute(ShardRequest.of("n", "d", "ERROR 'parou'")));

        assertTrue(failure.getMessage().contains("parou"), failure.getMessage());
    }

    @Test
    void respostaPerdidaNumaEscritaEDesfechoDesconhecidoENumaLeituraPodeRepetir() {
        HttpShardMember member = new HttpShardMember("s0", url, http, Duration.ofSeconds(5), null);
        dropAnswer.set(true);

        assertThrows(CommitOutcomeUnknownException.class,
            () -> member.handle(ShardRequest.of("n", "d", "CREATE t:1")));
        assertThrows(ShardUnreachableException.class,
            () -> member.handle(ShardRequest.of("n", "d", "SELECT * FROM t")));
    }

    @Test
    void shardSemEnderecoERecusado() {
        assertThrows(ShardException.class,
            () -> HttpShards.client(new ShardInfo("s0", List.of()), http, Duration.ofSeconds(1), null));
    }

    @Test
    void credencialDoGatewayEntraUmaVezEReaproveitaOToken() throws IOException {
        var credential = HttpShards.signin(http, List.of(deadEndpoint(), url), "root", "certa", Duration.ofSeconds(5));

        assertEquals("Bearer t1", credential.get());
        assertEquals("Bearer t1", credential.get());
        assertEquals(1, signins.get());
        ShardException refused = assertThrows(ShardException.class,
            () -> HttpShards.signin(http, List.of(url), "root", "errada", Duration.ofSeconds(5)).get());
        assertEquals(ShardException.Code.UNAVAILABLE, refused.code());
    }

    @Test
    void operacoesDeBackupAtravessamARede() {
        HttpBackupMember member = new HttpBackupMember("s0", url, http, Duration.ofSeconds(5), () -> "Bearer x");

        assertEquals("n1", member.node());
        assertEquals(new BackupMember.Status(true, new HlcTimestamp(100, 2)), member.status());
        assertEquals(new BackupMember.Barrier(7, new HlcTimestamp(101, 0), "inc"),
            member.barrier(new HlcTimestamp(99, 1)));
        assertEquals(new BackupMember.Copy("n1", "/b", 3, 6, "/j", "inc"), member.backup(5));
        member.seal(6, 9);
        NotLeaderException redirected = assertThrows(NotLeaderException.class, () -> member.seal(6, -1));

        assertEquals("10.0.0.2:9000", redirected.leaderAddress());
        assertEquals(List.of("barrier 99:1", "backup 5", "seal 6 9", "seal 6 -1"), backup.calls);
        assertEquals(null, BackupHttp.serve(backup, "outra", AxonValue.nul()));
    }

    @Test
    void noDeBackupInalcancavelEAvisado() throws IOException {
        HttpBackupMember member = new HttpBackupMember("s0", deadEndpoint(), http, Duration.ofSeconds(5), null);

        assertThrows(ShardUnreachableException.class, member::status);
    }
}
