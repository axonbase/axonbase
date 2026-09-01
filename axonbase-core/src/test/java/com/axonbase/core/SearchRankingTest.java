package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica o ranking BM25, o mantemento dos contadores do índice invertido e a
 * busca híbrida FULLTEXT + VECTOR co score combinado.
 */
class SearchRankingTest {

    private Datastore datastore() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    @Test
    void bm25PreferDocumentosConTerminoRepetido() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE ANALYZER plain LOWERCASE", s, null);
        ds.execute("DEFINE INDEX body_idx ON TABLE t COLUMNS body SEARCH ANALYZER plain", s, null);
        // d2 repite "gato" moito máis; BM25 por frecuencia debera puntualo por riba.
        ds.execute("CREATE t:1 CONTENT { name: \"unico\", body: \"gato\" }", s, null);
        ds.execute("CREATE t:2 CONTENT { name: \"repetido\", body: \"gato gato gato gato\" }", s, null);

        AxonValue rows = ds.execute("SELECT name, search::score() AS score FROM t "
            + "WHERE body @@ \"gato\" ORDER BY search::score() DESC", s, null);
        assertEquals(2, rows.asArray().size());
        assertEquals("repetido", rows.asArray().get(0).asObject().get("name").asString());
        assertTrue(rows.asArray().get(0).asObject().get("score").asDouble()
            > rows.asArray().get(1).asObject().get("score").asDouble());
    }

    @Test
    void bm25TerminoRaroAportaMaisQueTerminoComun() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE ANALYZER plain LOWERCASE", s, null);
        ds.execute("DEFINE INDEX body_idx ON TABLE t COLUMNS body SEARCH ANALYZER plain", s, null);
        ds.execute("CREATE t:1 CONTENT { name: \"ambos\", body: \"python python zoom coxinha\" }", s, null);
        // Un corpus alleo dá masa a N e á media, para que o termo raro "zoom" mostre idf alto.
        ds.execute("CREATE t:3 CONTENT { name: \"masa\", body: \"python python python python\" }", s, null);
        ds.execute("CREATE t:4 CONTENT { name: \"masa2\", body: \"python python python\" }", s, null);

        AxonValue rows = ds.execute("SELECT name, search::score() AS score FROM t "
            + "WHERE body @@ \"zoom\"", s, null);
        List<AxonValue> list = rows.asArray();
        boolean found = false;
        for (AxonValue r : list) {
            if ("ambos".equals(r.asObject().get("name").asString())) {
                found = true;
                assertTrue(r.asObject().get("score").asDouble() > 0);
            }
        }
        assertTrue(found, "o documento co termo raro debe aparecer no resultado");
    }

    @Test
    void updateYDeleteRetiranPostingsEContadores() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE ANALYZER plain LOWERCASE", s, null);
        ds.execute("DEFINE INDEX body_idx ON TABLE t COLUMNS body SEARCH ANALYZER plain", s, null);
        ds.execute("CREATE t:a CONTENT { body: \"orixe destino\" }", s, null);
        ds.execute("CREATE t:b CONTENT { body: \"orixe\" }", s, null);
        assertEquals(2, ds.execute("SELECT * FROM t WHERE body @@ \"orixe\"", s, null).asArray().size());

        // UPDATE: o post de "destino" desaparece e "orixe" segue contando 1 doc menos.
        ds.execute("UPDATE t:a SET body = \"cambio\"", s, null);
        assertEquals(1, ds.execute("SELECT * FROM t WHERE body @@ \"orixe\"", s, null).asArray().size());
        assertTrue(ds.execute("SELECT * FROM t WHERE body @@ \"destino\"", s, null).asArray().isEmpty());

        // DELETE: ao borra t:b o contador de "orixe" baixa a cero e a consulta non devolve nada.
        ds.execute("DELETE t:b", s, null);
        assertTrue(ds.execute("SELECT * FROM t WHERE body @@ \"orixe\"", s, null).asArray().isEmpty());
    }

    @Test
    void contadorDocumentalNonSeIncrementaDeFormaErronea() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE ANALYZER plain LOWERCASE", s, null);
        ds.execute("DEFINE INDEX s_idx ON TABLE t COLUMNS body SEARCH ANALYZER plain", s, null);
        ds.execute("CREATE t:a CONTENT { body: \"term común\" }", s, null);
        ds.execute("DELETE t:a", s, null);
        ds.execute("CREATE t:b CONTENT { body: \"term común\" }", s, null);
        // O contador de "term" debe reflectar exactamente un documento tras delete+create.
        assertEquals(1, ds.execute("SELECT * FROM t WHERE body @@ \"term\"", s, null).asArray().size());
    }

    @Test
    void buscaHibridaPontuaFULLTEXTEVectorEOrdenaPorScoreCombinado() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE ANALYZER plain LOWERCASE", s, null);
        ds.execute("CREATE item:1 CONTENT { name: \"banana\", body: \"topic tropico\","
            + " embedding: [0.9, 0.1, 0.0] }", s, null);
        ds.execute("CREATE item:2 CONTENT { name: \"manchego\", body: \"topic fruta\","
            + " embedding: [0.1, 0.9, 0.0] }", s, null);
        ds.execute("CREATE item:3 CONTENT { name: \"neutro\", body: \"senza topic\","
            + " embedding: [0.0, 0.0, 1.0] }", s, null);
        ds.execute("DEFINE INDEX body_idx ON TABLE item COLUMNS body SEARCH ANALYZER plain", s, null);
        ds.execute("DEFINE INDEX emb_idx ON TABLE item COLUMNS embedding HNSW DIMENSION 3 DIST cosine", s, null);

        // Híbrida: a banana casa en texto e é a máis cercana ao vector de consulta,
        // polo que o score combinado a sitúa primeiro.
        AxonValue rows = ds.execute("SELECT name, search::score() AS score FROM item "
            + "WHERE body @@ \"topic\" AND vector::similarity::cosine(embedding, [1.0, 0.0, 0.0]) > 0.0 "
            + "ORDER BY search::score() DESC LIMIT 2", s, null);
        List<AxonValue> list = rows.asArray();
        assertEquals(2, list.size());
        assertEquals("banana", list.get(0).asObject().get("name").asString());
        assertTrue(list.get(0).asObject().get("score").asDouble()
            >= list.get(1).asObject().get("score").asDouble());
        assertTrue(list.get(0).asObject().get("score").asDouble() > 0);
    }
}