package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Os métodos kv_* trazem ns e db nos parâmetros: o escopo do token precisa valer para eles. */
class RpcKvScopeHttpTest {

    private Datastore ds;
    private AxonServer server;
    private SecurityHttp http;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("loja", "ops");
        ds.createDatabase("outro", "base");
        ds.kvSet(null, "outro", "base", "segredo", AxonValue.str("valor-alheio"), null);
        ds.authCatalog().defineUser("editor", AuthCatalog.Scope.DATABASE, "loja", "ops", "pw",
            List.of("editor"), List.of());
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        http = new SecurityHttp(server.port());
        token = http.signin("loja", "ops", "editor", "pw");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private String rpc(String bearer, String method, String params) throws Exception {
        return http.post("/rpc", "{\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}",
            SecurityHttp.bearer(bearer, "loja", "ops")).body();
    }

    private String alheio() {
        AxonValue value = ds.kvGet(null, "outro", "base", "segredo");
        return value.isString() ? value.asString() : "";
    }

    @Test
    void tokenDeBancoNaoLeKvDeOutroBanco() throws Exception {
        String get = rpc(token, "kv_get", "[\"outro\",\"base\",\"segredo\"]");
        String scan = rpc(token, "kv_scan", "[\"outro\",\"base\",\"\"]");

        assertTrue(get.contains("\"error\""), get);
        assertFalse(get.contains("valor-alheio"), get);
        assertTrue(scan.contains("\"error\""), scan);
        assertFalse(scan.contains("valor-alheio"), scan);
        assertTrue(get.contains("\"id\":7"), "o erro leva o id da chamada: " + get);
    }

    @Test
    void tokenDeBancoNaoGravaNemApagaKvDeOutroBanco() throws Exception {
        String set = rpc(token, "kv_set", "[\"outro\",\"base\",\"segredo\",\"trocado\"]");
        String del = rpc(token, "kv_del", "[\"outro\",\"base\",\"segredo\"]");

        assertTrue(set.contains("\"error\""), set);
        assertTrue(del.contains("\"error\""), del);
        assertEquals("valor-alheio", alheio());
    }

    @Test
    void tokenDeBancoUsaKvDoProprioBancoERootUsaQualquerUm() throws Exception {
        String set = rpc(token, "kv_set", "[\"loja\",\"ops\",\"k\",\"v\"]");
        String get = rpc(token, "kv_get", "[\"loja\",\"ops\",\"k\"]");
        String root = rpc(http.rootToken("pw"), "kv_get", "[\"outro\",\"base\",\"segredo\"]");

        assertFalse(set.contains("\"error\""), set);
        assertTrue(get.contains("\"v\""), get);
        assertTrue(root.contains("valor-alheio"), root);
    }
}
