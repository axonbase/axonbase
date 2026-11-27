package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.QueryContext;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O {@code EXPLAIN} sem {@code ANALYZE} conta as linhas passando pelas chaves, uma de cada vez: não pede os valores
 * nem a lista de chaves, que numa tabela grande esgotavam a memória.
 */
@DisplayName("EXPLAIN: a estimativa não carrega a tabela")
class ExplainEstimateTest {

    private static final int ROWS = 50;
    private static final String TABLE = "test\u0000dev\u0000t\u0000";

    private final GuardedBackend backend = new GuardedBackend();
    private final Datastore ds = new Datastore(backend);
    private final Session session = session("dev");

    @BeforeEach
    void fill() {
        ds.createDatabase("test", "dev");
        run("DEFINE TABLE t SCHEMALESS");
        insert(session, "t", ROWS);
    }

    private static Session session(String database) {
        Session s = Session.create();
        s.namespace("test");
        s.database(database);
        return s;
    }

    private AxonValue run(String sql) {
        return run(session, sql);
    }

    private AxonValue run(Session s, String sql) {
        return ds.execute(sql, s, null);
    }

    private void insert(Session s, String table, int rows) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" [");
        for (int i = 1; i <= rows; i++) {
            sql.append(i > 1 ? "," : "").append("{id: ").append(i).append(", n: ").append(i).append('}');
        }
        run(s, sql.append(']').toString());
    }

    private long examined(String select) {
        return examined(session, select);
    }

    private long examined(Session s, String select) {
        return run(s, "EXPLAIN " + select).asObject().get("examinedRows").asLong();
    }

    private String strategy(String select) {
        return run("EXPLAIN " + select).asObject().get("strategy").asString();
    }

    private void assertNothingLoaded() {
        assertEquals(List.of(), backend.violations());
    }

    @Test
    @DisplayName("varredura completa conta as chaves sem ler registro nem listar a tabela")
    void fullScan_countsKeysOnly() {
        backend.guard(TABLE);

        assertEquals("FULL_SCAN", strategy("SELECT * FROM t WHERE n > 3"));
        assertEquals(ROWS, examined("SELECT * FROM t WHERE n > 3"));
        assertNothingLoaded();
    }

    @Test
    @DisplayName("as entradas de índice UNIQUE ficam fora da conta e não são lidas")
    void uniqueIndexEntries_areNotCounted() {
        run("DEFINE INDEX t_n ON TABLE t COLUMNS n UNIQUE");
        backend.guard(TABLE);

        assertEquals(ROWS, examined("SELECT * FROM t"));
        assertEquals(1, examined("SELECT * FROM t WHERE n = 3"));
        assertNothingLoaded();
    }

    @Test
    @DisplayName("consulta no tempo estima pela tabela atual, sem ler registro")
    void timeTravel_countsCurrentKeys() {
        backend.guard(TABLE);

        assertEquals(ROWS, examined("SELECT * FROM t AT (TIMESTAMP => 1)"));
        assertNothingLoaded();
    }

    @Test
    @DisplayName("tabela de outro banco do namespace é contada do mesmo jeito")
    void otherDatabaseTable_countsKeysOnly() {
        ds.createDatabase("test", "arquivo");
        insert(session("arquivo"), "pedido", 7);
        backend.guard("test\u0000arquivo\u0000pedido\u0000");

        assertEquals(7, examined("SELECT * FROM arquivo.pedido"));
        assertNothingLoaded();
    }

    @Test
    @DisplayName("agregação pelo índice columnar conta as entradas sem montar a lista delas")
    void columnarAggregate_countsEntriesInStream() {
        run("CREATE INDEX c_n ON TABLE c COLUMNS n COLUMNAR");
        insert(session, "c", ROWS);
        backend.guard("test\u0000dev\u0000c\u0000", "CI|test|dev|c|");

        assertEquals("COLUMNAR_AGG", strategy("SELECT sum(n) FROM c GROUP ALL"));
        assertEquals(ROWS, examined("SELECT sum(n) FROM c GROUP ALL"));
        assertNothingLoaded();
    }

    @Test
    @DisplayName("tabela particionada soma as partições sem ler registro")
    void partitionedTable_countsKeysOnly() {
        run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
        run("CREATE events:a CONTENT {at: d\"2026-11-01T10:00:00Z\"}");
        run("CREATE events:b CONTENT {at: d\"2026-11-02T10:00:00Z\"}");
        run("CREATE events:c CONTENT {at: d\"2026-11-03T10:00:00Z\"}");
        backend.guard("test\u0000dev\u0000events\u0000");

        assertEquals(3, examined("SELECT * FROM events"));
        run("BEGIN");
        run("CREATE events:d CONTENT {at: d\"2026-11-04T10:00:00Z\"}");
        backend.clearViolations();
        assertEquals(4, examined("SELECT * FROM events"));
        assertNothingLoaded();
        run("CANCEL");
    }

    @Test
    @DisplayName("numa transação conta o que ela escreveu e apagou, sem carregar a tabela")
    void insideTransaction_countsOwnWritesWithoutLoading() {
        run("CREATE INDEX c_n ON TABLE c COLUMNS n COLUMNAR");
        insert(session, "c", ROWS);
        run("BEGIN");
        run("CREATE t:100 SET n = 100");
        run("CREATE t:101 SET n = 101");
        run("DELETE t:1");
        run("UPDATE t:2 SET n = 200");
        run("CREATE c:100 SET n = 100");
        backend.guard(TABLE, "test\u0000dev\u0000c\u0000", "CI|test|dev|c|");

        assertEquals(ROWS + 1, examined("SELECT * FROM t"));
        assertEquals(ROWS + 1, examined("SELECT sum(n) FROM c GROUP ALL"));
        assertNothingLoaded();
        run("CANCEL");
    }

    @Test
    @DisplayName("o EXPLAIN não vira leitura da transação: escrita alheia na tabela não derruba o commit")
    void insideTransaction_estimateDoesNotCauseConflicts() {
        Session other = session("dev");
        run("BEGIN");
        assertEquals(ROWS, examined("SELECT * FROM t"));
        run(other, "UPDATE t:1 SET n = 999");
        run(other, "CREATE t:500 SET n = 500");
        run("CREATE nota:1 SET texto = \"ok\"");

        assertDoesNotThrow(() -> run("COMMIT"));
        assertEquals(ROWS + 1, examined("SELECT * FROM t"));
        assertEquals(1, examined("SELECT * FROM nota"));
    }

    @Test
    @DisplayName("a contagem continua parando quando a consulta é cancelada")
    void cancelledQuery_stopsTheCount() {
        QueryContext.Handle handle = new QueryContext.Handle("explain");
        AtomicInteger checkpoints = new AtomicInteger();
        handle.pacer(() -> {
            if (checkpoints.incrementAndGet() == ROWS / 2) {
                handle.cancel("teste");
            }
        });
        QueryContext.Handle previous = QueryContext.attach(handle);
        try {
            AxonError error = assertThrows(AxonError.class, () -> examined("SELECT * FROM t"));
            assertEquals(QueryContext.CANCELLED, error.code());
        } finally {
            QueryContext.detach(previous);
        }
        assertTrue(checkpoints.get() >= ROWS / 2);
    }

    @Test
    @DisplayName("registro único conta um quando existe e zero quando não")
    void singleRecord_countsZeroOrOne() {
        assertEquals(1, examined("SELECT * FROM t:1"));
        assertEquals(0, examined("SELECT * FROM t:999"));
    }

    @Test
    @DisplayName("subconsulta, lista literal e variável contam as linhas que produzem")
    void computedSources_countTheirRows() {
        assertEquals(5, examined("SELECT * FROM (SELECT * FROM t WHERE n <= 5)"));
        assertEquals(2, examined("SELECT * FROM [{a: 1}, {a: 2}]"));
        assertEquals(3, run("LET $x = (SELECT * FROM t WHERE n <= 3); EXPLAIN SELECT * FROM $x")
            .asObject().get("examinedRows").asLong());
    }

    @Test
    @DisplayName("grafo: o percurso conta o registro de partida; arestas sem registro não são linhas")
    void graphSources_keepTheirCounts() {
        run("CREATE user:1");
        run("CREATE article:1");
        run("CREATE article:2");
        run("RELATE user:1->wrote->article:1");
        run("RELATE user:1->wrote->article:2");

        assertEquals(1, examined("SELECT ->wrote->article FROM user:1"));
        assertEquals(0, examined("SELECT * FROM wrote"));
    }

    @Test
    @DisplayName("com regras de acesso a estimativa é a mesma de antes, sem filtro por linha")
    void permissions_estimateIsUnchanged() {
        run("DEFINE TABLE doc SCHEMALESS PERMISSIONS FOR select WHERE published = true");
        run("CREATE doc:1 SET published = true");
        run("CREATE doc:2 SET published = false");
        run("CREATE doc:3 SET published = false");

        assertEquals(1, run("SELECT * FROM doc").asArray().size());
        assertEquals(3, examined("SELECT * FROM doc"));
    }

    @Test
    @DisplayName("tabela sem linhas, chave de texto e chave que não se lê de volta")
    void edgeKeys_keepTheirCounts() {
        assertEquals(0, examined("SELECT * FROM vazia"));
        run("CREATE nome:ana");
        run("CREATE nome:7");
        assertEquals(2, examined("SELECT * FROM nome"));
        backend.put("test\u0000dev\u0000nome\u0000xsemvolta", "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, examined("SELECT * FROM nome"));
    }
}
