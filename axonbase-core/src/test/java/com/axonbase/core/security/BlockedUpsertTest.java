package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Um UPSERT que não pode alterar um registro existente não o regrava, e INSERT IGNORE confere quem pode criar. */
class BlockedUpsertTest {

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session user(String rules) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("v"));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("app"));
        claims.put("db", AxonValue.str("main"));
        claims.put("roles", AxonValue.str("editor"));
        if (rules != null) {
            claims.put("data_rules", AxonValue.str(rules));
        }
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Datastore ds(String permissions) {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.execute("CREATE p:1 CONTENT {dono: 'outro', nome: 'A'}", local(), null);
        if (permissions != null) {
            ds.execute("DEFINE TABLE p SCHEMALESS PERMISSIONS " + permissions, local(), null);
        }
        return ds;
    }

    private static String name(Datastore ds) {
        return ds.execute("RETURN p:1.nome", local(), null).asString();
    }

    private static void assertDenied(Datastore ds, String query, Session s) {
        AxonError refused = assertThrows(AxonError.class, () -> ds.execute(query, s, null), query);
        assertTrue(refused.getMessage().contains("permission denied"), refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPSERT p:1 CONTENT {dono: 'v', nome: 'B'}",
        "UPSERT p:1 SET nome = 'B'",
        "UPSERT p CONTENT {id: 1, dono: 'v', nome: 'B'}"})
    void upsertBarradoPorPermissaoNaoRegrava(String write) {
        Datastore ds = ds("FOR update WHERE dono = $auth.id FOR create WHERE true");

        assertDenied(ds, write, user(null));

        assertEquals("A", name(ds));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPSERT p:1 CONTENT {nome: 'B'} WHERE nome = 'ninguem'",
        "UPSERT p CONTENT {id: 1, nome: 'B'} WHERE nome = 'ninguem'"})
    void upsertComWhereFalsoNaoRegrava(String write) {
        Datastore ds = ds(null);

        AxonValue written = ds.execute(write, local(), null);

        assertEquals(0, written.asArray().size());
        assertEquals("A", name(ds));
    }

    @Test
    void upsertDeUsuarioComRegraEWhereFalsoNaoRegrava() {
        Datastore ds = ds(null);
        ds.execute("CREATE DATA RULE meus APPLY dono = 'outro'", local(), null);

        ds.execute("UPSERT p:1 CONTENT {nome: 'B'} WHERE nome = 'ninguem'", user("meus"), null);

        assertEquals("A", name(ds));
    }

    @Test
    void upsertContinuaCriandoOQueNaoExiste() {
        Datastore ds = ds("FOR update WHERE dono = $auth.id FOR create WHERE true");

        ds.execute("UPSERT p:2 CONTENT {nome: 'B'}", user(null), null);

        assertEquals("B", ds.execute("RETURN p:2.nome", local(), null).asString());
    }

    @Test
    void insertIgnoreConferePermissaoDeCriarAntesDePular() {
        Datastore ds = ds("FOR create WHERE dono = $auth.id");

        // sem permissão, a resposta é a mesma para um id que existe e para um que não existe
        assertDenied(ds, "INSERT IGNORE INTO p [{id: 1, dono: 'outro'}]", user(null));
        assertDenied(ds, "INSERT IGNORE INTO p [{id: 2, dono: 'outro'}]", user(null));
        AxonValue skipped = ds.execute("INSERT IGNORE INTO p [{id: 1, dono: 'v'}]", user(null), null);

        assertEquals(0, skipped.asArray().size());
        assertEquals("A", name(ds));
    }
}
