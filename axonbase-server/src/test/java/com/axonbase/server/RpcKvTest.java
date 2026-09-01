package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Métodos kv_* do RPC: round-trip, escaneo e enrutado ao líder das escritas.
 */
@DisplayName("RPC: key-value público")
class RpcKvTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("kv");
        return s;
    }

    private static AxonValue result(String response) {
        return AxonJson.parseDocument(response).asObject().get("result");
    }

    @Test
    void roundTripSetGetDelViaRpc() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "kv");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        Session s = session();

        rpc.dispatch("{\"id\":1,\"method\":\"kv_set\",\"params\":[\"app\",\"kv\",\"greeting\",\"alive\",3600]}", s);
        String get1 = rpc.dispatch(
            "{\"id\":2,\"method\":\"kv_get\",\"params\":[\"app\",\"kv\",\"greeting\"]}", s);
        assertTrue(result(get1).asString().equals("alive"), "get1=" + get1);

        String del = rpc.dispatch(
            "{\"id\":3,\"method\":\"kv_del\",\"params\":[\"app\",\"kv\",\"greeting\"]}", s);
        assertTrue(del.contains("\"result\":true"), "del=" + del);
        AxonValue get2 = result(rpc.dispatch(
            "{\"id\":4,\"method\":\"kv_get\",\"params\":[\"app\",\"kv\",\"greeting\"]}", s));
        assertTrue(get2 == null || get2.isNull(), "get2=" + get2);
    }

    @Test
    void kvSetRuteseOLiderEkvGetNon() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "kv");
        RpcDispatcher rpc = new RpcDispatcher(ds);
        rpc.clusterStatus(() -> new ClusterStatusProvider.Status("n2", "g", "n1", "10.0.0.1:8000",
            ClusterRole.FOLLOWER, 7, 42, 1, 3, 2));
        Session s = session();

        Map<String, AxonValue> write = AxonJson.parseDocument(rpc.dispatch(
            "{\"id\":1,\"method\":\"kv_set\",\"params\":[\"app\",\"kv\",\"k\",1]}", s))
            .asObject().get("error").asObject();
        assertEquals(RpcDispatcher.NOT_LEADER, write.get("code").asLong());

        // Escritura recusada antes de tocar o estado local.
        AxonValue absent = result(rpc.dispatch(
            "{\"id\":2,\"method\":\"kv_get\",\"params\":[\"app\",\"kv\",\"k\"]}", s));
        assertTrue(absent == null || absent.isNull(), "absent=" + absent);
        // La lectura no se redirige.
        assertFalse(rpc.dispatch(
            "{\"id\":3,\"method\":\"kv_scan\",\"params\":[\"app\",\"kv\",\"\"]}", session())
            .contains("NOT_LEADER"));
    }
}