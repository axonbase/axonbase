package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.common.Messages;
import com.axonbase.common.AxonError;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void rpcEspecializadoExigeAutenticacaoQuandoConfigurada() {
        Messages.setLanguage("pt-BR");
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        RpcDispatcher rpc = new RpcDispatcher(ds,
            new AuthService("secret", new UserStore(ds.authCatalog())), true);
        Session session = Session.create();
        session.namespace("app");
        session.database("main");

        String response = rpc.dispatch("{\"id\":1,\"method\":\"select\",\"params\":[\"orders\"]}", session);

        assertTrue(response.contains(Messages.get("auth_required")));
    }

    @Test
    void parametrosInvalidosERrosDoMotorPermanecemNoEnvelopeRpc() {
        Datastore ds = Datastore.memory();
        RpcDispatcher rpc = new RpcDispatcher(ds);
        Session s = Session.create();

        var invalid = AxonJson.parseDocument(rpc.dispatch("{\"id\":1,\"method\":\"kill\",\"params\":[1]}", s));
        assertEquals(-32602, invalid.asObject().get("error").asObject().get("code").asLong());

        var unknown = AxonJson.parseDocument(rpc.dispatch("{\"id\":2,\"method\":\"nope\",\"params\":[]}", s));
        assertEquals(-32601, unknown.asObject().get("error").asObject().get("code").asLong());
    }

    @Test
    void preservaCodigoAxonErrorEEscapaControlesNaRespostaRpc() {
        RpcDispatcher rpc = new RpcDispatcher(Datastore.memory());

        var parseError = AxonJson.parseDocument(rpc.dispatch(
            "{\"id\":1,\"method\":\"query\",\"params\":[\"CREATE\"]}", Session.create()))
            .asObject().get("error").asObject();
        assertEquals(AxonError.parse("x").code(), parseError.get("code").asLong());

        var escaped = AxonJson.parseDocument(rpc.dispatch(
            "{\"id\":2,\"method\":\"unknown\\nmethod\",\"params\":[]}", Session.create()))
            .asObject().get("error").asObject();
        assertEquals("método não suportado: unknown\nmethod", escaped.get("message").asString());
    }
}
