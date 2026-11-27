package com.axonbase.core;

import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.control.ControlStateMachine;
import com.axonbase.core.control.ControlStore;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O plano de controle só grava e só reexecuta a definição do tipo registrado: nome nenhum vira comando. */
@DisplayName("Plano de controle: identificador não vira comando no replay")
class ControlCatalogInjectionTest {

    private static final String PASSWORD = "Test-pw-1";

    private static final String HOSTILE =
        "x SCHEMALESS; DEFINE USER evil ON ROOT PASSWORD 'Test-pw-1' ROLES OWNER; --";

    private final MemoryBackend backend = new MemoryBackend();

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static Object evil(Datastore ds) {
        return ds.authCatalog().verify("evil", PASSWORD, "n", "d");
    }

    private static String tables(Datastore ds) {
        return AxonJson.write(ds.execute("INFO FOR DATABASE", session(), null).asObject().get("tables"));
    }

    /** Grava direto no storage, como uma versão anterior deixou. */
    private void storedByEarlierVersion(ControlCommand.Kind kind, String name, String definition) {
        Datastore ds = new Datastore(backend);
        ds.createDatabase("n", "d");
        ControlStore store = new ControlStore(backend);
        ControlStateMachine state = new ControlStateMachine();
        state.restore(store.load());
        state.apply(ControlCommand.of(kind, "n", "d", name, definition));
        store.save(state, backend);
    }

    @Test
    void nomeDeTabelaHostilNaoViraComandoNoCancelNemNoReinicio() {
        Datastore ds = new Datastore(backend);
        ds.execute("SELECT * FROM `" + HOSTILE + "`", session(), null);
        Session s = session();
        ds.execute("BEGIN", s, null);
        ds.execute("SELECT * FROM inexistente", s, null);
        ds.execute("CANCEL", s, null);

        assertNull(evil(ds));
        assertNull(evil(new Datastore(backend)));
    }

    @Test
    void baseJaAfetadaAbreSemExecutarOTextoGravado() {
        storedByEarlierVersion(ControlCommand.Kind.TABLE, HOSTILE, "DEFINE TABLE " + HOSTILE + " SCHEMALESS");

        Datastore reopened = assertDoesNotThrow(() -> new Datastore(backend));

        assertNull(evil(reopened));
    }

    @Test
    void registroDeTabelaNuncaCriaUsuario() {
        storedByEarlierVersion(ControlCommand.Kind.TABLE, "t",
            "DEFINE USER evil ON ROOT PASSWORD 'Test-pw-1' ROLES OWNER");

        assertNull(evil(assertDoesNotThrow(() -> new Datastore(backend))));
    }

    @Test
    void textoInvalidoGravadoNaoImpedeASubida() {
        storedByEarlierVersion(ControlCommand.Kind.TABLE, "order items", "DEFINE TABLE order items SCHEMALESS");

        assertDoesNotThrow(() -> new Datastore(backend));
    }

    @Test
    void nomeComEspacoSobreviveAoReinicio() {
        Datastore ds = new Datastore(backend);
        ds.execute("CREATE `order items`:1 SET a = 1", session(), null);
        ds.execute("CREATE `Order`:1 SET a = 1", session(), null);

        Datastore reopened = assertDoesNotThrow(() -> new Datastore(backend));

        assertTrue(tables(reopened).contains("\"order items\""));
        assertTrue(tables(reopened).contains("\"Order\""));
        assertNotNull(reopened.execute("SELECT * FROM `order items`", session(), null));
    }

    @Test
    void chaveDeObjetoNoDefaultNaoViraComando() {
        Datastore ds = new Datastore(backend);
        ds.execute("DEFINE FIELD meta ON TABLE t DEFAULT {\"a: 1}; DEFINE USER evil ON ROOT PASSWORD "
            + "'Test-pw-1' ROLES OWNER; --\": 1}", session(), null);

        assertNull(evil(assertDoesNotThrow(() -> new Datastore(backend))));
    }

    @Test
    void gravacaoRecusaMaisDeUmaSentenca() {
        Datastore ds = new Datastore(backend);
        AxonValue ignored = ds.execute("DEFINE TABLE t", session(), null);

        assertThrows(RuntimeException.class, () -> ds.applyControl(ControlCommand.of(ControlCommand.Kind.TABLE,
            "n", "d", "t", "DEFINE TABLE t SCHEMALESS; DEFINE USER evil ON ROOT PASSWORD 'Test-pw-1'")));
    }
}
