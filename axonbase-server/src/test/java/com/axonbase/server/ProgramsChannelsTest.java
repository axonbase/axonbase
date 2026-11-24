package com.axonbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AxonPL pelos canais WebSocket, MCP e GraphQL: execução, erros de programa e permissão. */
class ProgramsChannelsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Datastore ds;
    private AxonServer server;
    private int port;
    private String admin;
    private String viewer;

    @BeforeEach
    void subir() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.authCatalog().defineUser("visao", AuthCatalog.Scope.ROOT, null, null, "pw", List.of("viewer"), List.of());
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        port = server.port();
        admin = signin("root");
        viewer = signin("visao");
    }

    @AfterEach
    void descer() throws Exception {
        server.stop();
    }

    private String signin(String user) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"" + user + "\",\"pass\":\"pw\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        return AxonJson.parseDocument(r.body()).asObject().get("token").asString();
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token).header("Axon-Ns", "app").header("Axon-Db", "main")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ WebSocket

    private static final class WsClient implements WebSocket.Listener {
        final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        WebSocket socket;
        int nextId = 1;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(partial.toString());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        AxonValue call(String method, String paramsJson) throws Exception {
            int id = nextId++;
            socket.sendText("{\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}", true)
                .join();
            for (;;) {
                String f = frames.poll(10, TimeUnit.SECONDS);
                if (f == null) {
                    throw new AssertionError("sem resposta do WebSocket para " + method);
                }
                AxonValue v = AxonJson.parseDocument(f);
                if (v.isObject() && v.asObject().containsKey("id") && v.asObject().get("id").asLong() == id) {
                    return v;
                }
            }
        }

        AxonValue query(String sql) throws Exception {
            return call("query", "[" + AxonJson.write(AxonValue.str(sql)) + "]");
        }
    }

    private WsClient ws(String token) throws Exception {
        WsClient c = new WsClient();
        c.socket = HTTP.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/rpc/ws"), c).join();
        assertTrue(c.frames.poll(10, TimeUnit.SECONDS).contains("hello"));
        assertFalse(c.call("use", "[\"app\",\"main\"]").asObject().containsKey("error"));
        assertFalse(c.call("authenticate", "[\"" + token + "\"]").asObject().containsKey("error"));
        return c;
    }

    @Test
    void webSocketCriaChamaESelecionaComFuncao() throws Exception {
        WsClient c = ws(admin);
        try {
            assertFalse(c.query("CREATE FUNCTION dobro(x) AS $$ return x * 2 $$").asObject().containsKey("error"));
            assertFalse(c.query("CREATE PROCEDURE grava(v) AS $$ db.exec('CREATE it CONTENT {v: $v}', {'v': v})\n"
                + "return db.one('RETURN dobro($v)', {'v': v})\n$$").asObject().containsKey("error"));
            AxonValue call = c.query("CALL grava(4)");
            assertEquals(8, call.asObject().get("result").asLong());
            AxonValue sel = c.query("SELECT dobro(v) AS d FROM it");
            assertEquals(8, sel.asObject().get("result").asArray().get(0).asObject().get("d").asLong());
        } finally {
            c.socket.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
        }
    }

    @Test
    void webSocketDevolveErroDeProgramaComCodigo() throws Exception {
        WsClient c = ws(admin);
        try {
            c.query("CREATE FUNCTION dividir(a, b) AS $$\nreturn a / b\n$$");
            Map<String, AxonValue> err = c.query("RETURN dividir(1, 0)").asObject().get("error").asObject();
            assertEquals(-32040, err.get("code").asLong());
            assertTrue(err.get("message").asString().contains("ZeroDivisionError"));
            c.query("CREATE FUNCTION laco() AS $$\nwhile True:\n    pass\n$$");
            Map<String, AxonValue> limit = c.query("RETURN laco()").asObject().get("error").asObject();
            assertEquals(-32041, limit.get("code").asLong());
        } finally {
            c.socket.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
        }
    }

    @Test
    void webSocketViewerNaoFazCall() throws Exception {
        WsClient a = ws(admin);
        a.query("CREATE PROCEDURE ins() AS $$ db.exec('CREATE it CONTENT {v: 1}') $$");
        a.socket.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
        WsClient v = ws(viewer);
        try {
            AxonValue r = v.query("CALL ins()");
            assertTrue(r.asObject().containsKey("error"), r.toString());
            assertEquals(0, ds.execute("SELECT * FROM it", sessionApp(), null).asArray().size());
            assertTrue(v.query("CREATE FUNCTION x() AS $$ return 1 $$").asObject().containsKey("error"));
        } finally {
            v.socket.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
        }
    }

    private static com.axonbase.core.Session sessionApp() {
        com.axonbase.core.Session s = com.axonbase.core.Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    // ------------------------------------------------------------------ MCP e GraphQL (canais restritos)
    //
    // Esses dois canais não aceitam AxonQL arbitrário (ver ServerTest): a definição (CREATE) é feita por /sql e
    // a chamada pelo canal, com a ferramenta axon_call (MCP) ou o campo call(name:, args:) (GraphQL).

    private void definir(String sql) throws Exception {
        HttpResponse<String> r = post("/sql", admin, sql);
        assertEquals(200, r.statusCode(), r.body());
    }

    private void definirProgramas() throws Exception {
        definir("CREATE FUNCTION dobro(x) AS $$ return x * 2 $$");
        definir("CREATE PROCEDURE grava(v) AS $$ db.exec('CREATE it CONTENT {v: $v}', {'v': v})\n"
            + "return db.one('RETURN dobro($v)', {'v': v})\n$$");
        definir("CREATE PACKAGE util AS $$\nK = 5\ndef soma(a, b):\n    return a + b + K\n$$");
        definir("CREATE FUNCTION dividir(a, b) AS $$\nreturn a / b\n$$");
        definir("CREATE FUNCTION laco() AS $$\nwhile True:\n    pass\n$$");
    }

    private Map<String, AxonValue> mcpCall(String token, String name, String argsJson) throws Exception {
        String body = "{\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"axon_call\",\"arguments\":"
            + "{\"name\":\"" + name + "\",\"args\":" + argsJson + "}}}";
        HttpResponse<String> r = post("/mcp/message", token, body);
        assertEquals(200, r.statusCode(), r.body());
        AxonValue result = AxonJson.parseDocument(r.body()).asObject().get("result");
        return result.isObject() ? result.asObject() : Map.of("value", result);
    }

    @Test
    void mcpChamaFuncaoProcedureEPackage() throws Exception {
        definirProgramas();
        assertTrue(post("/mcp/message", admin, "{\"id\":0,\"method\":\"tools/list\"}").body().contains("axon_call"));
        assertEquals(10, mcpCall(admin, "dobro", "[5]").get("value").asLong());
        assertEquals(8, mcpCall(admin, "grava", "[4]").get("value").asLong());
        assertEquals(1, ds.execute("SELECT * FROM it", sessionApp(), null).asArray().size());
        assertEquals(8, mcpCall(admin, "util::soma", "[1, 2]").get("value").asLong());
    }

    @Test
    void mcpErroDeProgramaEPermissao() throws Exception {
        definirProgramas();
        Map<String, AxonValue> err = mcpCall(admin, "dividir", "[1, 0]");
        assertEquals(-32040, err.get("code").asLong());
        assertTrue(err.get("error").asString().contains("ZeroDivisionError"));
        assertEquals(-32041, mcpCall(admin, "laco", "[]").get("code").asLong());

        // viewer não chama procedure (nada é gravado)
        Map<String, AxonValue> denied = mcpCall(viewer, "grava", "[1]");
        assertTrue(denied.containsKey("error"), denied.toString());
        assertEquals(0, ds.execute("SELECT * FROM it", sessionApp(), null).asArray().size());
        // nome malformado nunca vira AxonQL
        assertTrue(mcpCall(admin, "dobro(1); DELETE it", "[]").containsKey("error"));
    }

    private AxonValue graphqlCall(String token, String field, String variables) throws Exception {
        String body = "{\"query\":" + AxonJson.write(AxonValue.str(field)) + (variables == null ? ""
            : ",\"variables\":" + variables) + "}";
        HttpResponse<String> r = post("/graphql", token, body);
        assertEquals(200, r.statusCode(), r.body());
        return AxonJson.parseDocument(r.body());
    }

    @Test
    void graphqlChamaFuncaoProcedureEPackage() throws Exception {
        definirProgramas();
        assertEquals(10, graphqlCall(admin, "{ call(name: \"dobro\", args: [5]) }", null).asObject().get("data")
            .asObject().get("call").asLong());
        assertEquals(8, graphqlCall(admin, "{ call(name: \"grava\", args: [4]) }", null).asObject().get("data")
            .asObject().get("call").asLong());
        assertEquals(1, ds.execute("SELECT * FROM it", sessionApp(), null).asArray().size());
        assertEquals(8, graphqlCall(admin, "query($a: [Int]) { call(name: \"util::soma\", args: $a) }",
            "{\"a\":[1,2]}").asObject().get("data").asObject().get("call").asLong());
    }

    @Test
    void graphqlErroDeProgramaEPermissao() throws Exception {
        definirProgramas();
        Map<String, AxonValue> err = graphqlCall(admin, "{ call(name: \"dividir\", args: [1, 0]) }", null)
            .asObject().get("errors").asArray().get(0).asObject();
        assertTrue(err.get("message").asString().contains("ZeroDivisionError"));
        assertEquals(-32040, err.get("extensions").asObject().get("code").asLong());
        assertEquals(-32041, graphqlCall(admin, "{ call(name: \"laco\") }", null).asObject().get("errors")
            .asArray().get(0).asObject().get("extensions").asObject().get("code").asLong());

        AxonValue denied = graphqlCall(viewer, "{ call(name: \"grava\", args: [1]) }", null);
        assertTrue(denied.asObject().containsKey("errors"), denied.toString());
        assertEquals(0, ds.execute("SELECT * FROM it", sessionApp(), null).asArray().size());
    }
}
