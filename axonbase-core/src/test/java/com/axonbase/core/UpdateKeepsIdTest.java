package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UPDATE e UPSERT nunca tiram nem trocam o campo id de um registro existente. */
@DisplayName("Escritas preservam o id do registro")
class UpdateKeepsIdTest {

    private final MemoryBackend backend = new MemoryBackend();
    private final Datastore ds = new Datastore(backend);

    private AxonValue run(String sql) {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return ds.execute(sql, s, null);
    }

    private static String field(AxonValue rows, String name) {
        AxonValue row = rows.isArray() ? rows.asArray().get(0) : rows;
        AxonValue value = row.asObject().get(name);
        return value == null ? null : AxonJson.write(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE p:1 CONTENT {nome: 'C'}",
        "UPDATE p:1 REPLACE {nome: 'C'}",
        "UPSERT p:1 CONTENT {nome: 'C'}",
        "UPSERT p:1 REPLACE {nome: 'C'}",
        "UPDATE p CONTENT {nome: 'C'}"})
    @DisplayName("CONTENT e REPLACE sobre registro existente mantêm o id")
    void contentAndReplaceKeepTheId(String write) {
        run("CREATE p:1 CONTENT {nome: 'B', idade: 20}");

        AxonValue returned = run(write);

        assertEquals("\"p:1\"", field(returned, "id"));
        AxonValue stored = run("SELECT * FROM p");
        assertEquals("\"p:1\"", field(stored, "id"));
        assertEquals("\"C\"", field(stored, "nome"));
        assertNull(field(stored, "idade"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"p:1", "'p:1'", "1"})
    @DisplayName("repetir no conteúdo o id do próprio registro é aceito")
    void sameIdIsAccepted(String id) {
        run("CREATE p:1 CONTENT {nome: 'B'}");

        run("UPDATE p:1 CONTENT {id: " + id + ", nome: 'C'}");

        assertEquals("\"p:1\"", field(run("SELECT * FROM p"), "id"));
        assertEquals("\"C\"", field(run("SELECT * FROM p"), "nome"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE p:1 CONTENT {id: p:2, nome: 'C'}",
        "UPDATE p:1 CONTENT {id: 'q:1', nome: 'C'}",
        "UPDATE p:1 CONTENT {id: '1', nome: 'C'}",
        "UPDATE p:1 REPLACE {id: 2, nome: 'C'}",
        "UPSERT p:1 CONTENT {id: 2, nome: 'C'}",
        "UPDATE p:1 MERGE {id: 2}",
        "UPDATE p:1 SET id = 2"})
    @DisplayName("id diferente é recusado com erro que cita o id, e nada é gravado")
    void differentIdIsRejected(String write) {
        run("CREATE p:1 CONTENT {nome: 'B'}");

        RuntimeException error = assertThrows(RuntimeException.class, () -> run(write));

        assertTrue(error.getMessage().contains("p:1"), error.getMessage());
        AxonValue stored = run("SELECT * FROM p");
        assertEquals(1, stored.asArray().size());
        assertEquals("\"p:1\"", field(stored, "id"));
        assertEquals("\"B\"", field(stored, "nome"));
    }

    @Test
    @DisplayName("chave textual: o id continua o mesmo depois do CONTENT")
    void textKeyKeepsTheId() {
        run("CREATE p:ana CONTENT {nome: 'Ana'}");

        run("UPDATE p:ana CONTENT {id: 'ana', nome: 'Bia'}");

        assertEquals("\"p:ana\"", field(run("SELECT * FROM p"), "id"));
    }

    /** Registro como a rc1 deixava depois de um UPDATE CONTENT: guardado sem o campo id. */
    private void stripStoredId(String table, String keyString) {
        String key = "n\u0000d\u0000" + table + "\u0000" + keyString;
        Map<String, AxonValue> record = new LinkedHashMap<>(AxonJson.decode(backend.get(key).orElseThrow()).asObject());
        record.remove("id");
        backend.put(key, AxonJson.encode(AxonValue.object(record)));
    }

    @Test
    @DisplayName("registro que já perdeu o id volta a tê-lo na próxima escrita")
    void damagedRecordGetsItsIdBack() {
        run("CREATE p:1 CONTENT {nome: 'B'}");
        stripStoredId("p", "n1");

        run("UPDATE p:1 SET nome = 'C'");

        assertEquals("\"p:1\"", field(run("SELECT * FROM p"), "id"));
    }

    @Test
    @DisplayName("registro que já perdeu o id continua na exportação")
    void damagedRecordIsExported() {
        run("CREATE p:1 CONTENT {nome: 'B'}");
        stripStoredId("p", "n1");

        String dump = ds.exportDatabase("n", "d");

        assertTrue(dump.contains("CREATE p:1 CONTENT"), dump);
    }
}
