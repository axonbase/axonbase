package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Agregação com varredura paralela: mesmo resultado da sequencial, cancelamento e tabelas pequenas")
class ParallelScanTest {

    private static final int ROWS = ParallelScan.SEQUENTIAL_HEAD + 9_000;

    private final Datastore ds = Datastore.memory();
    private final Session session = Session.create();

    ParallelScanTest() {
        ds.createDatabase("test", "dev");
        session.namespace("test");
        session.database("dev");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private void fill(String table, int rows) {
        for (int i = 0; i < rows; i++) {
            run("CREATE " + table + ":" + i + " CONTENT {g: 'g" + (i % 7) + "', v: " + (i % 100) + ", w: " + (i * 3)
                + ", s: 's" + (i % 13) + "'}");
        }
    }

    /** Mesma consulta dentro de uma transação aberta, o que força a varredura sequencial. */
    private AxonValue sequential(String sql) {
        run("BEGIN");
        try {
            return run(sql);
        } finally {
            run("CANCEL");
        }
    }

    @Test
    @DisplayName("GROUP BY com todos os agregados, WHERE e HAVING dá o mesmo resultado, na mesma ordem dos grupos")
    void groupByMatchesTheSequentialScan() {
        fill("p", ROWS);
        long before = ParallelScan.BATCHES.get();
        for (String sql : List_of(
            "SELECT g, count() AS n, sum(v) AS s, avg(w) AS a, min(v) AS lo, max(w) AS hi FROM p WHERE v >= 10 GROUP BY g",
            "SELECT g, count() AS n FROM p GROUP BY g ORDER BY n DESC, g",
            "SELECT s, count() AS n, sum(w) AS t FROM p GROUP BY s HAVING n > 100 ORDER BY s",
            "SELECT count() AS n, sum(v) AS s, avg(v) AS a FROM p GROUP ALL",
            "SELECT count() FROM p WHERE g = 'g3' AND v < 50 GROUP ALL",
            "SELECT g, count(DISTINCT s) AS d FROM p GROUP BY g",
            "SELECT string::uppercase(g) AS G, count() AS n FROM p WHERE math::abs(v - 50) < 20 GROUP BY G")) {
            assertEquals(sequential(sql), run(sql), sql);
        }
        assertTrue(ParallelScan.BATCHES.get() > before, "a varredura paralela não foi usada");
    }

    @Test
    @DisplayName("permissões da tabela valem na varredura paralela")
    void permissionsApplyInParallel() {
        run("DEFINE TABLE q SCHEMALESS PERMISSIONS FOR select WHERE v > 20");
        fill("q", ROWS);
        String sql = "SELECT g, count() AS n, sum(v) AS s FROM q GROUP BY g";
        assertEquals(sequential(sql), run(sql));
        long total = run("SELECT count() AS n FROM q GROUP ALL").asArray().get(0).asObject().get("n").asLong();
        assertTrue(total > 0 && total < ROWS);
    }

    @Test
    @DisplayName("tabela pequena não usa o pool")
    void smallTablesStaySequential() {
        fill("s", 500);
        long before = ParallelScan.BATCHES.get();
        run("SELECT g, count() AS n FROM s GROUP BY g");
        assertEquals(before, ParallelScan.BATCHES.get());
    }

    @Test
    @DisplayName("consulta cancelada para a varredura")
    void cancelledQueryStops() {
        fill("c", ROWS);
        QueryContext.Handle handle = new QueryContext.Handle("q1");
        handle.cancel("teste");
        QueryContext.Handle previous = QueryContext.attach(handle);
        try {
            AxonError e = assertThrows(AxonError.class, () -> run("SELECT g, count() AS n FROM c GROUP BY g"));
            assertEquals(QueryContext.CANCELLED, e.code());
        } finally {
            QueryContext.detach(previous);
        }
    }

    private static java.util.List<String> List_of(String... items) {
        return java.util.List.of(items);
    }
}
