package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Consultas em escala: LIMIT que para cedo, top-K, GEO com coordenadas, vizinhos mais próximos e VH sem varredura")
class ScaleQueryTest {

    private final CountingBackend backend = new CountingBackend();
    private final Datastore ds = new Datastore(backend);
    private final Session session = Session.create();

    ScaleQueryTest() {
        ds.createDatabase("test", "dev");
        session.namespace("test");
        session.database("dev");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private List<Long> ns(String sql) {
        List<Long> out = new ArrayList<>();
        run(sql).asArray().forEach(r -> out.add(r.asObject().get("n").asLong()));
        return out;
    }

    private String strategy(String sql) {
        return run("EXPLAIN " + sql).asObject().get("strategy").asString();
    }

    // ------------------------------------------------------------------ LIMIT, START e top-K

    private void fill(String table, int rows) {
        for (int i = 0; i < rows; i++) {
            run("CREATE " + table + ":" + i + " CONTENT {n: " + i + ", status: '" + "abcd".charAt(i % 4)
                + "', valor: " + ((i * 37) % 101) + "}");
        }
    }

    @Test
    @DisplayName("LIMIT e START devolvem a fatia do resultado completo, com e sem índice, e conjunção de filtros")
    void limitAndStartEqualTheFullResultSlice() {
        fill("t", 400);
        run("CREATE INDEX t_status ON TABLE t COLUMNS status COLUMNAR");
        for (String where : List.of("", " WHERE status = 'b'", " WHERE valor > 50", " WHERE status = 'b' AND valor > 30")) {
            List<Long> all = ns("SELECT n FROM t" + where);
            assertTrue(all.size() > 20, where);
            assertEquals(all.subList(0, 10), ns("SELECT n FROM t" + where + " LIMIT 10"), where);
            assertEquals(all.subList(5, 15), ns("SELECT n FROM t" + where + " LIMIT 10 START 5"), where);
            int tail = all.size() - 3;
            assertEquals(all.subList(tail, all.size()), ns("SELECT n FROM t" + where + " LIMIT 10 START " + tail), where);
            assertEquals(List.of(), ns("SELECT n FROM t" + where + " LIMIT 5 START " + (all.size() + 7)), where);
            assertEquals(List.of(), ns("SELECT n FROM t" + where + " LIMIT 0"), where);
        }
    }

    @Test
    @DisplayName("ORDER BY com LIMIT é a fatia da ordenação completa, inclusive nos empates")
    void topKEqualsTheFullSortedSlice() {
        fill("t", 600);
        for (String order : List.of("valor", "valor DESC", "valor, n DESC", "status DESC, valor")) {
            for (String where : List.of("", " WHERE valor > 20")) {
                List<Long> all = ns("SELECT n FROM t" + where + " ORDER BY " + order);
                assertEquals(all.subList(0, 7), ns("SELECT n FROM t" + where + " ORDER BY " + order + " LIMIT 7"), order);
                assertEquals(all.subList(3, 13), ns("SELECT n FROM t" + where + " ORDER BY " + order + " LIMIT 10 START 3"), order);
                assertEquals(all, ns("SELECT n FROM t" + where + " ORDER BY " + order + " LIMIT 100000"), order);
            }
        }
    }

    @Test
    @DisplayName("LIMIT lê só o necessário: o número de documentos carregados não cresce com a tabela")
    void limitLoadsOnlyWhatItNeeds() {
        fill("t", 3000);
        run("CREATE INDEX t_status ON TABLE t COLUMNS status COLUMNAR");
        for (String sql : List.of("SELECT * FROM t LIMIT 10", "SELECT * FROM t WHERE status = 'b' LIMIT 10",
            "SELECT * FROM t WHERE valor > 90 LIMIT 5")) {
            long before = backend.gets.get();
            AxonValue rows = run(sql);
            long loaded = backend.gets.get() - before;
            assertEquals(sql.endsWith("LIMIT 5") ? 5 : 10, rows.asArray().size(), sql);
            assertTrue(loaded < 120, sql + " carregou " + loaded);
        }
    }

    // ------------------------------------------------------------------ GEO

    private static final double LON0 = -46.70;
    private static final double LAT0 = -23.60;

    /** Duas tabelas com os mesmos pontos: {@code g} com índice GEO e {@code h} sem índice (referência). */
    private void geoTables(int count, long seed) {
        run("DEFINE TABLE g SCHEMALESS; DEFINE TABLE h SCHEMALESS; CREATE INDEX g_pos ON TABLE g COLUMNS pos GEO");
        Random rnd = new Random(seed);
        for (int i = 0; i < count; i++) {
            double lon = LON0 + rnd.nextDouble() * 0.10;
            double lat = LAT0 + rnd.nextDouble() * 0.10;
            if (i % 50 == 0) {
                lon += 0.5;
            }
            String body = "{n: " + i + ", status: '" + (i % 3 == 0 ? "x" : "y") + "', pos: {type: 'Point', coordinates: ["
                + lon + ", " + lat + "]}}";
            run("CREATE g:" + i + " CONTENT " + body);
            run("CREATE h:" + i + " CONTENT " + body);
        }
    }

    private static String point(double lon, double lat) {
        return String.format(Locale.ROOT, "{type: 'Point', coordinates: [%.6f, %.6f]}", lon, lat);
    }

    private static List<Long> sorted(List<Long> in) {
        List<Long> out = new ArrayList<>(in);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Test
    @DisplayName("raio pelo índice GEO dá o mesmo resultado da varredura, inclusive na borda do raio")
    void radiusEqualsFullScan() {
        geoTables(2500, 11);
        Random rnd = new Random(3);
        for (int q = 0; q < 40; q++) {
            String c = point(LON0 + rnd.nextDouble() * 0.12, LAT0 + rnd.nextDouble() * 0.12);
            double meters = 50 + rnd.nextDouble() * 4000;
            for (String op : List.of("<=", "<")) {
                String where = " WHERE geo::distance(pos, " + c + ") " + op + " " + meters;
                assertEquals(sorted(ns("SELECT n FROM h" + where)), sorted(ns("SELECT n FROM g" + where)), where);
            }
        }
        // a borda: o raio é exatamente a distância até um ponto, que entra com <= e sai com <
        String c = point(LON0 + 0.05, LAT0 + 0.05);
        for (int n : new int[] {7, 123, 999, 2001}) {
            double d = run("SELECT geo::distance(pos, " + c + ") AS d FROM h WHERE n = " + n)
                .asArray().get(0).asObject().get("d").asDouble();
            for (String op : List.of("<=", "<")) {
                String where = " WHERE geo::distance(pos, " + c + ") " + op + " " + d;
                List<Long> viaIndex = sorted(ns("SELECT n FROM g" + where));
                assertEquals(sorted(ns("SELECT n FROM h" + where)), viaIndex, where);
                assertEquals(op.equals("<="), viaIndex.contains((long) n), where);
            }
        }
        assertEquals("GEO", strategy("SELECT n FROM g WHERE geo::distance(pos, " + c + ") <= 1000"));
    }

    @Test
    @DisplayName("raio com LIMIT, START e filtro extra para ao completar a janela e respeita o WHERE inteiro")
    void radiusWithWindow() {
        geoTables(2500, 12);
        String c = point(LON0 + 0.05, LAT0 + 0.05);
        String where = " WHERE geo::distance(pos, " + c + ") <= 3000";
        List<Long> all = sorted(ns("SELECT n FROM h" + where));
        assertTrue(all.size() > 60);
        List<Long> page = ns("SELECT n FROM g" + where + " LIMIT 20");
        assertEquals(20, page.size());
        assertTrue(all.containsAll(page));
        assertEquals(20, ns("SELECT n FROM g" + where + " LIMIT 20 START 10").size());
        String both = where + " AND status = 'x'";
        assertEquals(sorted(ns("SELECT n FROM h" + both)), sorted(ns("SELECT n FROM g" + both)));
        List<Long> limited = ns("SELECT n FROM g" + both + " LIMIT 5");
        assertEquals(5, limited.size());
        assertTrue(sorted(ns("SELECT n FROM h" + both)).containsAll(limited));
    }

    @Test
    @DisplayName("o raio não carrega documentos de candidatos fora do círculo")
    void radiusSkipsDocumentsOutsideTheCircle() {
        geoTables(2500, 13);
        String c = point(LON0 + 0.05, LAT0 + 0.05);
        String where = " WHERE geo::distance(pos, " + c + ") <= 800";
        int inside = ns("SELECT n FROM h" + where).size();
        long before = backend.gets.get();
        assertEquals(inside, ns("SELECT n FROM g" + where).size());
        long loaded = backend.gets.get() - before;
        assertTrue(loaded <= inside + 12, "carregou " + loaded + " para " + inside + " dentro do círculo");
    }

    @Test
    @DisplayName("vizinhos mais próximos: ORDER BY geo::distance LIMIT n igual à varredura, com START, filtro e centro distante")
    void nearestNeighboursEqualFullScan() {
        geoTables(2500, 14);
        Random rnd = new Random(5);
        List<String> centers = new ArrayList<>();
        for (int q = 0; q < 25; q++) {
            centers.add(point(LON0 + rnd.nextDouble() * 0.12, LAT0 + rnd.nextDouble() * 0.12));
        }
        centers.add(point(LON0 + 0.5, LAT0 + 0.5));
        centers.add(point(-40.0, -10.0));
        for (String c : centers) {
            String order = " ORDER BY geo::distance(pos, " + c + ")";
            assertEquals(ns("SELECT n FROM h" + order + " LIMIT 15"), ns("SELECT n FROM g" + order + " LIMIT 15"), c);
            assertEquals(ns("SELECT n FROM h" + order + " LIMIT 6 START 4"), ns("SELECT n FROM g" + order + " LIMIT 6 START 4"), c);
            String filtered = " WHERE status = 'x'" + order + " LIMIT 10";
            assertEquals(ns("SELECT n FROM h" + filtered), ns("SELECT n FROM g" + filtered), c);
        }
        String c = centers.get(0);
        String order = " ORDER BY geo::distance(pos, " + c + ") LIMIT 100000";
        assertEquals(ns("SELECT n FROM h" + order), ns("SELECT n FROM g" + order));
        assertEquals("GEO_KNN", strategy("SELECT n FROM g ORDER BY geo::distance(pos, " + c + ") LIMIT 20"));
        assertEquals("FULL_SCAN", strategy("SELECT n FROM h ORDER BY geo::distance(pos, " + c + ") LIMIT 20"));
        // por apelido, como no app de motoristas
        assertEquals(ns("SELECT n, geo::distance(pos, " + c + ") AS metros FROM h ORDER BY metros LIMIT 9"),
            ns("SELECT n, geo::distance(pos, " + c + ") AS metros FROM g ORDER BY metros LIMIT 9"));
    }

    @Test
    @DisplayName("vizinhos mais próximos não leem o raio inteiro: poucos documentos carregados")
    void nearestNeighboursLoadFewDocuments() {
        geoTables(2500, 15);
        String c = point(LON0 + 0.05, LAT0 + 0.05);
        long before = backend.gets.get();
        assertEquals(20, ns("SELECT n FROM g ORDER BY geo::distance(pos, " + c + ") LIMIT 20").size());
        assertTrue(backend.gets.get() - before < 60, "carregou " + (backend.gets.get() - before));
    }

    @Test
    @DisplayName("posição atualizada e registro apagado saem do índice com coordenadas")
    void updateAndDeleteKeepTheIndexExact() {
        geoTables(300, 17);
        String c = point(LON0 + 0.05, LAT0 + 0.05);
        String radius = " WHERE geo::distance(pos, " + c + ") <= 1500";
        for (int i = 0; i < 300; i += 3) {
            String moved = point(LON0 + 0.05 + (i % 7) * 0.004, LAT0 + 0.05 + (i % 5) * 0.004);
            run("UPDATE g:" + i + " SET pos = " + moved);
            run("UPDATE h:" + i + " SET pos = " + moved);
        }
        for (int i = 1; i < 300; i += 11) {
            run("DELETE g:" + i);
            run("DELETE h:" + i);
        }
        assertEquals(sorted(ns("SELECT n FROM h" + radius)), sorted(ns("SELECT n FROM g" + radius)));
        String order = " ORDER BY geo::distance(pos, " + c + ") LIMIT 25";
        assertEquals(ns("SELECT n FROM h" + order), ns("SELECT n FROM g" + order));
    }

    @Test
    @DisplayName("índice com polígono continua devolvendo as geometrias como candidatas do raio")
    void polygonsKeepTheOldCoverage() {
        run("DEFINE INDEX geo ON TABLE area COLUMNS shape GEO");
        run("CREATE area:r CONTENT {n: 1, shape: geometry::polygon([[[0, 0], [2, 0], [2, 2], [0, 2], [0, 0]]])}");
        run("CREATE area:p CONTENT {n: 2, shape: geometry::point(0.04, 0.04)}");
        run("CREATE area:q CONTENT {n: 3, shape: geometry::point(40, 40)}");
        List<Long> found = sorted(ns("SELECT n FROM area WHERE geo::distance(shape, geometry::point(0.05, 0.05)) < 5000"));
        assertTrue(found.contains(1L) && found.contains(2L) && !found.contains(3L), found.toString());
    }

    // ------------------------------------------------------------------ VH e auditoria

    private static String vector(Random rnd) {
        return String.format(Locale.ROOT, "[%.4f, %.4f, %.4f, %.4f]", rnd.nextDouble(), rnd.nextDouble(),
            rnd.nextDouble(), rnd.nextDouble());
    }

    @Test
    @DisplayName("grafo vetorial: escrever não varre o grafo VH inteiro, e a busca continua achando os vizinhos")
    void vectorIndexWritesDoNotScanTheGraph() {
        run("DEFINE TABLE v SCHEMALESS; CREATE INDEX vi ON TABLE v COLUMNS emb HNSW DIMENSION 4 DIST euclidean");
        Random rnd = new Random(21);
        List<double[]> vectors = new ArrayList<>();
        for (int i = 0; i < 220; i++) {
            double[] v = {rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble()};
            vectors.add(v);
            run("CREATE v:" + i + " CONTENT {n: " + i + ", emb: " + java.util.Arrays.toString(v) + "}");
        }
        for (int i = 0; i < 30; i++) {
            double[] v = {rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble()};
            vectors.set(i, v);
            run("UPDATE v:" + i + " SET emb = " + java.util.Arrays.toString(v));
        }
        for (int i = 200; i < 220; i++) {
            run("DELETE v:" + i);
        }
        long wholeGraph = backend.listings.stream().filter("VH|test|dev|v|vi|"::equals).count();
        assertTrue(wholeGraph <= 1, "varreduras do grafo inteiro: " + wholeGraph);

        double recall = 0;
        int queries = 25;
        for (int q = 0; q < queries; q++) {
            double[] target = {rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble()};
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                expected.add(i);
            }
            expected.sort(Comparator.comparingDouble(i -> dist(vectors.get(i), target)));
            List<Long> got = ns("SELECT n FROM v ORDER BY vector::distance::euclidean(emb, "
                + java.util.Arrays.toString(target) + ") LIMIT 5");
            assertEquals(5, got.size());
            assertTrue(got.stream().allMatch(n -> n < 200), "apagado voltou: " + got);
            for (int k = 0; k < 5; k++) {
                if (got.contains((long) expected.get(k))) {
                    recall++;
                }
            }
        }
        assertTrue(recall / (queries * 5) >= 0.9, "recall " + recall / (queries * 5));
    }

    private static double dist(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (a[i] - b[i]) * (a[i] - b[i]);
        }
        return Math.sqrt(s);
    }

    @Test
    @DisplayName("grafo vetorial antigo, sem contador de nível, é migrado na primeira escrita")
    void vectorGraphWithoutCounterIsMigrated() {
        run("DEFINE TABLE v SCHEMALESS; CREATE INDEX vi ON TABLE v COLUMNS emb HNSW DIMENSION 4 DIST euclidean");
        Random rnd = new Random(22);
        for (int i = 0; i < 40; i++) {
            run("CREATE v:" + i + " CONTENT {n: " + i + ", emb: " + vector(rnd) + "}");
        }
        assertTrue(backend.inner.get("VHT|test|dev|v|vi").isPresent());
        backend.inner.delete("VHT|test|dev|v|vi");
        run("CREATE v:100 CONTENT {n: 100, emb: [0.5, 0.5, 0.5, 0.5]}");
        assertTrue(backend.inner.get("VHT|test|dev|v|vi").isPresent());
        List<Long> got = ns("SELECT n FROM v ORDER BY vector::distance::euclidean(emb, [0.5, 0.5, 0.5, 0.5]) LIMIT 1");
        assertEquals(List.of(100L), got);
    }

    @Test
    @DisplayName("trilha de auditoria: o próximo id vem de um contador, sem listar o log a cada escrita")
    void auditIdsComeFromACounter() {
        ds.auditConfig(true, "auth", false, 65536);
        session.auth(AxonValue.object(java.util.Map.of("id", AxonValue.str("alice"))));
        for (int i = 0; i < 120; i++) {
            run("CREATE a CONTENT {n: " + i + "}");
        }
        long listings = backend.listings.stream().filter(p -> p.startsWith("!audit_op|")).count();
        assertTrue(listings <= 1, "listagens do log: " + listings);
        assertEquals(120, backend.inner.keysWithPrefix("!audit_op|test|dev|").size());

        // outro processo sobre o mesmo armazenamento continua a numeração pelo contador persistido
        Datastore second = new Datastore(backend);
        second.createDatabase("test", "dev");
        second.auditConfig(true, "auth", false, 65536);
        backend.listings.clear();
        for (int i = 0; i < 10; i++) {
            second.execute("CREATE a CONTENT {n: 1}", session, null);
        }
        assertEquals(0, backend.listings.stream().filter(p -> p.startsWith("!audit_op|")).count());
        assertEquals(130, backend.inner.keysWithPrefix("!audit_op|test|dev|").size());

        // log antigo, sem contador: uma varredura e a numeração segue sem colisão
        backend.inner.delete("!audit_seq|test|dev");
        Datastore legacy = new Datastore(backend);
        legacy.createDatabase("test", "dev");
        legacy.auditConfig(true, "auth", false, 65536);
        backend.listings.clear();
        for (int i = 0; i < 5; i++) {
            legacy.execute("CREATE a CONTENT {n: 2}", session, null);
        }
        assertEquals(1, backend.listings.stream().filter(p -> p.startsWith("!audit_op|")).count());
        assertEquals(135, backend.inner.keysWithPrefix("!audit_op|test|dev|").size());
        assertFalse(backend.inner.keysWithPrefix("!audit_op|test|dev|").isEmpty());
    }
}
