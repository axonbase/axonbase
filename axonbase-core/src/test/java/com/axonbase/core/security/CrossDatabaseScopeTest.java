package com.axonbase.core.security;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O nome {@code banco.tabela} só alcança outro banco para uma identidade cujo escopo cubra aquele banco. */
class CrossDatabaseScopeTest {

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session session(String scope, String roles) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("quem"));
        claims.put("scope", AxonValue.str(scope));
        if (!"ROOT".equals(scope)) {
            claims.put("ns", AxonValue.str("app"));
        }
        if ("DATABASE".equals(scope)) {
            claims.put("db", AxonValue.str("main"));
        }
        claims.put("roles", AxonValue.str(roles));
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.createDatabase("app", "outro");
        Session other = local();
        other.database("outro");
        ds.execute("CREATE segredo:1 CONTENT {v: 'alheio'}", other, null);
        ds.execute("CREATE semente:1 CONTENT {v: 'alheio'}", local(), null);
        return ds;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM outro.segredo",
        "SELECT * FROM \"outro\".\"segredo\":1",
        "RETURN (SELECT * FROM outro.segredo)",
        "SELECT * FROM semente WHERE v IN (SELECT VALUE v FROM outro.segredo)",
        "SELECT (SELECT VALUE v FROM outro.segredo) AS roubo FROM semente",
        "SELECT * FROM (SELECT * FROM outro.segredo)",
        "SELECT count() FROM outro.segredo GROUP ALL",
        "EXPLAIN SELECT * FROM outro.segredo",
        "LET $x = (SELECT * FROM outro.segredo)",
        "CREATE copia SET dado = (SELECT * FROM outro.segredo)",
        "SELECT * FROM fantasma.segredo"})
    void usuarioDeBancoNaoAlcancaOutroBancoDoNamespace(String statement) {
        Datastore ds = ds();

        for (String role : new String[] {"viewer", "editor", "admin"}) {
            if (role.equals("viewer") && statement.startsWith("CREATE")) {
                continue;
            }
            RuntimeException refused = assertThrows(RuntimeException.class,
                () -> ds.execute(statement, session("DATABASE", role), null), statement);
            String name = statement.contains("fantasma") ? "app/fantasma" : "app/outro";
            assertTrue(refused.getMessage().contains(name), refused.getMessage());
        }

        assertNull(ds.findDatabase("app", "fantasma"));
        assertEquals(0, ds.execute("SELECT * FROM copia", local(), null).asArray().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOT", "NAMESPACE"})
    void quemAlcancaONamespaceContinuaLendo(String scope) {
        Datastore ds = ds();

        AxonValue rows = ds.execute("SELECT * FROM outro.segredo", session(scope, "viewer"), null);

        assertEquals(1, rows.asArray().size());
    }

    @Test
    void modoSemAutenticacaoContinuaLendo() {
        assertEquals(1, ds().execute("SELECT * FROM outro.segredo", local(), null).asArray().size());
    }

    @Test
    void usuarioDeBancoContinuaCitandoOProprioBanco() {
        AxonValue rows = ds().execute("SELECT * FROM main.semente", session("DATABASE", "viewer"), null);

        assertEquals(1, rows.asArray().size());
    }
}
