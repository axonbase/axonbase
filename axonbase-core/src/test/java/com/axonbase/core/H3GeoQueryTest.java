package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.axonbase.core.GeoTestData.INDEX;
import static com.axonbase.core.GeoTestData.insert;
import static com.axonbase.core.GeoTestData.ns;
import static com.axonbase.core.GeoTestData.plain;
import static com.axonbase.core.GeoTestData.point;
import static com.axonbase.core.GeoTestData.run;
import static com.axonbase.core.GeoTestData.sortedNs;
import static com.axonbase.core.GeoTestData.strategy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Consultas GEO sobre H3 iguais à força bruta")
class H3GeoQueryTest {

    /** Quando o índice é criado em relação aos dados. */
    enum Moment {
        BEFORE_DATA, AFTER_DATA, AFTER_DATA_IN_BACKGROUND
    }

    private static Datastore tables(Datastore ds, Moment moment, List<double[]> points) throws InterruptedException {
        ds.createDatabase("test", "dev");
        if (moment == Moment.AFTER_DATA_IN_BACKGROUND) {
            ds.findDatabase("test", "dev").indexInlineRows(0);
        }
        run(ds, "DEFINE TABLE g SCHEMALESS; DEFINE TABLE h SCHEMALESS");
        if (moment == Moment.BEFORE_DATA) {
            run(ds, INDEX);
        }
        for (int i = 0; i < points.size(); i++) {
            insert(ds, i, points.get(i));
        }
        if (moment != Moment.BEFORE_DATA) {
            run(ds, INDEX);
            assertTrue(IndexBuilder.awaitAll(60_000), "construção não terminou");
        }
        return ds;
    }

    private static List<double[]> centers(List<double[]> points) {
        Random rnd = new Random(99);
        List<double[]> out = new ArrayList<>(List.of(
            new double[] {0, 90}, new double[] {77, -90}, new double[] {180, -16.8}, new double[] {-180, -16.8},
            new double[] {179.95, -16.85}, new double[] {-46.6333, -23.5505}, new double[] {10, 10}));
        for (int i = 0; i < 18; i++) {
            out.add(points.get(rnd.nextInt(points.size())));
        }
        out.addAll(GeoTestData.cellEdges(-46.6333, -23.5505).subList(0, 10));
        return out;
    }

    /** Raio, vizinhos mais próximos e polígono: {@code g} (índice) igual a {@code h} (varredura). */
    private static void assertSameAsBruteForce(Datastore ds, List<double[]> points) {
        Random rnd = new Random(7);
        for (double[] center : centers(points)) {
            String c = point(center[0], center[1]);
            for (double meters : new double[] {1, 120, 2_500, 40_000, 900_000, 12_000_000, 25_000_000}) {
                String where = " WHERE geo::distance(pos, " + c + ") <= " + plain(meters * (0.7 + rnd.nextDouble()));
                assertEquals(sortedNs(ds, "SELECT n FROM h" + where), sortedNs(ds, "SELECT n FROM g" + where), where);
            }
            // a borda: o raio é exatamente a distância até um ponto, que entra com <= e sai com <
            long target = rnd.nextInt(points.size());
            AxonValue found = run(ds, "SELECT geo::distance(pos, " + c + ") AS d FROM h WHERE n = " + target);
            if (found.asArray().isEmpty()) {
                continue;
            }
            double exact = found.asArray().get(0).asObject().get("d").asDouble();
            for (String op : List.of("<=", "<")) {
                String where = " WHERE geo::distance(pos, " + c + ") " + op + " " + plain(exact);
                assertEquals(sortedNs(ds, "SELECT n FROM h" + where), sortedNs(ds, "SELECT n FROM g" + where), where);
            }
            String order = " ORDER BY geo::distance(pos, " + c + ")";
            assertEquals(ns(ds, "SELECT n FROM h" + order + " LIMIT 25"),
                ns(ds, "SELECT n FROM g" + order + " LIMIT 25"), c);
            assertEquals(ns(ds, "SELECT n FROM h" + order + " LIMIT 7 START 5"),
                ns(ds, "SELECT n FROM g" + order + " LIMIT 7 START 5"), c);
            String filtered = " WHERE status = 'x'" + order + " LIMIT 12";
            assertEquals(ns(ds, "SELECT n FROM h" + filtered), ns(ds, "SELECT n FROM g" + filtered), c);
        }
        for (String ring : List.of(
            "[[-46.66,-23.58],[-46.62,-23.58],[-46.62,-23.54],[-46.66,-23.54],[-46.66,-23.58]]",
            "[[-46.70,-23.60],[-46.58,-23.60],[-46.64,-23.55],[-46.58,-23.48],[-46.70,-23.48],[-46.70,-23.60]]",
            "[[179.9,-17],[180,-17],[180,-16.6],[179.9,-16.6],[179.9,-17]]",
            "[[-180,-17],[-179.9,-17],[-179.9,-16.6],[-180,-16.6],[-180,-17]]",
            // além de 180: a conta é plana, então só as longitudes escritas assim entram, como na varredura
            "[[179.9,-17],[180.2,-17],[180.2,-16.6],[179.9,-16.6],[179.9,-17]]",
            "[[-180,89.85],[180,89.85],[180,90],[-180,90],[-180,89.85]]",
            "[[-180,-90],[180,-90],[180,-89.9],[-180,-89.9],[-180,-90]]",
            "[[-179,-80],[179,-80],[179,80],[-179,80],[-179,-80]]",
            "[[10,10],[11,10],[11,11],[10,10]]")) {
            String where = " WHERE geo::contains({type: 'Polygon', coordinates: [" + ring + "]}, pos)";
            assertEquals(sortedNs(ds, "SELECT n FROM h" + where), sortedNs(ds, "SELECT n FROM g" + where), where);
        }
        // polígono cujos vértices são vértices de uma célula: os pontos de borda
        List<double[]> edge = GeoTestData.cellEdges(-46.6333, -23.5505);
        StringBuilder ring = new StringBuilder();
        for (double[] v : edge.subList(1, 7)) {
            ring.append('[').append(plain(v[0])).append(',').append(plain(v[1])).append("],");
        }
        ring.append('[').append(plain(edge.get(1)[0])).append(',').append(plain(edge.get(1)[1])).append(']');
        String where = " WHERE geo::contains({type: 'Polygon', coordinates: [[" + ring + "]]}, pos)";
        assertEquals(sortedNs(ds, "SELECT n FROM h" + where), sortedNs(ds, "SELECT n FROM g" + where), where);
    }

    private static void assertIndexed(Datastore ds) {
        String c = point(-46.6333, -23.5505);
        assertEquals("GEO", strategy(ds, "SELECT n FROM g WHERE geo::distance(pos, " + c + ") <= 500"));
        assertEquals("GEO_KNN", strategy(ds, "SELECT n FROM g ORDER BY geo::distance(pos, " + c + ") LIMIT 3"));
        assertEquals("FULL_SCAN", strategy(ds, "SELECT n FROM h WHERE geo::distance(pos, " + c + ") <= 500"));
    }

    @ParameterizedTest
    @EnumSource(Moment.class)
    @DisplayName("índice criado antes dos dados, depois e em segundo plano")
    void differential(Moment moment) throws InterruptedException {
        List<double[]> points = GeoTestData.points(1);
        Datastore ds = tables(new Datastore(new MemoryBackend()), moment, points);
        assertIndexed(ds);
        assertSameAsBruteForce(ds, points);
    }

    @Test
    @DisplayName("continua exato depois de mover e apagar registros")
    void afterUpdatesAndDeletes() throws InterruptedException {
        List<double[]> points = GeoTestData.points(2);
        Datastore ds = tables(new Datastore(new MemoryBackend()), Moment.BEFORE_DATA, points);
        Random rnd = new Random(3);
        for (int i = 0; i < points.size(); i += 3) {
            double[] to = points.get(rnd.nextInt(points.size()));
            String move = " SET pos = " + point(to[0], to[1]);
            run(ds, "UPDATE g:" + i + move);
            run(ds, "UPDATE h:" + i + move);
        }
        for (int i = 1; i < points.size(); i += 7) {
            run(ds, "DELETE g:" + i);
            run(ds, "DELETE h:" + i);
        }
        assertSameAsBruteForce(ds, points);
    }

    @Test
    @DisplayName("escritas dentro de uma transação são vistas pela consulta indexada")
    void insideTransaction() throws InterruptedException {
        List<double[]> points = GeoTestData.points(4).subList(0, 200);
        Datastore ds = tables(new Datastore(new MemoryBackend()), Moment.BEFORE_DATA, points);
        String c = point(1.5, 1.5);
        AxonValue found = run(ds, "BEGIN; CREATE g:9000 CONTENT {n: 9000, pos: " + c + "};"
            + " LET $near = (SELECT VALUE n FROM g WHERE geo::distance(pos, " + c + ") <= 10); COMMIT; RETURN $near");
        assertEquals(9000L, found.asArray().get(0).asLong());
    }

    @Test
    @DisplayName("mais próximos com várias condições ligadas por AND aplicam todas antes do LIMIT")
    void nearestWithSeveralConditions() throws InterruptedException {
        List<double[]> points = GeoTestData.points(8).subList(0, 700);
        Datastore ds = tables(new Datastore(new MemoryBackend()), Moment.BEFORE_DATA, points);
        for (double[] center : List.of(points.get(0), points.get(350), new double[] {-46.64, -23.55})) {
            String query = " WHERE n > 300 AND status = 'x' ORDER BY geo::distance(pos, "
                + point(center[0], center[1]) + ") LIMIT 8";
            assertEquals(ns(ds, "SELECT n FROM h" + query), ns(ds, "SELECT n FROM g" + query), query);
        }
    }

    @Test
    @DisplayName("raio ou centro que dependem da linha não usam o índice")
    void rowDependentRadiusOrCenter() {
        Datastore ds = new Datastore(new MemoryBackend());
        ds.createDatabase("test", "dev");
        run(ds, "DEFINE TABLE g SCHEMALESS; DEFINE TABLE h SCHEMALESS; " + INDEX);
        for (String table : List.of("g", "h")) {
            run(ds, "CREATE " + table + ":1 CONTENT {n: 1, alcance: 5000, destino: 0, pos: " + point(0, 0.02) + "}");
            run(ds, "CREATE " + table + ":2 CONTENT {n: 2, alcance: 10, destino: 0, pos: " + point(0, 0.002) + "}");
        }
        for (String where : List.of(
            " WHERE geo::distance(pos, " + point(0, 0) + ") <= math::max([alcance, 500])",
            " WHERE geo::distance(pos, geometry::point(destino, 0)) <= 500")) {
            assertEquals("FULL_SCAN", strategy(ds, "SELECT n FROM g" + where), where);
            assertEquals(sortedNs(ds, "SELECT n FROM h" + where), sortedNs(ds, "SELECT n FROM g" + where), where);
        }
    }

    @Test
    @DisplayName("mais próximos dentro de uma transação varrem a tabela, sem segurar faixas enormes do índice")
    void nearestInsideTransactionScans() throws InterruptedException {
        List<double[]> points = GeoTestData.points(9).subList(0, 200);
        Datastore ds = tables(new Datastore(new MemoryBackend()), Moment.BEFORE_DATA, points);
        String order = " ORDER BY geo::distance(pos, " + point(-46.64, -23.55) + ") LIMIT 5";
        Session s = GeoTestData.session();
        ds.execute("BEGIN", s, null);
        assertEquals("FULL_SCAN",
            ds.execute("EXPLAIN SELECT n FROM g" + order, s, null).asObject().get("strategy").asString());
        assertEquals(ds.execute("SELECT VALUE n FROM h" + order, s, null),
            ds.execute("SELECT VALUE n FROM g" + order, s, null));
        ds.execute("CANCEL", s, null);
        assertEquals("GEO_KNN", strategy(ds, "SELECT n FROM g" + order));
    }

    @Test
    @DisplayName("coordenadas no formato marcado do dump de exportação entram no índice")
    void taggedDecimalCoordinates() {
        Datastore ds = new Datastore(new MemoryBackend());
        ds.createDatabase("test", "dev");
        run(ds, "DEFINE TABLE g SCHEMALESS; DEFINE TABLE h SCHEMALESS; " + INDEX);
        String pos = "{type: 'Point', coordinates: [{\"$decimal\": \"-46.5\"}, {\"$decimal\": \"-23.5\"}]}";
        run(ds, "CREATE g:1 CONTENT {n: 1, pos: " + pos + "}");
        run(ds, "CREATE h:1 CONTENT {n: 1, pos: " + pos + "}");
        String where = " WHERE geo::distance(pos, " + point(-46.5, -23.5) + ") <= 100";
        assertEquals(List.of(1L), ns(ds, "SELECT n FROM h" + where));
        assertEquals(List.of(1L), ns(ds, "SELECT n FROM g" + where));
        assertEquals(List.of(1L), ns(ds, "SELECT n FROM g ORDER BY geo::distance(pos, "
            + point(-46.5, -23.5) + ") LIMIT 1"));
    }

    @Test
    @DisplayName("registro com chave contendo barra vertical e vírgula é achado e apagado")
    void awkwardRecordKeys() {
        Datastore ds = new Datastore(new MemoryBackend());
        ds.createDatabase("test", "dev");
        run(ds, INDEX);
        String c = point(3, 4);
        run(ds, "CREATE g:⟨a|b,c⟩ CONTENT {n: 1, pos: " + c + "}");
        String where = " WHERE geo::distance(pos, " + c + ") <= 5";
        assertEquals(List.of(1L), ns(ds, "SELECT n FROM g" + where));
        assertEquals(List.of(1L), ns(ds, "SELECT n FROM g ORDER BY geo::distance(pos, " + c + ") LIMIT 2"));
        run(ds, "DELETE g:⟨a|b,c⟩");
        assertTrue(ns(ds, "SELECT n FROM g" + where).isEmpty());
    }

    @Test
    @DisplayName("índice composto só atende consultas pela primeira coluna")
    void onlyTheFirstColumnIsIndexed() {
        Datastore ds = new Datastore(new MemoryBackend());
        ds.createDatabase("test", "dev");
        run(ds, "DEFINE INDEX two ON TABLE g COLUMNS pos, other GEO");
        run(ds, "CREATE g:1 CONTENT {n: 1, pos: " + point(0, 0) + ", other: " + point(50, 50) + "}");
        String where = " WHERE geo::distance(other, " + point(50, 50) + ") <= 5";
        assertEquals("FULL_SCAN", strategy(ds, "SELECT n FROM g" + where));
        assertEquals(List.of(1L), ns(ds, "SELECT n FROM g" + where));
    }

    @Test
    @DisplayName("reiniciar sobre RocksDB mantém o índice pronto e exato, sem reconstruir")
    void survivesRestart(@TempDir Path dir) throws InterruptedException {
        List<double[]> points = GeoTestData.points(5);
        String path = dir.resolve("db").toString();
        try (RocksDbBackend backend = new RocksDbBackend(path)) {
            tables(new Datastore(backend), Moment.AFTER_DATA_IN_BACKGROUND, points);
        }
        try (RocksDbBackend backend = new RocksDbBackend(path)) {
            IndexBuilder.chunkHook(() -> {
                throw new AssertionError("não deveria reconstruir");
            });
            try {
                Datastore ds = new Datastore(backend);
                assertIndexed(ds);
                assertSameAsBruteForce(ds, points);
            } finally {
                IndexBuilder.chunkHook(null);
            }
        }
    }

    @Test
    @DisplayName("backup lógico, exportação e backup físico restauram um índice GEO exato")
    void survivesBackups(@TempDir Path dir) throws Exception {
        List<double[]> points = GeoTestData.points(6);
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("source").toString())) {
            Datastore source = tables(new Datastore(backend), Moment.BEFORE_DATA, points);

            Datastore logical = new Datastore(new MemoryBackend());
            logical.restoreCluster(source.backupCluster());
            assertTrue(IndexBuilder.awaitAll(60_000));
            assertIndexed(logical);
            assertSameAsBruteForce(logical, points);

            Datastore imported = new Datastore(new MemoryBackend());
            imported.createDatabase("test", "dev");
            imported.importDatabase("test", "dev", source.exportDatabase("test", "dev"));
            assertTrue(IndexBuilder.awaitAll(60_000));
            assertIndexed(imported);
            assertSameAsBruteForce(imported, points);

            int id;
            try (PhysicalBackups backups = PhysicalBackups.open(dir.resolve("backups"))) {
                id = backups.create(backend).id();
            }
            PhysicalBackups.restore(dir.resolve("backups"), id, dir.resolve("restored"));
            try (RocksDbBackend restored = new RocksDbBackend(dir.resolve("restored").toString())) {
                Datastore physical = new Datastore(restored);
                assertTrue(IndexBuilder.awaitAll(60_000));
                assertIndexed(physical);
                assertSameAsBruteForce(physical, points);
            }
        }
    }
}
