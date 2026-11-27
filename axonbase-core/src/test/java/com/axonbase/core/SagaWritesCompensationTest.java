package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SAGA: INSERT, UPSERT que cria e RELATE são desfeitos pelo CANCEL")
class SagaWritesCompensationTest {

    private final Datastore ds = Datastore.memory();
    private final Session session = scope();

    private static Session scope() {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private void begin(String correlation) {
        run("BEGIN SAGA pedido WITH CORRELATION '" + correlation + "'");
        run("LET $saga_corr = '" + correlation + "'");
    }

    private void cancel(String correlation) {
        run("CANCEL SAGA pedido WITH CORRELATION '" + correlation + "'");
    }

    private int count(String sql) {
        return run(sql).asArray().size();
    }

    private int steps(String correlation) {
        return run("SHOW SAGA TRANSACTION pedido '" + correlation + "'").asObject().get("steps").asArray().size();
    }

    /** O valor de {@code v}: a leitura por id pode devolver o registro ou uma lista com ele. */
    private long v(String record) {
        AxonValue rows = run("SELECT * FROM " + record);
        AxonValue row = rows.isArray() ? rows.asArray().get(0) : rows;
        return row.asObject().get("v").asLong();
    }

    private boolean edgeExists(String edge) {
        return !run("UNRELATE " + edge).asArray().isEmpty();
    }

    @Test
    void insertEhDesfeito() {
        begin("i1");
        run("INSERT INTO b {id: 'x', v: 1}");

        cancel("i1");

        assertEquals(0, count("SELECT * FROM b"));
    }

    @Test
    void insertEmLoteComChaveGeradaEhDesfeitoSemTocarNoQueJaExistia() {
        run("CREATE b:antigo SET v = 0");
        begin("i2");
        run("INSERT INTO b [{v: 1}, {v: 2}, {id: 'novo', v: 3}]");

        cancel("i2");

        assertEquals(1, count("SELECT * FROM b"));
    }

    @Test
    void insertIgnoreNaoDesfazOQuePulou() {
        run("CREATE b:antigo SET v = 0");
        begin("i3");
        run("INSERT IGNORE INTO b [{id: 'antigo', v: 9}, {id: 'novo', v: 1}]");

        cancel("i3");

        assertEquals(1, count("SELECT * FROM b"));
        assertEquals(0, v("b:antigo"));
    }

    @Test
    void upsertQueCriaPorIdEhDesfeito() {
        begin("u1");
        run("UPSERT b:1 SET v = 1");

        cancel("u1");

        assertEquals(0, count("SELECT * FROM b"));
    }

    @Test
    void upsertQueCriaComChaveGeradaEhDesfeito() {
        begin("u2");
        run("UPSERT b SET v = 1");

        cancel("u2");

        assertEquals(0, count("SELECT * FROM b"));
    }

    @Test
    void upsertQueRegravaComWhereFalsoVoltaAoQueEra() {
        run("CREATE b:1 SET v = 1");
        begin("u3");
        run("UPSERT b:1 SET v = 2 WHERE v = 99");

        cancel("u3");

        assertEquals(1, v("b:1"));
    }

    @Test
    void relateNovoEhDesfeito() {
        run("CREATE p:1; CREATE p:2");
        begin("r1");
        run("RELATE p:1->conhece->p:2 SET desde = 2020");

        cancel("r1");

        assertFalse(edgeExists("p:1->conhece->p:2"));
        assertEquals(2, count("SELECT * FROM p"));
    }

    @Test
    void relateComChaveTextoComHifenEhDesfeito() {
        begin("r2");
        run("RELATE p:'a-1'->conhece->p:'b 2'");

        cancel("r2");

        assertFalse(edgeExists("p:'a-1'->conhece->p:'b 2'"));
    }

    @Test
    void relateSobreArestaExistenteRestauraAsPropriedadesAnteriores() {
        run("RELATE p:1->conhece->p:2 SET desde = 1");
        begin("r3");
        run("RELATE p:1->conhece->p:2 SET desde = 2, nota = 'nova'");

        cancel("r3");

        String dump = ds.exportDatabase("t", "d");
        assertTrue(dump.contains("SET desde = 1;"), dump);
        assertFalse(dump.contains("nota"), dump);
    }

    @Test
    void escritaDeTransacaoCanceladaNaoDeixaPassoQueApagueOutroRegistro() {
        run("DEFINE TABLE b SCHEMALESS");
        begin("t1");
        run("BEGIN");
        run("INSERT INTO b {id: 'x', v: 1}");
        run("CANCEL");
        assertEquals(0, steps("t1"));
        run("LET $saga_corr = ''");
        run("CREATE b:x SET v = 2");

        cancel("t1");

        assertEquals(1, count("SELECT * FROM b"));
    }

    @Test
    void passoContinuaValendoDepoisDoCommitDaTransacao() {
        begin("t2");
        run("BEGIN");
        run("INSERT INTO b {id: 'x', v: 1}");
        run("COMMIT");

        cancel("t2");

        assertEquals(0, count("SELECT * FROM b"));
    }

    @Test
    void relateComDatabaseLinkEhRecusadoDentroDaSaga() {
        begin("l1");

        RuntimeException refused = assertThrows(RuntimeException.class,
            () -> run("RELATE \"remoto\".\"p\":1->conhece->\"remoto\".\"p\":2"));

        assertTrue(refused.getMessage().contains("SAGA"), refused.getMessage());
    }
}
