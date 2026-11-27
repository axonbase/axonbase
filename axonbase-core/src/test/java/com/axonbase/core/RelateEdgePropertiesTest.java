package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("RELATE ... SET grava as propriedades da aresta")
class RelateEdgePropertiesTest {

    private final MemoryBackend backend = new MemoryBackend();
    private final Datastore ds = new Datastore(backend);

    private static AxonValue run(Datastore target, String sql) {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return target.execute(sql, s, null);
    }

    private static AxonValue read(Datastore target, String field) {
        AxonValue rows = run(target, "SELECT ->k." + field + " AS v FROM a:1");
        AxonValue row = rows.isArray() ? rows.asArray().get(0) : rows;
        return row.asObject().get("v");
    }

    private void connect() {
        run(ds, "CREATE a:1");
        run(ds, "CREATE a:2");
        run(ds, "RELATE a:1->k->a:2 SET desde = 2020");
    }

    @Test
    void propriedadeEhLidaPelaTravessiaESobreviveAoReinicio() {
        connect();

        assertEquals(2020, read(ds, "desde").asLong());
        assertEquals(2020, read(new Datastore(backend), "desde").asLong());
    }

    @Test
    void repetirORelateNaoApagaAsPropriedades() {
        connect();
        run(ds, "RELATE a:1->k->a:2");
        run(ds, "RELATE a:1->k->a:2 SET peso = 3");

        assertEquals(2020, read(ds, "desde").asLong());
        assertEquals(3, read(ds, "peso").asLong());
    }

    @Test
    void unrelateApagaAsPropriedadesJunto() {
        connect();
        run(ds, "UNRELATE a:1->k->a:2");
        run(ds, "RELATE a:1->k->a:2");

        assertFalse(read(ds, "desde").isNumber());
    }

    @Test
    void campoDoRegistroDeDestinoContinuaValendo() {
        run(ds, "CREATE a:1");
        run(ds, "CREATE a:2 SET nome = 'destino'");
        run(ds, "RELATE a:1->k->a:2 SET nome = 'aresta'");

        assertEquals("destino", read(ds, "nome").asString());
    }
}
