package com.axonbase.core;

import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.control.ControlStateMachine;
import com.axonbase.core.control.ControlStore;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CANCEL de uma transação com DDL reconstrói o catálogo: a autenticação precisa sobreviver a isso. */
@DisplayName("CANCEL depois de DDL não derruba a autenticação")
class CancelAfterDdlAuthTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static Datastore withRoot(MemoryBackend backend) {
        Datastore ds = new Datastore(backend);
        ds.authCatalog().ensureUser("root", "segredo-de-teste");
        return ds;
    }

    private static boolean tableExists(Datastore ds, String table) {
        AxonValue tables = ds.execute("INFO FOR DATABASE", session(), null).asObject().get("tables");
        return com.axonbase.value.AxonJson.write(tables).contains("\"" + table + "\"");
    }

    @Test
    @DisplayName("DROP INDEX seguido de transação cancelada: o root continua autenticando")
    void cancelAfterDropIndex() {
        Datastore ds = withRoot(new MemoryBackend());
        ds.execute("CREATE t:1 SET a = 1; CREATE INDEX ia ON TABLE t COLUMNS a; DROP INDEX ia", session(), null);
        Session s = session();
        ds.execute("BEGIN", s, null);
        ds.execute("CREATE nova:1", s, null);

        assertDoesNotThrow(() -> ds.execute("CANCEL", s, null));

        assertNotNull(ds.authCatalog().verify("root", "segredo-de-teste", "n", "d"));
        assertFalse(tableExists(ds, "nova"));
    }

    @Test
    @DisplayName("mesmo com um comando de controle quebrado, o root e os usuários definidos continuam")
    void brokenControlCommandKeepsAuthentication() {
        MemoryBackend backend = new MemoryBackend();
        Datastore ds = withRoot(backend);
        ds.execute("DEFINE USER alice ON DATABASE PASSWORD \"outra-de-teste\"", session(), null);
        ControlStore store = new ControlStore(backend);
        ControlStateMachine state = new ControlStateMachine();
        state.restore(store.load());
        state.apply(ControlCommand.of(ControlCommand.Kind.INDEX, "n", "d", "t:quebrado", "ISTO NAO E AXONQL"));
        store.save(state, backend);
        Session s = session();
        ds.execute("BEGIN", s, null);
        ds.execute("CREATE nova:1", s, null);

        // a falha continua visível para quem cancelou
        assertThrows(RuntimeException.class, () -> ds.execute("CANCEL", s, null));

        assertNotNull(ds.authCatalog().verify("root", "segredo-de-teste", "n", "d"));
        assertNotNull(ds.authCatalog().verify("alice", "outra-de-teste", "n", "d"));
    }
}
