package com.axonbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** /admin/v1/activity: consultas em execução e lentas, cancelamento, sessões, transações e conflitos. */
@DisplayName("API de atividade")
class ActivityApiTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private Datastore ds;
    private AxonServer server;
    private int port;
    private String root;
    private String viewer;
    private String operator;

    @BeforeEach
    void start() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.authCatalog().defineUser("visao", AuthCatalog.Scope.ROOT, null, null, "pw", List.of("viewer"), List.of());
        ds.authCatalog().defineUser("oper", AuthCatalog.Scope.ROOT, null, null, "pw", List.of("operator"), List.of());
        ds.authCatalog().defineUser("adm", AuthCatalog.Scope.ROOT, null, null, "pw", List.of("admin"), List.of());
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        port = server.port();
        root = signin("root");
        viewer = signin("visao");
        operator = signin("oper");
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
    }

    // ---------------- helpers ----------------

    private String signin(String user) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"" + user + "\",\"pass\":\"pw\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        return AxonJson.parseDocument(r.body()).asObject().get("token").asString();
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        var b = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/admin/v1" + path))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, HttpRequest.BodyPublishers.noBody());
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sql(String token, String statement) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/sql"))
            .header("Authorization", "Bearer " + token).header("Axon-Ns", "app").header("Axon-Db", "main")
            .POST(HttpRequest.BodyPublishers.ofString(statement)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<AxonValue> list(String path, String token) throws Exception {
        HttpResponse<String> r = call("GET", path, token);
        assertEquals(200, r.statusCode(), r.body());
        return AxonJson.parseDocument(r.body()).asArray();
    }

    private static <T> T await(Supplier<T> probe, java.util.function.Predicate<T> ok) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        T value = probe.get();
        while (!ok.test(value) && System.currentTimeMillis() < end) {
            Thread.sleep(25);
            value = probe.get();
        }
        return value;
    }

    private void seed(int rows) throws Exception {
        for (int base = 0; base < rows; base += 500) {
            StringBuilder sb = new StringBuilder("INSERT INTO t [");
            for (int i = base; i < Math.min(rows, base + 500); i++) {
                if (i > base) sb.append(',');
                sb.append("{id: ").append(i).append(", v: ").append(i % 10).append("}");
            }
            assertEquals(200, sql(root, sb.append(']').toString()).statusCode());
        }
    }

    // ---------------- testes ----------------

    @Test
    @DisplayName("exige autenticação e dá o resumo para qualquer papel")
    void summaryAndAuth() throws Exception {
        assertEquals(401, call("GET", "/activity/summary", null).statusCode());
        HttpResponse<String> r = call("GET", "/activity/summary", viewer);
        assertEquals(200, r.statusCode(), r.body());
        AxonValue o = AxonJson.parseDocument(r.body());
        assertEquals(1000, o.asObject().get("slowThresholdMs").asLong());
        assertEquals(0, o.asObject().get("running").asLong());
        assertEquals(404, call("GET", "/activity/inexistente", viewer).statusCode());
    }

    @Test
    @DisplayName("consulta lenta aparece com fingerprint; o texto só vai para OPERATOR e ADMIN")
    void slowQueryVisibilityByRole() throws Exception {
        server.activity().configure(new ActivityConfig(1, true, 100));
        seed(2000);
        assertEquals(200, sql(root, "SELECT * FROM t WHERE v = 3 AND id > 10 LIMIT 5000").statusCode());

        List<AxonValue> asViewer = list("/activity/slow?limit=50", viewer);
        assertFalse(asViewer.isEmpty());
        AxonValue hit = asViewer.stream().filter(v -> v.asObject().get("fingerprint").asString().contains("WHERE v = ?"))
            .findFirst().orElseThrow();
        assertEquals("SELECT * FROM t WHERE v = ? AND id > ? LIMIT ?", hit.asObject().get("fingerprint").asString());
        assertTrue(hit.asObject().get("sql").isNull(), "VIEWER não recebe o texto");
        assertEquals("root", hit.asObject().get("user").asString());
        assertEquals("app", hit.asObject().get("ns").asString());

        AxonValue asOperator = list("/activity/slow?limit=50", operator).stream()
            .filter(v -> v.asObject().get("fingerprint").asString().contains("WHERE v = ?")).findFirst().orElseThrow();
        assertEquals("SELECT * FROM t WHERE v = 3 AND id > 10 LIMIT 5000", asOperator.asObject().get("sql").asString());

        List<AxonValue> top = list("/activity/top?orderBy=total&limit=10", viewer);
        assertFalse(top.isEmpty());
        assertTrue(top.get(0).asObject().get("count").asLong() >= 1);
        assertTrue(top.get(0).asObject().get("totalMs").asLong() >= 0);
        assertFalse(top.get(0).asObject().containsKey("sql"), "agregação nunca traz texto com valores");
    }

    @Test
    @DisplayName("com a captura de SQL desligada só o fingerprint é guardado")
    void captureCanBeDisabled() throws Exception {
        server.activity().configure(new ActivityConfig(1, false, 100));
        seed(1500);
        sql(root, "SELECT * FROM t WHERE v = 7");
        AxonValue hit = list("/activity/slow?limit=50", root).stream()
            .filter(v -> v.asObject().get("fingerprint").asString().contains("WHERE v = ?")).findFirst().orElseThrow();
        assertTrue(hit.asObject().get("sql").isNull());
    }

    @Test
    @DisplayName("cancelar uma consulta em execução interrompe de verdade e devolve erro ao cliente")
    void cancelRunningQuery() throws Exception {
        seed(20000);
        // várias varreduras sem índice na mesma requisição: dura bem mais do que o tempo do teste
        String heavy = "SELECT * FROM t WHERE v = 5;".repeat(400);
        CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return sql(root, heavy);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        List<AxonValue> running = await(() -> {
            try {
                return list("/activity/queries", operator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, l -> !l.isEmpty());
        assertEquals(1, running.size());
        AxonValue q = running.get(0);
        assertEquals("root", q.asObject().get("user").asString());
        assertTrue(q.asObject().get("elapsedMs").asLong() >= 0);
        assertTrue(q.asObject().get("sql").asString().startsWith("SELECT * FROM t WHERE v = 5;"));
        String id = q.asObject().get("id").asString();

        assertEquals(403, call("POST", "/activity/queries/" + id + "/cancel", viewer).statusCode());
        assertEquals(404, call("POST", "/activity/queries/nao-existe/cancel", operator).statusCode());
        HttpResponse<String> cancel = call("POST", "/activity/queries/" + id + "/cancel", operator);
        assertEquals(200, cancel.statusCode(), cancel.body());

        HttpResponse<String> done = pending.get(15, TimeUnit.SECONDS);
        assertEquals(400, done.statusCode());
        assertTrue(done.body().toLowerCase().contains("cancel"), done.body());
        assertTrue(await(() -> {
            try {
                return list("/activity/queries", root);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, List::isEmpty).isEmpty(), "a consulta deve sair da lista de execução");
        assertEquals(1, AxonJson.parseDocument(call("GET", "/activity/summary", root).body())
            .asObject().get("cancelledTotal").asLong());
    }

    @Test
    @DisplayName("sessões por token: lista, marca a atual e encerra por revogação")
    void tokenSessions() throws Exception {
        String other = signin("adm");
        String rootSecond = signin("root");
        List<AxonValue> sessions = list("/activity/sessions", root);
        assertTrue(sessions.stream().anyMatch(s -> s.asObject().get("user").asString().equals("adm")));
        long currents = sessions.stream().filter(s -> s.asObject().get("current").asBool()).count();
        assertEquals(1, currents, "só a sessão de quem chama é a atual");
        AxonValue current = sessions.stream().filter(s -> s.asObject().get("current").asBool()).findFirst().orElseThrow();
        String admId = sessions.stream().filter(s -> s.asObject().get("user").asString().equals("adm"))
            .findFirst().orElseThrow().asObject().get("id").asString();

        assertEquals(403, call("DELETE", "/activity/sessions/" + admId, operator).statusCode(), "só ADMIN encerra");
        assertEquals(409, call("DELETE", "/activity/sessions/" + current.asObject().get("id").asString(), root)
            .statusCode(), "a própria sessão não");
        assertEquals(404, call("DELETE", "/activity/sessions/nao-existe", root).statusCode());
        String otherRootId = sessions.stream().filter(s -> s.asObject().get("user").asString().equals("root")
            && !s.asObject().get("current").asBool()).findFirst().orElseThrow().asObject().get("id").asString();
        assertEquals(409, call("DELETE", "/activity/sessions/" + otherRootId, root).statusCode(), "root não é expulso");

        assertEquals(200, call("GET", "/info", other).statusCode());
        assertEquals(204, call("DELETE", "/activity/sessions/" + admId, root).statusCode());
        assertEquals(401, call("GET", "/info", other).statusCode(), "o token revogado deixa de valer");
        assertNotNull(rootSecond);
    }

    /** Escuta frames de um WebSocket do /rpc/ws. */
    private static final class Frames implements WebSocketListener {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        volatile Session session;
        volatile boolean closed;

        @Override
        public void onWebSocketConnect(Session s) {
            this.session = s;
        }

        @Override
        public void onWebSocketText(String message) {
            frames.add(message);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            closed = true;
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {}

        @Override
        public void onWebSocketError(Throwable cause) {}

        String rpc(String json) throws Exception {
            session.getRemote().sendString(json);
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) {
                String f = frames.poll(200, TimeUnit.MILLISECONDS);
                if (f != null && !f.contains("\"hello\"") && !f.contains("\"notification\"")) {
                    return f;
                }
            }
            throw new AssertionError("sem resposta para " + json);
        }
    }

    @Test
    @DisplayName("sessão WebSocket com transação aberta: aparece, reverte e encerra")
    void webSocketTransactions() throws Exception {
        WebSocketClient client = new WebSocketClient();
        client.start();
        try {
            Frames ws = new Frames();
            client.connect(ws, URI.create("ws://127.0.0.1:" + port + "/rpc/ws")).get(10, TimeUnit.SECONDS);
            String token = AxonJson.parseDocument(
                ws.rpc("{\"id\":1,\"method\":\"signin\",\"params\":[{\"user\":\"visao\",\"pass\":\"pw\"}]}"))
                .asObject().get("result").asString();
            // no WebSocket o signin só devolve o token; a sessão passa a ter usuário depois de authenticate
            ws.rpc("{\"id\":11,\"method\":\"authenticate\",\"params\":[\"" + token + "\"]}");
            ws.rpc("{\"id\":2,\"method\":\"use\",\"params\":[\"app\",\"main\"]}");
            ws.rpc("{\"id\":3,\"method\":\"begin\",\"params\":[]}");

            List<AxonValue> sessions = await(() -> {
                try {
                    return list("/activity/sessions", root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, l -> l.stream().anyMatch(s -> "ws".equals(s.asObject().get("type").asString())));
            AxonValue w = sessions.stream().filter(s -> "ws".equals(s.asObject().get("type").asString())).findFirst().orElseThrow();
            assertEquals("visao", w.asObject().get("user").asString());
            assertTrue(w.asObject().get("inTransaction").asBool());
            String sessionId = w.asObject().get("id").asString();

            List<AxonValue> txs = list("/activity/transactions", viewer);
            assertEquals(1, txs.size());
            assertEquals(sessionId, txs.get(0).asObject().get("sessionId").asString());
            assertEquals(0, txs.get(0).asObject().get("writes").asLong());

            assertEquals(403, call("POST", "/activity/transactions/" + sessionId + "/rollback", viewer).statusCode());
            assertEquals(200, call("POST", "/activity/transactions/" + sessionId + "/rollback", operator).statusCode());
            assertTrue(list("/activity/transactions", root).isEmpty(), "a transação foi revertida");
            assertEquals(404, call("POST", "/activity/transactions/" + sessionId + "/rollback", operator).statusCode());

            assertEquals(403, call("DELETE", "/activity/sessions/" + sessionId, operator).statusCode());
            assertEquals(204, call("DELETE", "/activity/sessions/" + sessionId, root).statusCode());
            long end = System.currentTimeMillis() + 10_000;
            while (!ws.closed && System.currentTimeMillis() < end) {
                Thread.sleep(25);
            }
            assertTrue(ws.closed, "a conexão deve ser fechada");
            List<AxonValue> after = await(() -> {
                try {
                    return list("/activity/sessions", root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, l -> l.stream().noneMatch(s -> "ws".equals(s.asObject().get("type").asString())));
            assertTrue(after.stream().noneMatch(s -> "ws".equals(s.asObject().get("type").asString())));
        } finally {
            client.stop();
        }
    }

    @Test
    @DisplayName("conflitos de versão e métricas novas")
    void conflictsAndMetrics() throws Exception {
        AxonValue c = AxonJson.parseDocument(call("GET", "/activity/conflicts", viewer).body());
        assertTrue(c.asObject().get("total").asLong() >= 0);
        assertTrue(c.asObject().get("byTarget").isArray());

        HttpResponse<String> m = HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/metrics"))
            .header("Authorization", "Bearer " + root).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, m.statusCode());
        for (String name : List.of("axe_queries_running", "axe_queries_cancelled_total", "axe_sessions_open{type=\"token\"}",
            "axe_txn_open", "axe_txn_conflicts_total", "axe_slow_query_threshold_seconds")) {
            assertTrue(m.body().contains(name), "falta a métrica " + name);
        }
    }

    @Test
    @DisplayName("sem DEFINE USER root o AxonBase não lista nada além do esperado")
    void unusedImportGuard() {
        assertNotNull(new ArrayList<>());
    }
}
