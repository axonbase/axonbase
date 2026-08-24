package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcScopeTest {

    @Test
    void tokenDeBancoBloqueiaTrocaDeBancoNoMetodoUse() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.createDatabase("app", "other");
        Session setup = Session.create();
        setup.namespace("app");
        setup.database("main");
        ds.execute("DEFINE USER alice ON DATABASE PASSWORD \"senha\"", setup, null);

        AuthService auth = new AuthService("secret", new UserStore(ds.authCatalog()));
        RpcDispatcher rpc = new RpcDispatcher(ds, auth, true);
        Session session = Session.create();
        session.namespace("app");
        session.database("main");

        String signin = rpc.dispatch("{\"id\":1,\"method\":\"signin\",\"params\":[{\"user\":\"alice\",\"pass\":\"senha\"}]}", session);
        String token = AxonJson.parseDocument(signin).asObject().get("result").asString();
        String authenticated = rpc.dispatch("{\"id\":2,\"method\":\"authenticate\",\"params\":[\""
            + token + "\"]}", session);
        assertTrue(!authenticated.contains("error"));

        String denied = rpc.dispatch("{\"id\":3,\"method\":\"use\",\"params\":[\"app\",\"other\"]}", session);
        assertTrue(denied.contains("error"));
        assertTrue(session.database().equals("main"));
    }
}
