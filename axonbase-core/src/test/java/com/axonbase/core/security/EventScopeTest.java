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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Um administrador de banco define o evento, e o corpo roda na sessão de quem grava. O corpo não pode ter mais poder
 * do que quem o define ali, mesmo quando quem grava é o root ou o motor sem autenticação.
 */
class EventScopeTest {

    private static final String ROOT_USER = "CREATE USER intruso ON ROOT PASSWORD 'Test-pw-1' ROLES admin";

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session session(String id, String scope, String roles) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str(scope));
        if (!"ROOT".equals(scope)) {
            claims.put("ns", AxonValue.str("app"));
            claims.put("db", AxonValue.str("main"));
        }
        claims.put("roles", AxonValue.str(roles));
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Session root() {
        return session("root", "ROOT", "admin");
    }

    private static Session databaseAdmin() {
        return session("chefe", "DATABASE", "admin");
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.createDatabase("app", "outro");
        Session other = local();
        other.database("outro");
        ds.execute("CREATE segredo:1 CONTENT {v: 'alheio'}", other, null);
        return ds;
    }

    private static void defineEvent(Datastore ds, String body) {
        ds.execute("DEFINE EVENT armadilha ON TABLE pedido WHEN true THEN (" + body + ")", databaseAdmin(), null);
    }

    private static void assertScopeRefusal(Datastore ds, Session writer) {
        AxonError refused = assertThrows(AxonError.class,
            () -> ds.execute("CREATE pedido:1 CONTENT {v: 1}", writer, null));
        assertTrue(refused.getMessage().contains("app/main"), refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ROOT_USER,
        "DEFINE USER intruso ON NAMESPACE PASSWORD 'Test-pw-1' ROLES admin",
        "DEFINE ACCESS porta ON ROOT",
        "USE DB outro",
        "CREATE copia SET dado = (SELECT * FROM outro.segredo)"})
    void escritaDoRootNaoDaAoCorpoDoEventoPoderForaDoBanco(String body) {
        Datastore ds = ds();
        defineEvent(ds, body);
        Session writer = root();

        assertScopeRefusal(ds, writer);

        assertNull(ds.authCatalog().user("intruso", null, null));
        assertNull(ds.authCatalog().user("intruso", "app", null));
        assertEquals("main", writer.database());
    }

    @Test
    void escritaSemAutenticacaoTambemNaoSaiDoBanco() {
        Datastore ds = ds();
        defineEvent(ds, ROOT_USER);

        assertScopeRefusal(ds, local());

        assertNull(ds.authCatalog().user("intruso", null, null));
    }

    @Test
    void procedureChamadaPeloEventoContinuaConfinada() {
        Datastore ds = ds();
        ds.execute("CREATE PROCEDURE promove() AS $$ db.exec('CREATE USER intruso ON ROOT PASSWORD \"Test-pw-1\" "
            + "ROLES admin') $$", databaseAdmin(), null);
        defineEvent(ds, "CALL promove()");

        assertThrows(RuntimeException.class, () -> ds.execute("CREATE pedido:1", root(), null));

        assertNull(ds.authCatalog().user("intruso", null, null));
    }

    @Test
    void eventoQueGravaNoProprioBancoDisparaParaQualquerEscritor() {
        Datastore ds = ds();
        defineEvent(ds, "CREATE trilha CONTENT {ok: true}");

        for (Session writer : new Session[] {session("editor", "DATABASE", "editor"), root(), local()}) {
            ds.execute("CREATE pedido CONTENT {v: 1}", writer, null);
            assertEquals("app", writer.namespace());
            assertEquals("main", writer.database());
        }

        assertEquals(3, ds.execute("SELECT * FROM trilha", local(), null).asArray().size());
    }

    @Test
    void permissionsQueLemOutroBancoValemNaEscritaDoEventoComoNumaEscritaDireta() {
        Datastore ds = ds();
        ds.execute("DEFINE TABLE trilha SCHEMALESS PERMISSIONS FOR create WHERE 'alheio' IN "
            + "(SELECT VALUE v FROM outro.segredo)", databaseAdmin(), null);
        defineEvent(ds, "CREATE trilha CONTENT {ok: true}");

        ds.execute("CREATE pedido:1", session("editor", "DATABASE", "editor"), null);

        assertEquals(1, ds.execute("SELECT * FROM trilha", local(), null).asArray().size());
    }

    @Test
    void depoisDoEventoASessaoDeQuemGravaVoltaAoPoderDela() {
        Datastore ds = ds();
        defineEvent(ds, "CREATE trilha CONTENT {ok: true}");
        Session writer = root();

        ds.execute("CREATE pedido:1; " + ROOT_USER, writer, null);

        assertTrue(ds.authCatalog().user("intruso", null, null) != null);
    }
}
