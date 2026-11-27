package com.axonbase.core;

import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O dump leva as PERMISSIONS e as Data Rules, para o banco importado não ficar mais aberto que o de origem. */
@DisplayName("Exportar e importar mantém as regras de acesso")
class ExportAccessRulesTest {

    private final Datastore source = new Datastore(new MemoryBackend());

    private static Session local() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static Session user(String id, String roles, String rules) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("n"));
        claims.put("db", AxonValue.str("d"));
        claims.put("roles", AxonValue.str(roles));
        if (rules != null) {
            claims.put("data_rules", AxonValue.str(rules));
        }
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static AxonValue run(Datastore ds, String sql) {
        return ds.execute(sql, local(), null);
    }

    @BeforeEach
    void define() {
        source.createDatabase("n", "d");
        run(source, "CREATE pedido:1 CONTENT {dono: 'ana', cpf: '111'}");
        run(source, "CREATE pedido:2 CONTENT {dono: 'bia', cpf: '222'}");
        run(source, "DEFINE EVENT registra ON TABLE pedido WHEN true THEN (CREATE trilha CONTENT {ok: true})");
        run(source, "CREATE TABLE conta (saldo INT NOT NULL)");
        run(source, "CREATE conta:1 SET saldo = 10");
        run(source, "DEFINE TABLE pedido SCHEMALESS PERMISSIONS FOR select WHERE dono = $auth.id "
            + "FOR create WHERE dono = $auth.id FOR update WHERE dono = $auth.id FOR delete WHERE false");
        run(source, "DEFINE TABLE conta SCHEMAFULL PERMISSIONS FOR select WHERE saldo < 0");
        run(source, "CREATE DATA RULE meus APPLY dono = $auth.id MASK FIELDS WITH '%cpf%'");
        run(source, "CREATE USER segredo ON DATABASE PASSWORD 'Test-pw-1' ROLES editor APPLY DATA RULE meus");
    }

    @Test
    @DisplayName("PERMISSIONS e Data Rules valem no banco importado, com dados, campos e eventos")
    void accessRulesSurvive() {
        Datastore target = new Datastore(new MemoryBackend());

        target.importDatabase("n", "d", source.exportDatabase("n", "d"));

        assertEquals(1, target.execute("SELECT * FROM pedido", user("ana", "editor", null), null)
            .asArray().size());
        assertEquals(0, target.execute("SELECT * FROM conta", user("ana", "editor", null), null)
            .asArray().size());
        assertEquals("bia", run(target, "RETURN pedido:2.dono").asString());
        assertEquals(10L, run(target, "RETURN conta:1.saldo").asLong());
        String ruled = target.execute("SELECT * FROM pedido", user("ana", "editor", "meus"), null).toString();
        assertFalse(ruled.contains("111"), ruled);
        Catalog catalog = target.findDatabase("n", "d").catalog();
        assertEquals(java.util.List.of("%cpf%"), catalog.dataRule("meus").maskPatterns());
        assertTrue(catalog.table("pedido").events().containsKey("registra"));
        assertTrue(catalog.table("conta").fields().get("saldo").notNull());
        assertTrue(catalog.table("conta").schemafull());
    }

    @Test
    @DisplayName("o dump não leva usuários, senhas, acessos nem ligações")
    void identitiesStayOut() {
        String dump = source.exportDatabase("n", "d");

        for (String secret : new String[] {"segredo", "PASSHASH", "PASSWORD", " USER ", " ACCESS ", "DATABASE LINK"}) {
            assertFalse(dump.contains(secret), secret + " em " + dump);
        }
    }

    @Test
    @DisplayName("importar uma Data Rule continua exigindo administrador")
    void importingARuleStillNeedsAdmin() {
        Datastore target = new Datastore(new MemoryBackend());
        target.createDatabase("n", "d");
        String dump = source.exportDatabase("n", "d").lines()
            .filter(line -> line.startsWith("CREATE DATA RULE"))
            .collect(java.util.stream.Collectors.joining("\n", "", "\n"));

        assertTrue(dump.contains("meus"), dump);
        assertThrows(RuntimeException.class,
            () -> target.importDatabase(user("eva", "editor", null), dump));
        assertEquals(null, target.findDatabase("n", "d").catalog().dataRule("meus"));
    }
}
