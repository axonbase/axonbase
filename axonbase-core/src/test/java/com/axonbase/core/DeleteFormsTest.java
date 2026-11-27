package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DELETE FROM apaga, DELETE TABLE é recusado, e nenhuma forma de DELETE cria tabela. */
@DisplayName("Formas do DELETE")
class DeleteFormsTest {

    private final Datastore ds = new Datastore(new MemoryBackend());

    private AxonValue run(String sql) {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return ds.execute(sql, s, null);
    }

    private int count(String table) {
        return run("SELECT * FROM " + table).asArray().size();
    }

    private boolean tableExists(String table) {
        return AxonJson.write(run("INFO FOR DATABASE").asObject().get("tables")).contains("\"" + table + "\"");
    }

    private void twoRows() {
        run("CREATE p:1 SET n = 1; CREATE p:2 SET n = 2");
    }

    @Test
    @DisplayName("DELETE FROM com WHERE apaga só o que casa e não cria a tabela from")
    void deleteFromWithWhere() {
        twoRows();

        run("DELETE FROM p WHERE n = 1");

        assertEquals(1, count("p"));
        assertFalse(tableExists("from"));
    }

    @Test
    @DisplayName("DELETE FROM sem WHERE apaga a tabela inteira")
    void deleteFromWholeTable() {
        twoRows();

        run("DELETE FROM p");

        assertEquals(0, count("p"));
        assertFalse(tableExists("from"));
    }

    @Test
    @DisplayName("DELETE FROM com record id apaga o registro e respeita RETURN BEFORE")
    void deleteFromRecord() {
        twoRows();

        AxonValue removed = run("DELETE FROM p:1 RETURN BEFORE");

        assertEquals(1, removed.asArray().size());
        assertEquals(1, count("p"));
        assertFalse(tableExists("from"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE TABLE p", "DELETE TABLE p WHERE n = 1", "DELETE TABLE IF EXISTS p"})
    @DisplayName("DELETE TABLE é recusado, indica os comandos certos e não apaga nada")
    void deleteTableIsRejected(String sql) {
        twoRows();

        RuntimeException error = assertThrows(RuntimeException.class, () -> run(sql));

        assertTrue(error.getMessage().contains("DROP TABLE"), error.getMessage());
        assertEquals(2, count("p"));
        assertFalse(tableExists("table"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE fantasma", "DELETE FROM fantasma", "DELETE fantasma WHERE n = 1",
        "DELETE FROM fantasma:1", "DELETE fantasma:1"})
    @DisplayName("apagar de uma tabela que não existe não a cria")
    void deleteNeverCreatesATable(String sql) {
        twoRows();

        run(sql);

        assertFalse(tableExists("fantasma"));
        assertFalse(tableExists("from"));
        assertEquals(2, count("p"));
    }

    @Test
    @DisplayName("uma tabela chamada from (deixada pela rc1) continua podendo ser esvaziada")
    void tableNamedFromStillWorks() {
        run("CREATE from:1; CREATE from:2; CREATE from:3");

        run("DELETE from:1");
        assertEquals(2, count("from"));
        run("DELETE FROM from:2");
        assertEquals(1, count("from"));
        run("DELETE from");
        assertEquals(0, count("from"));
    }
}
