package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Agregação: em consulta agrupada as funções math:: reduzem o grupo inteiro")
class MathAggregationTest {

    private final Datastore ds = Datastore.memory();
    private final Session session = Session.create();

    @BeforeEach
    void setUp() {
        session.namespace("t");
        session.database("d");
        run("CREATE venda:1 SET cidade = 'SP', valor = 10, notas = [1, 2]");
        run("CREATE venda:2 SET cidade = 'SP', valor = 20, notas = [3]");
        run("CREATE venda:3 SET cidade = 'RJ', valor = 60, notas = [4, 5]");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private List<AxonValue> rows(String sql) {
        return run(sql).asArray();
    }

    private static long num(AxonValue row, String key) {
        return row.asObject().get(key).asLong();
    }

    @Test
    void groupAllReduzTodosOsRegistros() {
        Map<String, AxonValue> row = rows("SELECT math::sum(valor) AS soma, math::min(valor) AS menor,"
            + " math::max(valor) AS maior, math::mean(valor) AS media, math::median(valor) AS mediana,"
            + " math::product(valor) AS produto FROM venda GROUP ALL").get(0).asObject();

        assertEquals(90, row.get("soma").asLong());
        assertEquals(10, row.get("menor").asLong());
        assertEquals(60, row.get("maior").asLong());
        assertEquals(30, row.get("media").asLong());
        assertEquals(20, row.get("mediana").asLong());
        assertEquals(12000, row.get("produto").asLong());
    }

    @Test
    void groupByReduzCadaGrupo() {
        List<AxonValue> rows = rows("SELECT cidade, math::sum(valor) AS total FROM venda"
            + " GROUP BY cidade ORDER BY cidade");

        assertEquals(60, num(rows.get(0), "total"));
        assertEquals(30, num(rows.get(1), "total"));
    }

    @Test
    void campoListaContribuiComTodosOsItens() {
        assertEquals(15, num(rows("SELECT math::sum(notas) AS total FROM venda GROUP ALL").get(0), "total"));
    }

    @Test
    void semAgrupamentoContinuaValendoPorLinha() {
        List<AxonValue> rows = rows("SELECT math::sum(notas) AS s FROM venda ORDER BY s");

        assertEquals(3, rows.size());
        assertEquals(9, num(rows.get(2), "s"));
    }

    @Test
    void agruparPelaPropriaExpressaoMantemOValorDaLinha() {
        List<AxonValue> rows = rows("SELECT math::sum(notas) AS soma, count() AS n FROM venda"
            + " GROUP BY soma ORDER BY soma");

        assertEquals(2, rows.size());
        assertEquals(3, num(rows.get(0), "soma"));
        assertEquals(2, num(rows.get(0), "n"));
        assertEquals(9, num(rows.get(1), "soma"));
    }
}
