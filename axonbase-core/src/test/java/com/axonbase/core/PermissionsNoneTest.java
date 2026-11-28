package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("PERMISSIONS NONE nega as quatro ações, como WHERE false")
class PermissionsNoneTest {

    private final MemoryBackend backend = new MemoryBackend();
    private final Datastore ds = new Datastore(backend);

    private static AxonValue run(Datastore target, String sql) {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return target.execute(sql, s, null);
    }

    private static List<AxonValue> rows(Datastore target) {
        return run(target, "SELECT * FROM cofre").asArray();
    }

    private void open() {
        run(ds, "DEFINE TABLE cofre SCHEMALESS PERMISSIONS FULL");
    }

    @BeforeEach
    void define() {
        ds.createDatabase("n", "d");
        run(ds, "CREATE cofre:1 SET v = 1");
        run(ds, "DEFINE TABLE cofre SCHEMALESS PERMISSIONS NONE");
    }

    @Test
    void negaLeitura() {
        assertEquals(0, rows(ds).size());
    }

    @Test
    void negaCriacaoAlteracaoERemocao() {
        assertThrows(AxonError.class, () -> run(ds, "CREATE cofre:2 SET v = 2"));
        run(ds, "UPDATE cofre:1 SET v = 9");
        run(ds, "DELETE cofre:1");

        open();

        List<AxonValue> kept = rows(ds);
        assertEquals(1, kept.size(), kept.toString());
        assertEquals(1, kept.get(0).asObject().get("v").asLong());
    }

    @Test
    void continuaNegandoDepoisDoReinicio() {
        assertEquals(0, rows(new Datastore(backend)).size());
    }

    @Test
    void continuaNegandoNoBancoImportado() {
        Datastore target = new Datastore(new MemoryBackend());

        target.importDatabase("n", "d", ds.exportDatabase("n", "d"));

        assertEquals(0, rows(target).size());
        run(target, "DEFINE TABLE cofre SCHEMALESS PERMISSIONS FULL");
        assertEquals(1, rows(target).size());
    }
}
