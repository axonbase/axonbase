package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SAGA: a compensação devolve exatamente o que havia antes")
class SagaCompensationTest {

    private final Datastore ds = Datastore.memory();
    private final Session session = scope("t", "d");

    private static Session scope(String ns, String db) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
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
        AxonValue rows = run(sql);
        return rows.isArray() ? rows.asArray().size() : 1;
    }

    private AxonValue one(String sql) {
        AxonValue rows = run(sql);
        return rows.isArray() ? rows.asArray().get(0) : rows;
    }

    @Test
    void removeRegistroComChaveGeradaPeloServidor() {
        begin("u1");
        run("CREATE b SET v = 1");

        cancel("u1");

        assertEquals(0, count("SELECT * FROM b"));
    }

    @Test
    void updateCompensadoVoltaComOId() {
        run("CREATE item:1 SET q = 10");
        AxonValue before = one("SELECT * FROM item:1");
        begin("i1");
        run("UPDATE item:1 SET q = 9");

        cancel("i1");

        AxonValue after = one("SELECT * FROM item:1");
        assertEquals(10, after.asObject().get("q").asLong());
        assertEquals(before.asObject().get("id"), after.asObject().get("id"));
    }

    @Test
    void deleteCompensadoVoltaComAChaveGerada() {
        run("CREATE b SET v = 1");
        AxonValue before = one("SELECT * FROM b");
        begin("d1");
        run("DELETE b");

        cancel("d1");

        assertEquals(List.of(before), run("SELECT * FROM b").asArray());
    }

    @Test
    void chaveTextoNaoEhConfundidaComChaveNumerica() {
        run("CREATE k:1 SET v = 'num'");
        run("CREATE k:'1' SET v = 'txt'");
        begin("k1");
        run("UPDATE k:'1' SET v = 'alterado'");

        cancel("k1");

        assertEquals("num", one("SELECT * FROM k:1").asObject().get("v").asString());
        assertEquals("txt", one("SELECT * FROM k:'1'").asObject().get("v").asString());
    }

    @Test
    void restauraCampoDeclaradoComoDataEHora() {
        run("DEFINE FIELD q ON TABLE ev TYPE datetime");
        run("CREATE ev:a SET q = type::datetime('2026-01-01T00:00:00Z')");
        begin("t1");
        run("UPDATE ev:a SET q = type::datetime('2026-02-01T00:00:00Z')");

        cancel("t1");

        AxonValue restored = one("SELECT * FROM ev:a").asObject().get("q");
        assertEquals("2026-01-01T00:00:00Z", restored.asInstant().toString());
    }

    @Test
    void cancelRepetidoDestravaSagaParadaEmCompensacao() {
        begin("u2");
        run("CREATE b SET v = 1");
        ds.execute("UPDATE saga:u2 SET status = 'COMPENSATING'", scope("system", "saga"), null);
        run("LET $saga_corr = ''");

        cancel("u2");

        assertEquals(0, count("SELECT * FROM b"));
    }

    @Test
    void passoGravadoPorVersaoAnteriorComChaveUuidEhCompensado() {
        run("CREATE b:'53c07c2a-0020' SET v = 1");
        begin("leg");
        run("LET $saga_corr = ''");
        legacyStep("leg", 1, "b", "53c07c2a-0020", "{}", "CREATE");

        cancel("leg");

        assertEquals(0, count("SELECT * FROM b"));
    }

    @Test
    void passoNovoEhCompensadoAntesDosGravadosPorProcessoAnterior() {
        run("CREATE item:1 SET q = 1");
        begin("o1");
        // passo de um processo anterior (contador alto): q passou de 1 para 2
        legacyStep("o1", 1_000_000, "item", "1", "{id: 'item:1', q: 1}", "UPDATE");
        run("LET $saga_corr = ''");
        run("UPDATE item:1 SET q = 2");
        run("LET $saga_corr = 'o1'");
        run("UPDATE item:1 SET q = 3");

        cancel("o1");

        assertTrue(one("SELECT * FROM item:1").asObject().get("q").asLong() == 1,
            run("SELECT * FROM item:1").toString());
    }

    private void legacyStep(String correlation, long order, String table, String key, String before,
                            String operation) {
        ds.execute("CREATE saga_step CONTENT {correlation_id: '" + correlation + "', step_order: " + order
            + ", step_ns: 't', step_db: 'd', link_ns: '', link_name: 'local', table_name: '" + table
            + "', record_key: '" + key + "', before: " + before + ", operation: '" + operation
            + "', status: 'PREPARED'}", scope("system", "saga"), null);
    }
}
