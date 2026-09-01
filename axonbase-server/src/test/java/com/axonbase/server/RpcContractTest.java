package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.engine.Datastore;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato do wire protocol: PROTOCOL_VERSION + handshake hello, vars tipados
 * nos parâmetros de query e os métodos RPC (let/set/unset e o CRUD).
 */
@DisplayName("Protocolo: handshake, vars tipados e métodos RPC")
class RpcContractTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("dev");
        return s;
    }

    private static AxonValue result(String response) {
        AxonValue v = AxonJson.parseDocument(response);
        return v.asObject().get("result");
    }

    private static AxonValue errorOf(String response) {
        return AxonJson.parseDocument(response).asObject().get("error");
    }

    // ------------------------------------------------------------------
    // handshake: hello, protocolo e métodos expostos
    // ------------------------------------------------------------------

    @Test
    void helloExposeProtocoloVersaoEMetodos() {
        RpcDispatcher rpc = new RpcDispatcher(Datastore.memory());
        assertEquals(1, rpc.getProtocol());
        assertEquals(RpcDispatcher.PROTOCOL_VERSION, rpc.getProtocol());
        assertEquals(RpcDispatcher.PROTOCOL_VERSION, rpc.protocolVersion());
        assertEquals("0.1.0-SNAPSHOT", rpc.serverVersion());

        List<String> methods = rpc.methods();
        assertEquals(rpc.getMethods(), methods);
        for (String m : List.of("ping", "query", "let", "set", "unset", "select", "create",
            "insert", "update", "upsert", "delete", "relate", "signin", "signup",
            "authenticate", "invalidate", "certificate.begin", "certificate.complete", "version", "kv_set")) {
            assertTrue(methods.contains(m), "método faltante no handshake: " + m);
        }

        AxonValue hello = AxonJson.parseDocument(rpc.hello()).asObject().get("hello");
        assertEquals(1, hello.asObject().get("protocol").asLong());
        assertEquals("0.1.0-SNAPSHOT", hello.asObject().get("server").asString());
        assertTrue(hello.asObject().get("methods").isArray());
        assertTrue(hello.asObject().get("methods").asArray().size() >= methods.size());
        assertFalse(rpc.hello().startsWith("{\"notification\""));
    }

    @Test
    void handshakeWebSocketEnviaHelloNoPrimeiroFrame() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "f1-secret");
        WebSocketClient client = new WebSocketClient();
        try {
            client.start();
            Capture capture = new Capture();
            client.connect(capture, URI.create("ws://127.0.0.1:" + srv.port() + "/rpc/ws"))
                .get(10, TimeUnit.SECONDS);
            String first = capture.frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(first, "nenhum frame de handshake recebido");
            AxonValue hello = AxonJson.parseDocument(first).asObject().get("hello");
            assertNotNull(hello, "primeiro frame deveria ser o hello, foi: " + first);
            assertEquals(1, hello.asObject().get("protocol").asLong());
        } finally {
            try {
                client.stop();
            } finally {
                srv.stop();
            }
        }
    }

    // ------------------------------------------------------------------
    // version no request: v1 ok, v2 PROTOCOL_MISMATCH sem efeito
    // ------------------------------------------------------------------

    @Test
    void versionConformeExecuta() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        String resp = rpc.dispatch(
            "{\"id\":1,\"version\":1,\"method\":\"ping\",\"params\":[]}", session());
        assertTrue(resp.contains("\"result\":true"), resp);
    }

    @Test
    void versionDivergenteRecusaSemExecutar() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        Session s = session();

        AxonValue err = errorOf(rpc.dispatch(
            "{\"id\":5,\"version\":2,\"method\":\"ping\",\"params\":[]}", s));
        assertEquals(RpcDispatcher.PROTOCOL_MISMATCH, err.asObject().get("code").asLong());
        assertEquals("PROTOCOL_MISMATCH", err.asObject().get("message").asString());
        assertEquals("PROTOCOL_MISMATCH", err.asObject().get("kind").asString());

        // versão errada não executa nada: nem sequer uma escrita.
        String write = rpc.dispatch(
            "{\"id\":6,\"version\":2,\"method\":\"query\",\"params\":[\"CREATE t CONTENT {x:1}\"]}", s);
        assertTrue(write.contains("PROTOCOL_MISMATCH"), write);
        assertEquals(0, result(rpc.dispatch(
            "{\"id\":7,\"method\":\"query\",\"params\":[\"SELECT * FROM t\"]}", s))
            .asArray().size());
    }

    @Test
    void versionNaoNumericaEhRejeitada() {
        Datastore ds = Datastore.memory();
        RpcDispatcher rpc = new RpcDispatcher(ds);
        AxonValue err = errorOf(rpc.dispatch(
            "{\"id\":8,\"version\":\"um\",\"method\":\"ping\"}", session()));
        assertEquals(RpcDispatcher.PROTOCOL_MISMATCH, err.asObject().get("code").asLong());
    }

    // ------------------------------------------------------------------
    // let / set / unset: variáveis de sessão, sem tocar o backend
    // ------------------------------------------------------------------

    @Test
    void letSetUnsetAlteramVariaveisDaSessao() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        Session s = session();

        rpc.dispatch("{\"id\":1,\"method\":\"let\",\"params\":[\"nome\",\"Ana\"]}", s);
        assertEquals("Ana", result(rpc.dispatch(
            "{\"id\":2,\"method\":\"query\",\"params\":[\"RETURN $nome\"]}", s)).asString());

        rpc.dispatch("{\"id\":3,\"method\":\"set\",\"params\":[\"idade\",30]}", s);
        assertEquals(30L, result(rpc.dispatch(
            "{\"id\":4,\"method\":\"query\",\"params\":[\"RETURN $idade\"]}", s)).asLong());

        rpc.dispatch("{\"id\":5,\"method\":\"unset\",\"params\":[\"nome\"]}", s);
        // NONE e null colapsam em `null` no wire JSON: a leitura devolve null.
        assertTrue(result(rpc.dispatch(
            "{\"id\":6,\"method\":\"query\",\"params\":[\"RETURN $nome\"]}", s)).isNull());
        assertFalse(s.vars().contains("nome"));
        assertTrue(s.vars().contains("idade"));
    }

    // ------------------------------------------------------------------
    // CRUD funcional via RPC: create/insert/update/upsert/delete/select/relate
    // ------------------------------------------------------------------

    @Test
    void createUpdateDeleteSelectViaRpc() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        Session s = session();

        AxonValue created = result(rpc.dispatch(
            "{\"id\":1,\"method\":\"create\",\"params\":[\"person:ana\",{\"name\":\"Ana\",\"age\":30}]}", s));
        assertEquals("person:ana", created.asArray().get(0).asObject().get("id").asString());

        AxonValue updated = result(rpc.dispatch(
            "{\"id\":2,\"method\":\"update\",\"params\":[\"person:ana\",{\"age\":31}]}", s))
            .asArray().get(0);
        assertEquals(31L, updated.asObject().get("age").asLong());

        AxonValue rows = result(rpc.dispatch(
            "{\"id\":3,\"method\":\"select\",\"params\":[\"person\"]}", s));
        assertEquals(1, rows.asArray().size());
        assertEquals("Ana", rows.asArray().get(0).asObject().get("name").asString());

        AxonValue del = result(rpc.dispatch(
            "{\"id\":4,\"method\":\"delete\",\"params\":[\"person:ana\"]}", s));
        assertTrue(del.isArray() && del.asArray().size() >= 0);
        assertNull(errorOf(rpc.dispatch(
            "{\"id\":5,\"method\":\"select\",\"params\":[\"SELECT * FROM person\"]}", s)));
    }

    @Test
    void insertEUpsertViaRpc() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        Session s = session();

        // INSERT INTO
        AxonValue inserted = result(rpc.dispatch(
            "{\"id\":2,\"method\":\"insert\",\"params\":[\"person\",{\"name\":\"Bob\",\"age\":20}]}", s));
        assertEquals(1, inserted.asArray().size());

        // UPSERT em chave conhecida deixa-se criar por não existir.
        AxonValue upserted = result(rpc.dispatch(
            "{\"id\":3,\"method\":\"upsert\",\"params\":[\"person:bob\",{\"name\":\"Bob\",\"age\":21}]}", s))
            .asArray().get(0);
        assertEquals(21L, upserted.asObject().get("age").asLong());

        assertEquals(2, result(rpc.dispatch(
            "{\"id\":4,\"method\":\"select\",\"params\":[\"person\"]}", s)).asArray().size());
    }

    @Test
    void relateViaRpc() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        Session s = session();
        result(rpc.dispatch(
            "{\"id\":1,\"method\":\"create\",\"params\":[\"person:ana\",{}]}", s));
        result(rpc.dispatch(
            "{\"id\":2,\"method\":\"create\",\"params\":[\"person:bob\",{}]}", s));

        AxonValue edge = result(rpc.dispatch(
            "{\"id\":3,\"method\":\"relate\",\"params\":[\"person:ana\",\"friend\",\"person:bob\",{\"since\":2024}]}", s));
        assertEquals("person:ana", edge.asObject().get("in").asString());
        assertEquals("person:bob", edge.asObject().get("out").asString());
        assertEquals(2024L, edge.asObject().get("since").asLong());
    }

    // ------------------------------------------------------------------
    // vars tipados: $datetime, $duration, $uuid, $table, $record, $decimal, $bytes
    // ------------------------------------------------------------------

    @Test
    void varsTipadosRoundTripNoQuery() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        Session s = session();

        String req = "{\"id\":1,\"method\":\"query\",\"version\":1,\"params\":["
            + "\"RETURN $v\", {\"v\":{"
            + "\"dt\":{\"$datetime\":\"2026-01-01T00:00:00Z\"},"
            + "\"dur\":{\"$duration\":\"1h\"},"
            + "\"id\":{\"$uuid\":\"73e9a5c7-91f2-4b6e-b9a9-123456789abc\"},"
            + "\"tab\":{\"$table\":\"person\"},"
            + "\"rec\":{\"$record\":\"person:ana\"},"
            + "\"dec\":{\"$decimal\":\"123.45\"},"
            + "\"bin\":{\"$bytes\":\"SGk=\"}}}"
            + "]}";
        rpc.dispatch(req, s);
        // A variável chega tipada ao executor (session.vars)
        Map<String, AxonValue> typed = s.vars().get("v").asObject();
        assertTrue(typed.get("dt").isDatetime());
        assertTrue(typed.get("dur").isDuration());
        assertTrue(typed.get("id").isUuid());
        assertTrue(typed.get("tab").isTable());
        assertTrue(typed.get("rec").isRecordId());
        assertTrue(typed.get("dec").isNumber());
        assertTrue(typed.get("bin").isBytes());

        // e o JSON do echo preserva os marcadores no wire
        String echo = rpc.dispatch("{\"id\":2,\"method\":\"query\",\"params\":["
            + "\"RETURN $v\", {\"v\":{\"$datetime\":\"2026-01-01T00:00:00Z\"}}]}", s);
        assertTrue(echo.contains("\"$datetime\":\"2026-01-01T00:00:00Z\""), echo);

        AxonValue decResult = result(rpc.dispatch(
            "{\"id\":3,\"method\":\"query\",\"params\":[\"RETURN $dec\","
                + "{\"dec\":{\"$decimal\":\"123.45\"}}]}", s));
        assertEquals(new java.math.BigDecimal("123.45"), decResult.asDecimal());
    }

    // ------------------------------------------------------------------
    // signup / invalidate
    // ------------------------------------------------------------------

    @Test
    void signupCriaUsuarioEInvalidateLimpaSessao() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        UserStore store = new UserStore(ds.authCatalog());
        store.register("root", "root-pass");
        AuthService auth = new AuthService("f1-secret", store);
        RpcDispatcher rpc = new RpcDispatcher(ds, auth, true);
        Session s = session();

        String rootToken = result(rpc.dispatch(
            "{\"id\":0,\"method\":\"signin\",\"params\":[{\"user\":\"root\",\"pass\":\"root-pass\"}]}", s)).asString();
        rpc.dispatch("{\"id\":0,\"method\":\"authenticate\",\"params\":[\"" + rootToken + "\"]}", s);

        AxonValue token = result(rpc.dispatch(
            "{\"id\":1,\"method\":\"signup\",\"params\":[{\"user\":\"alice\",\"pass\":\"s3cr3t\",\"ns\":\"app\",\"db\":\"dev\"}]}", s));
        assertTrue(token.isString() && !token.asString().isBlank(), "token=" + token);

        // o token criado autentica numa sessão limpa
        Session s2 = session();
        rpc.dispatch("{\"id\":2,\"method\":\"authenticate\",\"params\":[\"" + token.asString() + "\"]}", s2);
        assertNotNull(s2.auth());

        // invalidate remove a relação local, sem revogar o token no servidor
        rpc.dispatch("{\"id\":3,\"method\":\"invalidate\",\"params\":[]}", s2);
        assertNull(s2.auth());

        // a mesma token volta a autenticar depois (revogação é só local)
        Session s3 = session();
        rpc.dispatch("{\"id\":4,\"method\":\"authenticate\",\"params\":[\"" + token.asString() + "\"]}", s3);
        assertNotNull(s3.auth());
    }

    @Test
    void certificateBeginCriaDesafioSemCertificadoQuandoNaoHaTruststore() {
        RpcDispatcher rpc = new RpcDispatcher(Datastore.memory());
        Session s = session();

        AxonValue challenge = result(rpc.dispatch(
            "{\"id\":1,\"method\":\"certificate.begin\",\"params\":[{\"store\":\"ausente\"}]}", s));

        assertTrue(challenge.isObject());
        assertTrue(challenge.asObject().get("id").isString());
        assertTrue(challenge.asObject().get("challenge").isString());
    }

    // ------------------------------------------------------------------
    // conformidade de roteamento: escritas só no líder
    // ------------------------------------------------------------------

    @Test
    void crudEscritasRoteiamAoLiderESelectNao() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "dev");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        rpc.clusterStatus(() -> new ClusterStatusProvider.Status("n2", "g", "n1", "10.0.0.1:8000",
            ClusterRole.FOLLOWER, 7, 42, 1, 3, 2));
        Session s = session();

        for (String method : List.of("create", "insert", "update", "upsert", "delete", "relate")) {
            Map<String, AxonValue> err = errorOf(rpc.dispatch(
                "{\"id\":1,\"method\":\"" + method + "\",\"params\":[\"t\",{}]}", s)).asObject();
            assertEquals(RpcDispatcher.NOT_LEADER, err.get("code").asLong(),
                method + " deveria ser roteada ao líder");
        }

        // leitura e variáveis não são roteadas
        assertNull(errorOf(rpc.dispatch(
            "{\"id\":2,\"method\":\"select\",\"params\":[\"t\"]}", s)));
        assertNull(errorOf(rpc.dispatch(
            "{\"id\":3,\"method\":\"let\",\"params\":[\"x\",1]}", s)));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static final class Capture extends WebSocketAdapter {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

        @Override
        public void onWebSocketText(String message) {
            frames.add(message);
        }
    }
}
