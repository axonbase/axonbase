package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PERMISSIONS que dependem de um dado que o token não tem negam, em vez de casar a falta com um campo ausente. */
class PermissionsMissingClaimTest {

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session token(String tenant) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("v"));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("app"));
        claims.put("db", AxonValue.str("main"));
        claims.put("roles", AxonValue.str("editor"));
        if (tenant != null) {
            claims.put("tenant", AxonValue.str(tenant));
        }
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.execute("CREATE doc:1 CONTENT {v: 1}", local(), null);
        ds.execute("CREATE doc:2 CONTENT {v: 2, tenant: 'a'}", local(), null);
        ds.execute("DEFINE TABLE doc SCHEMALESS PERMISSIONS FOR select WHERE tenant = $auth.tenant "
            + "FOR create WHERE tenant = $auth.tenant FOR update WHERE tenant = $token.tenant "
            + "FOR delete WHERE tenant = $auth.tenant", local(), null);
        return ds;
    }

    private static int visible(Datastore ds, String table, Session s) {
        return ds.execute("SELECT * FROM " + table, s, null).asArray().size();
    }

    @Test
    void selectSemODadoNoTokenNaoDevolveLinhas() {
        Datastore ds = ds();

        assertEquals(0, visible(ds, "doc", token(null)));
        assertEquals(0, visible(ds, "doc", local()));
        assertEquals(1, visible(ds, "doc", token("a")));
    }

    @Test
    void escritaSemODadoNoTokenNaoPassa() {
        Datastore ds = ds();

        AxonError refused = assertThrows(AxonError.class,
            () -> ds.execute("CREATE doc:3 CONTENT {v: 3}", token(null), null));
        ds.execute("UPDATE doc SET v = 9", token(null), null);
        ds.execute("DELETE doc", token(null), null);

        assertTrue(refused.getMessage().contains("permission denied"), refused.getMessage());
        assertEquals(1L, ds.execute("RETURN doc:1.v", local(), null).asLong());
        assertEquals(2L, ds.execute("RETURN doc:2.v", local(), null).asLong());
    }

    @Test
    void condicaoQueNaoDependeDoTokenNaoMudaDeSignificado() {
        Datastore ds = ds();
        ds.execute("CREATE livre:1 CONTENT {v: 1}", local(), null);
        ds.execute("DEFINE TABLE livre SCHEMALESS PERMISSIONS FOR select WHERE apagado = removido", local(), null);

        assertEquals(1, visible(ds, "livre", token(null)));
        assertEquals(1, visible(ds, "livre", local()));
    }
}
