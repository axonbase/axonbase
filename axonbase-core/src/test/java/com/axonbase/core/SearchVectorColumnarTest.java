package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Full-Text, Vector e Columnar: @@ com vetor, destaque, INFO e agregação pelo índice")
class SearchVectorColumnarTest {

    private Datastore ds;
    private Session session;

    @BeforeEach
    void setUp() {
        ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        session = Session.create();
        session.namespace("test");
        session.database("dev");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private static List<String> col(AxonValue rows, String name) {
        List<String> out = new ArrayList<>();
        rows.asArray().forEach(r -> {
            AxonValue v = r.asObject().get(name);
            out.add(v.isString() ? v.asString() : v.toString());
        });
        return out;
    }

    private void articles() {
        run("CREATE ANALYZER pt LOWERCASE STEMMING STOPWORDS \"de\"");
        run("DEFINE TABLE artigo SCHEMALESS");
        run("CREATE INDEX corpo_busca ON TABLE artigo COLUMNS corpo SEARCH ANALYZER pt");
        run("CREATE INDEX emb_vec ON TABLE artigo COLUMNS emb HNSW DIMENSION 3 DIST cos");
        run("CREATE artigo:1 CONTENT {titulo: 'Motoristas por perto', corpo: 'Busca de motoristas num raio', emb: [0.9, 0.1, 0.0]}");
        run("CREATE artigo:2 CONTENT {titulo: 'Grafos de pessoas', corpo: 'Quem segue quem na rede', emb: [0.1, 0.9, 0.1]}");
        run("CREATE artigo:3 CONTENT {titulo: 'Indices geograficos', corpo: 'Motoristas e mapas com indice', emb: [0.8, 0.2, 0.1]}");
    }

    // ------------------------------------------------------------------ vetor

    @Test
    @DisplayName("WHERE campo @@ vetor ORDER BY distância LIMIT devolve os vizinhos mais próximos")
    void vectorMatchReturnsNearestNeighbours() {
        articles();
        AxonValue rows = run("SELECT titulo FROM artigo WHERE emb @@ [0.85, 0.15, 0.0] "
            + "ORDER BY vector::distance::cosine(emb, [0.85, 0.15, 0.0]) ASC LIMIT 2");
        assertEquals(List.of("Motoristas por perto", "Indices geograficos"), col(rows, "titulo"));
        assertEquals("VECTOR", run("EXPLAIN SELECT titulo FROM artigo WHERE emb @@ [0.85, 0.15, 0.0] "
            + "ORDER BY vector::distance::cosine(emb, [0.85, 0.15, 0.0]) LIMIT 2").asObject().get("strategy").asString());
        AxonValue byVar = run("LET $q = [0.85, 0.15, 0.0]; SELECT titulo FROM artigo WHERE emb @@ $q "
            + "ORDER BY vector::distance::cosine(emb, $q) LIMIT 1");
        assertEquals(List.of("Motoristas por perto"), col(byVar, "titulo"));
    }

    @Test
    @DisplayName("busca híbrida: texto @@ e vetor @@ no mesmo WHERE")
    void hybridTextAndVectorMatch() {
        articles();
        AxonValue rows = run("LET $q = [0.85, 0.15, 0.0]; SELECT titulo, search::score() AS score FROM artigo "
            + "WHERE corpo @@ 'motoristas' AND emb @@ $q ORDER BY search::score() DESC");
        assertEquals(2, rows.asArray().size());
        assertEquals("Motoristas por perto", rows.asArray().get(0).asObject().get("titulo").asString());
        assertEquals("HYBRID", run("LET $q = [0.85, 0.15, 0.0]; EXPLAIN SELECT titulo FROM artigo "
            + "WHERE corpo @@ 'motoristas' AND emb @@ $q").asObject().get("strategy").asString());
        // a forma com limiar de similaridade continua valendo
        assertEquals(2, run("LET $q = [0.85, 0.15, 0.0]; SELECT titulo FROM artigo WHERE corpo @@ 'motoristas' "
            + "AND vector::similarity::cosine(emb, $q) > 0.5").asArray().size());
    }

    // ----------------------------------------------------------------- full-text

    @Test
    @DisplayName("search::highlight marca a palavra inteira, não só o radical do stemming")
    void highlightWrapsTheWholeWord() {
        articles();
        AxonValue rows = run("SELECT search::highlight(corpo) AS trecho FROM artigo WHERE corpo @@ 'motoristas' "
            + "ORDER BY titulo");
        List<String> trechos = col(rows, "trecho");
        assertTrue(trechos.contains("Busca de <em>motoristas</em> num raio"), trechos.toString());
        assertTrue(trechos.contains("<em>Motoristas</em> e mapas com indice"), trechos.toString());
    }

    // ---------------------------------------------------------------------- INFO

    @Test
    @DisplayName("INFO FOR TABLE informa o tipo de cada índice e os parâmetros do HNSW")
    void infoDescribesEachIndexKind() {
        articles();
        run("CREATE INDEX n_unique ON TABLE artigo COLUMNS titulo UNIQUE");
        run("CREATE INDEX n_col ON TABLE artigo COLUMNS views COLUMNAR");
        run("CREATE INDEX n_geo ON TABLE artigo COLUMNS pos GEO");
        var idx = run("INFO FOR TABLE artigo").asObject().get("indexes").asObject();
        assertEquals("SEARCH", idx.get("corpo_busca").asObject().get("kind").asString());
        assertEquals("HNSW", idx.get("emb_vec").asObject().get("kind").asString());
        assertEquals("UNIQUE", idx.get("n_unique").asObject().get("kind").asString());
        assertEquals("COLUMNAR", idx.get("n_col").asObject().get("kind").asString());
        assertTrue(idx.get("n_col").asObject().get("columnar").asBool());
        assertEquals("GEO", idx.get("n_geo").asObject().get("kind").asString());
        var hnsw = idx.get("emb_vec").asObject().get("hnsw").asObject();
        assertTrue(hnsw.get("m").asLong() > 0 && hnsw.get("efc").asLong() > 0 && hnsw.get("efs").asLong() > 0);
        assertFalse(idx.get("n_col").asObject().containsKey("hnsw"));
    }

    @Test
    @DisplayName("INFO FOR DATABASE lista os analisadores")
    void infoDatabaseListsAnalyzers() {
        articles();
        var analyzers = run("INFO FOR DATABASE").asObject().get("analyzers").asObject();
        var pt = analyzers.get("pt").asObject();
        assertTrue(pt.get("lowercase").asBool());
        assertTrue(pt.get("stemming").asBool());
        assertEquals("de", pt.get("stopwords").asArray().get(0).asString());
    }

    // ------------------------------------------------------------------ columnar

    private void people(String table, boolean indexed) {
        run("DEFINE TABLE " + table + " SCHEMALESS");
        if (indexed) {
            run("CREATE INDEX " + table + "_age ON TABLE " + table + " COLUMNS age COLUMNAR");
            run("CREATE INDEX " + table + "_dept ON TABLE " + table + " COLUMNS dept COLUMNAR");
        }
        int[] ages = {20, 25, 30, 35, 40, 45};
        String[] depts = {"a", "a", "b", "b", "b", "c"};
        for (int i = 0; i < ages.length; i++) {
            run("CREATE " + table + ":" + (i + 1) + " CONTENT {nome: 'p" + i + "', age: " + ages[i]
                + ", dept: '" + depts[i] + "'}");
        }
    }

    @Test
    @DisplayName("count, sum, avg, min e max pelo índice dão o mesmo resultado que pela tabela")
    void columnarAggregatesMatchTheTableScan() {
        people("plain", false);
        people("ind", true);
        String agg = "count() AS n, sum(age) AS soma, avg(age) AS media, min(age) AS menor, max(age) AS maior";
        assertEquals(run("SELECT " + agg + " FROM plain GROUP ALL"), run("SELECT " + agg + " FROM ind GROUP ALL"));
        assertEquals("COLUMNAR_AGG", run("EXPLAIN SELECT " + agg + " FROM ind GROUP ALL").asObject().get("strategy").asString());
        assertEquals("FULL_SCAN", run("EXPLAIN SELECT " + agg + " FROM plain GROUP ALL").asObject().get("strategy").asString());

        String grouped = "SELECT dept, count() AS n, avg(age) AS media FROM %s GROUP BY dept ORDER BY dept";
        assertEquals(run(String.format(grouped, "plain")), run(String.format(grouped, "ind")));
        assertEquals("COLUMNAR_AGG", run("EXPLAIN " + String.format(grouped, "ind")).asObject().get("strategy").asString());

        String filtered = "SELECT count() AS n, sum(age) AS soma FROM %s WHERE age > 28 GROUP ALL";
        assertEquals(run(String.format(filtered, "plain")), run(String.format(filtered, "ind")));
        assertEquals(4, run(String.format(filtered, "ind")).asArray().get(0).asObject().get("n").asLong());

        assertEquals(6, run("SELECT VALUE count() FROM ind").asLong());
    }

    @Test
    @DisplayName("o índice acompanha UPDATE e DELETE")
    void columnarFollowsWrites() {
        people("ind", true);
        run("UPDATE ind:1 SET age = 100");
        run("DELETE ind:6");
        AxonValue r = run("SELECT count() AS n, max(age) AS maior, min(age) AS menor FROM ind GROUP ALL")
            .asArray().get(0);
        assertEquals(5, r.asObject().get("n").asLong());
        assertEquals(100, r.asObject().get("maior").asLong());
        assertEquals(25, r.asObject().get("menor").asLong());
    }

    @Test
    @DisplayName("coluna sem índice columnar ou o campo id fazem a consulta ler os documentos")
    void fallsBackToTheTableWhenNotCovered() {
        people("ind", true);
        assertEquals("FULL_SCAN", run("EXPLAIN SELECT nome, count() FROM ind GROUP BY nome").asObject().get("strategy").asString());
        assertEquals("FULL_SCAN", run("EXPLAIN SELECT count(), max(id) FROM ind GROUP ALL").asObject().get("strategy").asString());
        assertEquals(6, run("SELECT nome, count() AS n FROM ind GROUP BY nome").asArray().size());
    }

    @Test
    @DisplayName("índice criado depois dos dados também responde pelo índice")
    void indexCreatedAfterTheData() {
        people("late", false);
        run("CREATE INDEX late_age ON TABLE late COLUMNS age COLUMNAR");
        assertEquals("COLUMNAR_AGG", run("EXPLAIN SELECT sum(age) FROM late GROUP ALL").asObject().get("strategy").asString());
        assertEquals(195, run("SELECT sum(age) AS s FROM late GROUP ALL").asArray().get(0).asObject().get("s").asLong());
    }

    @Test
    @DisplayName("sem o marcador do formato novo (índice antigo) a consulta lê os documentos")
    void legacyIndexWithoutMarkerFallsBack() {
        var backend = new com.axonbase.core.storage.MemoryBackend();
        Datastore first = new Datastore(backend);
        first.createDatabase("test", "dev");
        first.execute("DEFINE TABLE t SCHEMALESS; CREATE INDEX t_age ON TABLE t COLUMNS age COLUMNAR; "
            + "CREATE t:1 CONTENT {age: 1}; CREATE t:2 CONTENT {age: 2}", session, null);
        backend.keysWithPrefix("CIV|").forEach(backend::delete);
        assertEquals("FULL_SCAN", first.execute("EXPLAIN SELECT sum(age) FROM t GROUP ALL", session, null)
            .asObject().get("strategy").asString());
        assertEquals(3, first.execute("SELECT sum(age) AS s FROM t GROUP ALL", session, null)
            .asArray().get(0).asObject().get("s").asLong());
    }

    @Test
    @DisplayName("DROP INDEX aceita ON TABLE, apaga as entradas e o marcador")
    void dropIndexCleansUp() {
        people("ind", true);
        run("DROP INDEX ind_age ON TABLE ind");
        assertEquals("FULL_SCAN", run("EXPLAIN SELECT sum(age) FROM ind GROUP ALL").asObject().get("strategy").asString());
        assertEquals(195, run("SELECT sum(age) AS s FROM ind GROUP ALL").asArray().get(0).asObject().get("s").asLong());
        run("DROP INDEX ind_dept");
        assertFalse(run("INFO FOR TABLE ind").asObject().get("indexes").asObject().containsKey("ind_dept"));
    }
}
