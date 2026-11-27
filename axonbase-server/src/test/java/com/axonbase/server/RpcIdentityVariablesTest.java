package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pelo RPC, $auth é o do token e o método let não o substitui. */
class RpcIdentityVariablesTest {

    @Test
    void letDoRpcNaoSubstituiAuth() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        Session setup = Session.create();
        setup.namespace("app");
        setup.database("main");
        ds.execute("CREATE DATA RULE eu APPLY dono = $auth.id", setup, null);
        ds.execute("CREATE t:1 CONTENT {dono: 'outro'}", setup, null);
        ds.authCatalog().defineUser("w", AuthCatalog.Scope.DATABASE, "app", "main", "Test-pw-1",
            List.of("editor"), List.of("eu"));

        AuthService auth = new AuthService("secret", new UserStore(ds.authCatalog()));
        RpcDispatcher rpc = new RpcDispatcher(ds, auth, true);
        Session session = Session.create();
        session.namespace("app");
        session.database("main");
        String signin = rpc.dispatch(
            "{\"id\":1,\"method\":\"signin\",\"params\":[{\"user\":\"w\",\"pass\":\"Test-pw-1\"}]}", session);
        String token = result(signin).asString();
        rpc.dispatch("{\"id\":2,\"method\":\"authenticate\",\"params\":[\"" + token + "\"]}", session);

        String who = rpc.dispatch("{\"id\":3,\"method\":\"query\",\"params\":[\"RETURN $auth.id\"]}", session);
        assertEquals("w", result(who).asString(), who);

        String let = rpc.dispatch(
            "{\"id\":4,\"method\":\"let\",\"params\":[\"auth\",{\"id\":\"outro\"}]}", session);
        assertTrue(let.contains("error"), let);

        String vars = rpc.dispatch(
            "{\"id\":5,\"method\":\"query\",\"params\":[\"SELECT * FROM t\",{\"auth\":{\"id\":\"outro\"}}]}",
            session);
        assertTrue(vars.contains("error"), vars);

        String rows = rpc.dispatch("{\"id\":6,\"method\":\"query\",\"params\":[\"SELECT * FROM t\"]}", session);
        assertTrue(!rows.contains("outro"), rows);
    }

    private static AxonValue result(String response) {
        return AxonJson.parseDocument(response).asObject().get("result");
    }
}
