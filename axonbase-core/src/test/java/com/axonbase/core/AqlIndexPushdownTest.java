package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Índice em parte de um AND, geo::contains no índice GEO, cache de subquery e agregação em fluxo. */
class AqlIndexPushdownTest {

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

    private String strategy(String select) {
        return run("EXPLAIN " + select).asObject().get("strategy").asString();
    }

    private static TreeSet<String> ids(AxonValue rows) {
        TreeSet<String> out = new TreeSet<>();
        for (AxonValue r : rows.asArray()) {
            String id = r.asObject().get("id").asString();
            out.add(id.substring(id.indexOf(':') + 1)); // sem o nome da tabela, para comparar tabelas gêmeas
        }
        return out;
    }

    // ----------------------------------------------------- índice em parte do AND

    @Test
    void andComIgualdadeEmColunaUnicaUsaOIndiceDaParteIndexada() {
        run("DEFINE TABLE u SCHEMALESS");
        run("DEFINE INDEX u_email ON TABLE u COLUMNS email UNIQUE");
        run("INSERT INTO u [{email: 'a@x', n: 5}, {email: 'b@x', n: 1}, {email: 'c@x', n: 9}]");
        String sql = "SELECT email FROM u WHERE n > 2 AND email = 'c@x'";
        assertEquals("UNIQUE_LOOKUP", strategy(sql));
        AxonValue rows = run(sql);
        assertEquals(1, rows.asArray().size());
        assertEquals("c@x", rows.asArray().get(0).asObject().get("email").asString());
        // o resto do WHERE continua valendo sobre o candidato do índice
        assertEquals(0, run("SELECT email FROM u WHERE n > 100 AND email = 'c@x'").asArray().size());
    }

    @Test
    void andSemNenhumaParteIndexadaContinuaVarredura() {
        run("INSERT INTO u [{email: 'a@x', n: 5}]");
        assertEquals("FULL_SCAN", strategy("SELECT * FROM u WHERE n > 2 AND email = 'a@x'"));
        assertEquals(1, run("SELECT * FROM u WHERE n > 2 AND email = 'a@x'").asArray().size());
    }

    @Test
    void andComIndiceColunarNaSegundaParte() {
        run("DEFINE TABLE c SCHEMALESS");
        run("DEFINE INDEX c_city ON TABLE c COLUMNS city COLUMNAR");
        run("INSERT INTO c [{city: 'SP', n: 1}, {city: 'SP', n: 5}, {city: 'RJ', n: 7}]");
        String sql = "SELECT n FROM c WHERE n >= 2 AND city = 'SP'";
        assertEquals("COLUMNAR", strategy(sql));
        assertEquals(1, run(sql).asArray().size());
    }

    // --------------------------------------------------------- geo::contains

    private static final String SQUARE = "{type: 'Polygon', coordinates: [[[-46.66,-23.58],[-46.62,-23.58],[-46.62,-23.54],[-46.66,-23.54],[-46.66,-23.58]]]}";

    /** Duas tabelas com os mesmos pontos: {@code geo_idx} com índice GEO e {@code geo_raw} sem. */
    private void seedPoints(int n) {
        run("DEFINE INDEX geo_idx_pos ON TABLE geo_idx COLUMNS pos GEO");
        Random random = new Random(42);
        for (int from = 0; from < n; from += 200) {
            StringBuilder rows = new StringBuilder();
            for (int i = from; i < Math.min(n, from + 200); i++) {
                // metade dentro da caixa do polígono (e do polígono), metade espalhada pela cidade
                double lon = i % 2 == 0 ? -46.66 + random.nextDouble() * 0.04 : -46.80 + random.nextDouble() * 0.30;
                double lat = i % 2 == 0 ? -23.58 + random.nextDouble() * 0.04 : -23.70 + random.nextDouble() * 0.25;
                if (i > from) {
                    rows.append(',');
                }
                rows.append(String.format(Locale.ROOT, "{id: 'p%d', pos: {type: 'Point', coordinates: [%.6f, %.6f]}}", i, lon, lat));
            }
            run("INSERT INTO geo_idx [" + rows + "]");
            run("INSERT INTO geo_raw [" + rows + "]");
        }
    }

    @Test
    void geoContainsComPoligonoLiteralDaOMesmoResultadoComEmSemIndice() {
        seedPoints(1200);
        String where = " WHERE geo::contains(" + SQUARE + ", pos)";
        assertEquals("GEO", strategy("SELECT id FROM geo_idx" + where));
        assertEquals("FULL_SCAN", strategy("SELECT id FROM geo_raw" + where));
        TreeSet<String> indexed = ids(run("SELECT id FROM geo_idx" + where));
        TreeSet<String> scanned = ids(run("SELECT id FROM geo_raw" + where));
        assertTrue(scanned.size() > 100, "o polígono precisa pegar bastante ponto: " + scanned.size());
        assertEquals(scanned, indexed);
    }

    @Test
    void geoContainsDentroDeUmAndComFiltroExtra() {
        seedPoints(600);
        String where = " WHERE id != 'p0' AND geo::contains(" + SQUARE + ", pos)";
        assertEquals("GEO", strategy("SELECT id FROM geo_idx" + where));
        assertEquals(ids(run("SELECT id FROM geo_raw" + where)), ids(run("SELECT id FROM geo_idx" + where)));
    }

    @Test
    void geoContainsComPoligonoDeOutraTabelaPorParent() {
        seedPoints(800);
        run("INSERT INTO area [{id: 'a', geo: " + SQUARE + "}, "
            + "{id: 'b', geo: {type: 'Polygon', coordinates: [[[-46.80,-23.70],[-46.70,-23.70],[-46.70,-23.60],[-46.80,-23.60],[-46.80,-23.70]]]}}]");
        AxonValue viaIndex = run("SELECT id, array::len((SELECT id FROM geo_idx WHERE geo::contains($parent.geo, pos))) AS n FROM area ORDER BY id");
        AxonValue viaScan = run("SELECT id, array::len((SELECT id FROM geo_raw WHERE geo::contains($parent.geo, pos))) AS n FROM area ORDER BY id");
        assertEquals(viaScan, viaIndex);
        assertTrue(viaIndex.asArray().get(0).asObject().get("n").asLong() > 50);
    }

    @Test
    void geoContainsComPoligonoDeSubqueryEVariavel() {
        seedPoints(600);
        run("INSERT INTO area [{id: 'a', geo: " + SQUARE + "}]");
        AxonValue viaSubquery = run("SELECT id FROM geo_idx WHERE geo::contains((SELECT VALUE geo FROM area:a), pos)");
        AxonValue viaVariable = run("LET $g = (SELECT VALUE geo FROM area:a); SELECT id FROM geo_idx WHERE geo::contains($g, pos)");
        TreeSet<String> expected = ids(run("SELECT id FROM geo_raw WHERE geo::contains(" + SQUARE + ", pos)"));
        assertEquals(expected, ids(viaSubquery));
        assertEquals(expected, ids(viaVariable));
    }

    @Test
    void geoContainsComPoligonoEnormeVoltaParaAVarreduraEAcertaMesmoAssim() {
        seedPoints(400);
        String world = "{type: 'Polygon', coordinates: [[[-179,-80],[179,-80],[179,80],[-179,80],[-179,-80]]]}";
        assertEquals(400, run("SELECT id FROM geo_idx WHERE geo::contains(" + world + ", pos)").asArray().size());
    }

    @Test
    void geoContainsComCampoDaLinhaNoPoligonoNaoUsaOIndice() {
        seedPoints(100);
        // o polígono vem da própria linha: não dá para escolher células antes de ler
        assertEquals("FULL_SCAN", strategy("SELECT id FROM geo_idx WHERE geo::contains(pos, pos)"));
    }

    @Test
    void atualizarOPontoTiraAsCelulasAntigasDoIndiceGeo() {
        run("DEFINE INDEX s_pos ON TABLE s COLUMNS pos GEO");
        run("CREATE s:a CONTENT {pos: {type: 'Point', coordinates: [-46.63, -23.55]}}");
        String near = "SELECT * FROM s WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.63, -23.55]}) < 500";
        assertEquals(1, run("EXPLAIN ANALYZE " + near).asObject().get("examinedRows").asLong());
        run("UPDATE s:a SET pos = {type: 'Point', coordinates: [-46.90, -23.80]}");
        // o WHERE esconderia uma célula obsoleta; examinedRows conta o que o índice devolveu antes dele
        assertEquals(0, run("EXPLAIN ANALYZE " + near).asObject().get("examinedRows").asLong());
        String moved = "SELECT * FROM s WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.90, -23.80]}) < 500";
        assertEquals(1, run("EXPLAIN ANALYZE " + moved).asObject().get("examinedRows").asLong());
        run("DELETE s:a");
        assertEquals(0, run(moved).asArray().size());
    }

    @Test
    void registroSemGeometriaOuComGeometriaNulaNaoQuebraOIndice() {
        run("DEFINE INDEX s_pos ON TABLE s COLUMNS pos GEO");
        run("CREATE s:a CONTENT {nome: 'sem ponto'}");
        run("CREATE s:b CONTENT {pos: {type: 'Point', coordinates: [-46.63, -23.55]}}");
        run("UPDATE s:b SET pos = NONE");
        run("UPDATE s:a SET pos = {type: 'Point', coordinates: [-46.63, -23.55]}");
        assertEquals(1, run("SELECT * FROM s WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.63, -23.55]}) < 500").asArray().size());
    }

    // ------------------------------------------------------ cache de subquery

    @Test
    void subqueryIndependenteDaLinhaVeDadosNovosNoStatementSeguinte() {
        run("INSERT INTO a [{n: 1}, {n: 2}, {n: 3}]");
        run("INSERT INTO b [{y: 1}]");
        AxonValue out = run("SELECT VALUE n FROM a WHERE n IN (SELECT VALUE y FROM b) ORDER BY n;"
            + " INSERT INTO b [{y: 3}];"
            + " SELECT VALUE n FROM a WHERE n IN (SELECT VALUE y FROM b) ORDER BY n");
        assertEquals(2, out.asArray().size(), "o segundo SELECT enxerga o registro novo de b");
        assertEquals(1, out.asArray().get(0).asLong());
        assertEquals(3, out.asArray().get(1).asLong());
    }

    @Test
    void subqueryCorrelacionadaContinuaAvaliadaPorLinha() {
        run("INSERT INTO a [{n: 1}, {n: 2}]");
        run("INSERT INTO b [{y: 1, z: 10}, {y: 2, z: 20}]");
        AxonValue rows = run("SELECT n, (SELECT VALUE z FROM b WHERE y = $parent.n) AS z FROM a ORDER BY n");
        assertEquals(10, rows.asArray().get(0).asObject().get("z").asArray().get(0).asLong());
        assertEquals(20, rows.asArray().get(1).asObject().get("z").asArray().get(0).asLong());
    }

    // ------------------------------------------ agregação em fluxo e permissões

    @Test
    void agregacaoEmFluxoRespeitaPermissoesDeLeituraDaTabela() {
        run("DEFINE TABLE post SCHEMALESS PERMISSIONS FOR select WHERE published = true");
        run("INSERT INTO post [{published: true, n: 1}, {published: false, n: 2}, {published: true, n: 3}]");
        AxonValue rows = run("SELECT count() AS n, sum(n) AS total FROM post GROUP ALL");
        assertEquals(2, rows.asArray().get(0).asObject().get("n").asLong());
        assertEquals(4, rows.asArray().get(0).asObject().get("total").asLong());
    }

    @Test
    void agregacaoComIndiceNaPrimeiraParteDoAndAgregaSoOsCandidatos() {
        run("DEFINE TABLE v SCHEMALESS");
        run("DEFINE INDEX v_city ON TABLE v COLUMNS city COLUMNAR");
        run("INSERT INTO v [{city: 'SP', valor: 10}, {city: 'SP', valor: 20}, {city: 'RJ', valor: 99}]");
        List<AxonValue> rows = run("SELECT count() AS n, sum(valor) AS total FROM v WHERE city = 'SP' AND valor > 0 GROUP ALL").asArray();
        assertEquals(2, rows.get(0).asObject().get("n").asLong());
        assertEquals(30, rows.get(0).asObject().get("total").asLong());
    }
}
