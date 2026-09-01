package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.cluster.RaftCommitCoordinator;
import com.axonbase.core.cluster.RaftGroup;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comportamento do transporte quando o nó não é o líder, e o que os endpoints de
 * readiness e status realmente reportam.
 */
@DisplayName("Servidor: NOT_LEADER e status de cluster")
class ClusterEndpointTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER).build();

    private static ClusterStatusProvider follower(String leader, String address) {
        return () -> new ClusterStatusProvider.Status("n2", "g", leader, address,
            ClusterRole.FOLLOWER, 7, 42, 1, 3, 2);
    }

    private static ClusterStatusProvider leader(int active) {
        return () -> new ClusterStatusProvider.Status("n1", "g", "n1", "127.0.0.1:9001",
            ClusterRole.LEADER, 7, 42, active, 3, 2);
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    // ------------------------------------------------------------------
    // RPC: erro estruturado com o líder
    // ------------------------------------------------------------------

    @Test
    void rpcRecusaEscritaNoSeguidorComErroEstruturado() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        rpc.clusterStatus(() -> follower("n1", "10.0.0.1:8000").status());

        String response = rpc.dispatch(
            "{\"id\":1,\"method\":\"query\",\"params\":[\"CREATE t CONTENT {x: 1}\"]}", session());
        Map<String, AxonValue> body = AxonJson.parseDocument(response).asObject()
            .get("error").asObject();
        assertEquals(RpcDispatcher.NOT_LEADER, body.get("code").asLong());
        assertEquals("NOT_LEADER", body.get("kind").asString());
        assertEquals("n1", body.get("leader").asString());
        assertEquals("10.0.0.1:8000", body.get("leader_address").asString());

        // A escrita foi recusada antes de tocar o estado local.
        assertEquals(0, ds.execute("SELECT * FROM t", session(), null).asArray().size());
    }

    @Test
    void rpcPermiteLeituraLiveEKillNoSeguidor() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        rpc.clusterStatus(() -> follower("n1", "10.0.0.1:8000").status());

        assertFalse(rpc.dispatch("{\"id\":1,\"method\":\"query\",\"params\":[\"SELECT * FROM t\"]}",
            session()).contains("NOT_LEADER"));
        assertFalse(rpc.dispatch("{\"id\":2,\"method\":\"live\",\"params\":[\"t\"]}", session())
            .contains("NOT_LEADER"));
        assertFalse(rpc.dispatch("{\"id\":3,\"method\":\"ping\"}", session()).contains("NOT_LEADER"));
    }

    @Test
    void rpcSemLiderConhecidoRecusaSemEndereco() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        rpc.clusterStatus(() -> follower("", "").status());

        Map<String, AxonValue> body = AxonJson.parseDocument(rpc.dispatch(
            "{\"id\":1,\"method\":\"begin\"}", session())).asObject().get("error").asObject();
        assertEquals(RpcDispatcher.NOT_LEADER, body.get("code").asLong());
        assertEquals("", body.get("leader_address").asString());
        assertTrue(body.get("message").asString().contains("nenhum líder"));
    }

    /**
     * Perda de liderança descoberta só no commit: o dispatcher traduz a exceção do
     * consenso no mesmo erro estruturado do caminho preventivo.
     */
    @Test
    void rpcTraduzNotLeaderVindoDoConsenso() {
        Map<String, VersionedKvBackend> members = new LinkedHashMap<>();
        members.put("n1", new MemoryBackend());
        members.put("n2", new MemoryBackend());
        RaftGroup group = RaftGroup.over("n/d", members);
        Datastore ds = new Datastore(members.get("n2"));
        ds.commitCoordinator(new RaftCommitCoordinator(group), "n2");

        // O dispatcher acredita ser líder, mas o grupo elegeu n1.
        RpcDispatcher rpc = new RpcDispatcher(ds);
        rpc.clusterStatus(() -> leader(3).status());

        Map<String, AxonValue> body = AxonJson.parseDocument(rpc.dispatch(
            "{\"id\":1,\"method\":\"query\",\"params\":[\"CREATE t CONTENT {x: 1}\"]}", session()))
            .asObject().get("error").asObject();
        assertEquals(RpcDispatcher.NOT_LEADER, body.get("code").asLong());
        assertEquals("n1", body.get("leader").asString());
    }

    @Test
    void rpcTraduzFaltaDeQuorumNoCommitExplicito() {
        Map<String, VersionedKvBackend> members = new LinkedHashMap<>();
        members.put("n1", new MemoryBackend());
        members.put("n2", new MemoryBackend());
        members.put("n3", new MemoryBackend());
        RaftGroup group = RaftGroup.over("n/d", members);
        Datastore ds = new Datastore(members.get("n1"));
        ds.commitCoordinator(new RaftCommitCoordinator(group), "n1");
        group.stop("n2");
        group.stop("n3");

        RpcDispatcher rpc = new RpcDispatcher(ds);
        rpc.clusterStatus(() -> leader(1).status());
        Session session = session();
        rpc.dispatch("{\"id\":1,\"method\":\"begin\"}", session);
        rpc.dispatch("{\"id\":2,\"method\":\"query\",\"params\":[\"CREATE t CONTENT {x: 1}\"]}",
            session);
        Map<String, AxonValue> body = AxonJson.parseDocument(
            rpc.dispatch("{\"id\":3,\"method\":\"commit\"}", session)).asObject()
            .get("error").asObject();
        assertEquals(RpcDispatcher.NO_QUORUM, body.get("code").asLong());
        assertFalse(session.inTransaction(), "transação sem quórum deve ser desfeita");
    }

    /**
     * O WebSocket compartilha o dispatcher, então a mesma resposta estruturada é o
     * que o cliente recebe no frame.
     */
    @Test
    void webSocketEnviaOMesmoErroEstruturado() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        WsRpcServlet servlet = new WsRpcServlet(ds);
        servlet.dispatcher().clusterStatus(() -> follower("n1", "10.0.0.1:8000").status());

        String frame = servlet.dispatcher().dispatch(
            "{\"id\":9,\"method\":\"query\",\"params\":[\"CREATE t CONTENT {x: 1}\"]}", session());
        Map<String, AxonValue> body = AxonJson.parseDocument(frame).asObject()
            .get("error").asObject();
        assertEquals(RpcDispatcher.NOT_LEADER, body.get("code").asLong());
        assertEquals("10.0.0.1:8000", body.get("leader_address").asString());
    }

    // ------------------------------------------------------------------
    // HTTP: 307 para o líder, 503 estruturado sem líder
    // ------------------------------------------------------------------

    @Test
    void httpRedirecionaEscritaParaOLiderCom307() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            server.clusterStatus(follower("n1", "10.0.0.1:8000"));
            HttpResponse<String> sql = post(server.port(), "/sql", "CREATE t CONTENT {x: 1}");
            assertEquals(307, sql.statusCode());
            assertEquals("http://10.0.0.1:8000/sql", sql.headers().firstValue("Location").orElse(""));
            assertEquals("n1", sql.headers().firstValue("Axon-Leader").orElse(""));

            HttpResponse<String> table = post(server.port(), "/table/t", "{\"x\":1}");
            assertEquals(307, table.statusCode());
            assertEquals("http://10.0.0.1:8000/table/t",
                table.headers().firstValue("Location").orElse(""));

            // Leitura continua sendo servida localmente pelo seguidor.
            assertEquals(200, post(server.port(), "/sql", "SELECT * FROM t").statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void httpSemLiderRespondeErroEstruturado() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            server.clusterStatus(follower("", ""));
            HttpResponse<String> response = post(server.port(), "/sql", "CREATE t CONTENT {x: 1}");
            assertEquals(503, response.statusCode());
            Map<String, AxonValue> body = AxonJson.parseDocument(response.body()).asObject();
            assertEquals("NOT_LEADER", body.get("kind").asString());
            assertEquals("", body.get("leader").asString());
        } finally {
            server.stop();
        }
    }

    @Test
    void readyEStatusRefletemORuntimeReal() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            server.clusterStatus(leader(3));
            HttpResponse<String> ready = get(server.port(), "/ready");
            assertEquals(200, ready.statusCode());
            assertTrue(ready.body().contains("\"ready\":true"), ready.body());

            Map<String, AxonValue> status = AxonJson.parseDocument(
                get(server.port(), "/status").body()).asObject();
            assertEquals("n1", status.get("node_id").asString());
            assertEquals("g", status.get("cluster_id").asString());
            assertEquals("LEADER", status.get("role").asString());
            assertEquals("n1", status.get("leader").asString());
            assertEquals("127.0.0.1:9001", status.get("leader_address").asString());
            assertEquals(7, status.get("term").asLong());
            assertEquals(42, status.get("commit_index").asLong());
            assertEquals(3, status.get("active").asLong());
            assertEquals(3, status.get("members").asLong());
            assertEquals(2, status.get("quorum").asLong());

            // Líder que perdeu a maioria não está pronto.
            server.clusterStatus(leader(1));
            assertEquals(503, get(server.port(), "/ready").statusCode());

            // Seguidor que conhece o líder está pronto para leituras.
            server.clusterStatus(follower("n1", "10.0.0.1:8000"));
            assertEquals(200, get(server.port(), "/ready").statusCode());

            // Durante uma eleição, ninguém está pronto.
            server.clusterStatus(follower("", ""));
            assertEquals(503, get(server.port(), "/ready").statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void semClusterConfiguradoOServidorSegueLocalEPronto() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            assertEquals(200, get(server.port(), "/ready").statusCode());
            assertEquals(200, post(server.port(), "/sql", "CREATE t CONTENT {x: 1}").statusCode());
        } finally {
            server.stop();
        }
    }

    // ------------------------------------------------------------------
    // Admin dinámico: JOIN / LEAVE de membros
    // ------------------------------------------------------------------

    @Test
    void adminSemRuntimeRespondeInestruturado() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            HttpResponse<String> response = post(server.port(), "/admin/cluster/join",
                "{\"node\":\"n2\",\"address\":\"127.0.0.1:9001\"}");
            assertEquals(503, response.statusCode());
            assertEquals("ERR", AxonJson.parseDocument(response.body()).asObject()
                .get("status").asString());
        } finally {
            server.stop();
        }
    }

    @Test
    void adminJoIYLeaveAlteranOLinea() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        Path dir = Files.createTempDirectory("srv");
        ClusterRuntime rt = new ClusterRuntime(new ClusterConfig("n1", "g",
            new InetSocketAddress("127.0.0.1", 0), java.util.List.of()), dir);
        try {
            server.clusterStatus(rt);
            awaitLeader(rt);
            assertEquals(1, rt.members());

            HttpResponse<String> join = post(server.port(), "/admin/cluster/join",
                "{\"node\":\"n2\",\"address\":\"127.0.0.1:9090\"}");
            assertEquals(200, join.statusCode());
            AxonValue joined = AxonJson.parseDocument(join.body());
            assertEquals(2, joined.asObject().get("members").asLong());
            assertEquals(2, joined.asObject().get("quorum").asLong());
            assertEquals(2, rt.members());

            HttpResponse<String> leave = post(server.port(), "/admin/cluster/leave",
                "{\"address\":\"127.0.0.1:9090\"}");
            assertEquals(200, leave.statusCode());
            AxonValue gone = AxonJson.parseDocument(leave.body());
            assertEquals(1, gone.asObject().get("members").asLong());
            assertEquals(1, gone.asObject().get("quorum").asLong());
            assertEquals(1, rt.members());

            // As métricas de raft exponse co runtime acoplado.
            HttpResponse<String> metrics = get(server.port(), "/metrics");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("axe_raft_lag "), metrics.body());
            assertTrue(metrics.body().contains("axe_raft_election_started_total "), metrics.body());
            assertTrue(metrics.body().contains("axe_raft_quorum_unavailable_total "), metrics.body());
            assertTrue(metrics.body().contains("axe_raft_snapshot_bytes_total "), metrics.body());
        } finally {
            server.stop();
            rt.close();
        }
    }

    @Test
    void adminExigeTokenCandoAuthEstaActiva() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            HttpResponse<String> response = post(server.port(), "/admin/cluster/join",
                "{\"node\":\"n2\",\"address\":\"127.0.0.1:9090\"}");
            assertEquals(401, response.statusCode());
        } finally {
            server.stop();
        }
    }

    private static void awaitLeader(ClusterRuntime rt) throws Exception {
        for (int attempt = 0; attempt < 80 && rt.role() != ClusterRole.LEADER; attempt++) {
            Thread.sleep(60);
        }
    }

    private static HttpResponse<String> post(int port, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header("Axon-Ns", "n")
            .header("Axon-Db", "d")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
