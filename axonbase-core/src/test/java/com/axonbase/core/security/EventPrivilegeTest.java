package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.FileBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O corpo de um evento roda na sessão de quem dispara a escrita, então só um administrador mexe em eventos. */
class EventPrivilegeTest {

    private static final String ESCALATION = "CREATE USER intruso ON ROOT PASSWORD 'Test-pw-1' ROLES admin";
    private static final String TRAP = "DEFINE EVENT armadilha ON TABLE pedido WHEN $event = 'CREATE' THEN ("
        + ESCALATION + ")";
    private static final String HARMLESS = "DEFINE EVENT registra ON TABLE pedido WHEN true THEN "
        + "(CREATE trilha CONTENT {ok: true})";

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

    private static Session editor() {
        return session("editor", "DATABASE", "editor");
    }

    private static Session root() {
        return session("root", "ROOT", "admin");
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        return ds;
    }

    private static void assertRoleRefusal(Datastore ds, String query, Session s) {
        AxonError refused = assertThrows(AxonError.class, () -> ds.execute(query, s, null), query);
        assertTrue(refused.getMessage().contains("admin"), refused.getMessage());
    }

    @Test
    void editorNaoFazORootExecutarOCorpoDeUmEvento() {
        Datastore ds = ds();

        assertRoleRefusal(ds, TRAP, editor());
        ds.execute("CREATE pedido:1 CONTENT {v: 1}", root(), null);

        assertNull(ds.authCatalog().user("intruso", null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE EVENT registra ON TABLE pedido WHEN true THEN (CREATE outra)",
        "ALTER EVENT registra ON TABLE pedido WHEN true THEN (CREATE outra)",
        "DEFINE EVENT registra ON TABLE pedido WHEN true THEN (CREATE outra)",
        "DROP EVENT registra"})
    void editorNaoCriaNaoAlteraENaoRemoveEvento(String statement) {
        Datastore ds = ds();
        ds.execute(HARMLESS, root(), null);

        assertRoleRefusal(ds, statement, editor());
        ds.execute("CREATE pedido:1", root(), null);

        assertTrue(ds.execute("SELECT * FROM trilha", local(), null).asArray().size() == 1);
    }

    @Test
    void importacaoDeEditorNaoDefineEvento() {
        Datastore ds = ds();

        assertThrows(RuntimeException.class, () -> ds.importDatabase(editor(), TRAP + ";"));
        ds.execute("CREATE pedido:1", root(), null);

        assertNull(ds.authCatalog().user("intruso", null, null));
    }

    @Test
    void eventoDoAdministradorVoltaNoReinicioEContinuaDisparandoParaOEditor(@TempDir Path dir) {
        String path = dir.resolve("eventos.db").toString();
        Datastore first = new Datastore(new FileBackend(path));
        first.createDatabase("app", "main");
        first.execute(HARMLESS, session("chefe", "DATABASE", "admin"), null);

        Datastore recovered = new Datastore(new FileBackend(path));
        recovered.execute("CREATE pedido:1", editor(), null);

        assertNotNull(recovered.execute("SELECT * FROM trilha", local(), null));
        assertTrue(recovered.execute("SELECT * FROM trilha", local(), null).asArray().size() == 1);
    }
}
