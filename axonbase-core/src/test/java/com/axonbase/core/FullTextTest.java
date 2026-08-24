package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullTextTest {

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
    void analyzerEIndiceInvertidoFiltramPorTodosOsTermos() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE ANALYZER pt LOWERCASE STOPWORDS \"o\", \"a\" STEMMING", s, null);
        ds.execute("DEFINE INDEX body_search ON TABLE article COLUMNS body SEARCH ANALYZER pt", s, null);
        ds.execute("CREATE article:a CONTENT {title: \"A\", body: \"O gato correndo rápido\"}", s, null);
        ds.execute("CREATE article:b CONTENT {title: \"B\", body: \"Cachorro corre devagar\"}", s, null);

        AxonValue rows = ds.execute("SELECT title FROM article WHERE body @@ \"GATO CORRENDO\"", s, null);
        assertEquals(1, rows.asArray().size());
        assertEquals("A", rows.asArray().get(0).asObject().get("title").asString());

        AxonValue ranked = ds.execute("SELECT title, search::score() AS score, "
            + "search::highlight(body) AS snippet FROM article WHERE body @@ \"gato\"", s, null);
        assertTrue(ranked.asArray().get(0).asObject().get("score").asLong() > 0);
        assertTrue(ranked.asArray().get(0).asObject().get("snippet").asString().contains("<em>gato</em>"));
    }

    @Test
    void indiceEAtualizadoNoUpdateEDelete() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE ANALYZER plain LOWERCASE", s, null);
        ds.execute("CREATE article:a CONTENT {body: \"primeira versão\"}", s, null);
        // Definir o índice depois dos dados também indexa os documentos existentes.
        ds.execute("DEFINE INDEX body_search ON TABLE article COLUMNS body SEARCH ANALYZER plain", s, null);
        assertEquals(1, ds.execute("SELECT * FROM article WHERE body @@ \"primeira\"", s, null)
            .asArray().size());

        ds.execute("UPDATE article:a SET body = \"segunda versão\"", s, null);
        assertTrue(ds.execute("SELECT * FROM article WHERE body @@ \"primeira\"", s, null)
            .asArray().isEmpty());
        assertEquals(1, ds.execute("SELECT * FROM article WHERE body @@ \"segunda\"", s, null)
            .asArray().size());

        ds.execute("DELETE article:a", s, null);
        assertTrue(ds.execute("SELECT * FROM article WHERE body @@ \"segunda\"", s, null)
            .asArray().isEmpty());
    }
}
