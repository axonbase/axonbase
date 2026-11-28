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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Definir ou apagar embedding e analyzer é tarefa do administrador do banco: a URL decide para onde o texto vai. */
class EmbeddingAnalyzerRoleTest {

    private static final String URL = "http://127.0.0.1:9/v1/embeddings";

    private static final String DEFINITIONS = "DEFINE EMBEDDING e PROVIDER http URL \"" + URL
        + "\" MODEL \"m\" DIMENSION 4; DEFINE ANALYZER pt LOWERCASE";

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session session(String database, String roles) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("quem"));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("app"));
        claims.put("db", AxonValue.str(database));
        claims.put("roles", AxonValue.str(roles));
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.execute(DEFINITIONS, local(), null);
        return ds;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DEFINE EMBEDDING e PROVIDER http URL \"http://127.0.0.1:10/desvio\" MODEL \"m\" DIMENSION 4",
        "DEFINE EMBEDDING novo PROVIDER http URL \"http://127.0.0.1:10/v1\" MODEL \"m\" DIMENSION 4",
        "DROP EMBEDDING e",
        "DEFINE ANALYZER pt LOWERCASE STEMMING",
        "CREATE ANALYZER outro LOWERCASE",
        "DROP ANALYZER pt"})
    void editorEAdministradorDeOutroBancoSaoRecusados(String statement) {
        Datastore ds = ds();

        for (Session s : new Session[] {session("main", "editor"), session("vizinho", "admin")}) {
            AxonError refused = assertThrows(AxonError.class, () -> ds.execute(statement, s, null), statement);
            assertTrue(refused.getMessage().contains("admin"), refused.getMessage());
        }

        var catalog = ds.findDatabase("app", "main").catalog();
        assertEquals(URL, catalog.embedding("e").url());
        assertNull(catalog.embedding("novo"));
        assertNotNull(catalog.analyzer("pt"));
        assertNull(catalog.analyzer("outro"));
    }

    @Test
    void administradorDoBancoContinuaPodendo() {
        Datastore ds = ds();

        ds.execute("DROP EMBEDDING e; DROP ANALYZER pt; " + DEFINITIONS, session("main", "admin"), null);

        assertNotNull(ds.findDatabase("app", "main").catalog().embedding("e"));
    }

    @Test
    void repetirADefinicaoEmVigorNaoPedePapel() {
        Datastore ds = ds();

        ds.execute(DEFINITIONS, session("main", "editor"), null);

        assertEquals(URL, ds.findDatabase("app", "main").catalog().embedding("e").url());
    }

    @Test
    void administradorImportaOProprioDumpEmBancoVazio() {
        Datastore source = ds();
        Datastore target = Datastore.memory();
        target.createDatabase("app", "main");

        target.importDatabase(session("main", "admin"), source.exportDatabase("app", "main"));

        assertNotNull(target.findDatabase("app", "main").catalog().embedding("e"));
        assertNotNull(target.findDatabase("app", "main").catalog().analyzer("pt"));
    }
}
