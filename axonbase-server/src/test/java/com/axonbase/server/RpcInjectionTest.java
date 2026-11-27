package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Chaves, textos e valores marcados que chegam pelo RPC são dados, nunca comando. */
@DisplayName("RPC: valores do cliente não viram comando")
class RpcInjectionTest {

    private final Datastore ds = Datastore.memory();
    private final RpcDispatcher rpc = new RpcDispatcher(ds);
    private final Session session = session();

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    /** Monta o JSON à mão para o envelope {@code $table} chegar como o cliente o envia. */
    private void call(String method, String paramsJson) {
        rpc.dispatch("{\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}", session);
    }

    private static String json(String text) {
        return AxonJson.write(AxonValue.str(text));
    }

    private List<AxonValue> rows(String table) {
        return ds.execute("SELECT * FROM " + table, session, null).asArray();
    }

    @Test
    void chaveDeObjetoHostilEGravadaComoChave() {
        ds.execute("CREATE users:1 SET a = 1", session, null);
        String key = "x\": 1}; DELETE users; CREATE person CONTENT {\"y";

        call("create", "[\"person\",{" + json(key) + ":1}]");

        assertEquals(1, rows("users").size());
        assertEquals(1, rows("person").size());
        Map<String, AxonValue> stored = new LinkedHashMap<>(rows("person").get(0).asObject());
        assertEquals(1, stored.get(key).asLong());
    }

    @Test
    void valoresMarcadosHostisNaoViramComando() {
        ds.execute("CREATE users:1 SET a = 1", session, null);
        String hostile = "x}; DELETE users; CREATE z CONTENT {a: 1";

        call("create", "[\"person\",{\"t\":{\"$table\":" + json(hostile) + "}}]");
        call("create", "[\"person\",{\"r\":{\"$record\":" + json("a:" + hostile) + "}}]");
        call("create", "[\"person\",{\"r\":{\"$record\":" + json(hostile + ":1") + "}}]");

        assertEquals(1, rows("users").size());
    }

    @Test
    void propriedadeDeArestaHostilERecusada() {
        ds.execute("CREATE users:1 SET a = 1; CREATE users:2 SET a = 2", session, null);

        call("relate", "[\"users:1\",\"knows\",\"users:2\",{" + json("a = 1; DELETE users; LET $b") + ":1}]");

        assertEquals(2, rows("users").size());
    }

    @Test
    void idTerminadoEmComentarioNaoApagaSemOFiltro() {
        ds.execute("CREATE users:1 SET a = 1", session, null);

        call("delete", "[\"users--\",\"a = 999\"]");

        assertEquals(1, rows("users").size());
    }
}
