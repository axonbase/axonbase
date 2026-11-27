package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Busca textual: @@ junto de outra condição mantém índice, analisador e pontuação")
class FullTextCombinedTest {

    private final Datastore ds = Datastore.memory();
    private final Session session = Session.create();

    @BeforeEach
    void setUp() {
        ds.createDatabase("test", "dev");
        session.namespace("test");
        session.database("dev");
        run("DEFINE ANALYZER pt LOWERCASE STEMMING");
        run("DEFINE INDEX body_search ON TABLE art COLUMNS body SEARCH ANALYZER pt");
        run("CREATE art:1 SET n = 1, body = 'os bancos', t = 'x'");
        run("CREATE art:2 SET n = 2, body = 'um banco', t = 'x'");
        run("CREATE art:3 SET n = 3, body = 'um banco', t = 'y'");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private List<AxonValue> rows(String sql) {
        return run(sql).asArray();
    }

    @ParameterizedTest
    @ValueSource(strings = {"body @@ 'banco' AND t = 'x'", "t = 'x' AND body @@ 'banco'",
        "t = 'x' AND n > 0 AND body @@ 'banco'"})
    void valemOAnalisadorEAPontuacao(String where) {
        double alone = rows("SELECT search::score() AS s FROM art WHERE body @@ 'banco'")
            .get(0).asObject().get("s").asDouble();

        List<AxonValue> rows = rows("SELECT n, search::score() AS s FROM art WHERE " + where);

        assertEquals(1, rows.size(), rows.toString());
        assertEquals(2, rows.get(0).asObject().get("n").asLong());
        assertTrue(alone > 0);
        assertEquals(alone, rows.get(0).asObject().get("s").asDouble(), 1e-9);
    }

    @Test
    void agregacaoContaSomenteOQueOIndiceEncontra() {
        List<AxonValue> rows = rows("SELECT count() FROM art WHERE body @@ 'banco' AND t = 'x' GROUP ALL");

        assertEquals(1, rows.get(0).asObject().get("count").asLong());
    }

    @Test
    void explainMostraOIndiceDeBusca() {
        AxonValue plan = run("EXPLAIN SELECT * FROM art WHERE body @@ 'banco' AND t = 'x'");

        assertEquals("FULLTEXT", plan.asObject().get("strategy").asString());
        assertEquals("body_search", plan.asObject().get("indexName").asString());
    }
}
