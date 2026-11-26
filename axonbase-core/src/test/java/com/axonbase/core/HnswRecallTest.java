package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HnswRecallTest {

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
    void recallDoHnswMulticamadaContraForcaBruta() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE INDEX vec ON TABLE point COLUMNS v HNSW DIMENSION 2 DIST euclidean M 12 EFC 64 EFS 256", s, null);

        int n = 200;
        for (int i = 0; i < n; i++) {
            double x = (i % 20) / 19.0;
            double y = (i / 20) / 9.0;
            ds.execute("CREATE point:" + i + " CONTENT { v: [" + x + ", " + y + "] }", s, null);
        }

        double[] query = {0.5, 0.5};
        AxonValue rows = ds.execute("SELECT VALUE id FROM point ORDER BY vector::distance::euclidean(v, [0.5, 0.5]) LIMIT 10", s, null);
        List<String> returned = new ArrayList<>();
        for (AxonValue r : rows.asArray()) {
            returned.add(idText(r));
        }

        List<String> exact = bruteForce(n, query, 10);
        int hit = 0;
        List<String> stripped = new ArrayList<>();
        for (String id : returned) {
            String key = id.startsWith("point:") ? id.substring("point:".length()) : id;
            stripped.add(key);
            if (exact.contains(key)) {
                hit++;
            }
        }
        double recall = (double) hit / exact.size();
        assertTrue(recall >= 0.9, "recall %s%%, top=%s, exact=%s".formatted(recall * 100, stripped, exact));
    }

    @Test
    void remocaoDeVetorRefleteNaBusca() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("CREATE point:0 CONTENT {v: [0.0, 0.0]}", s, null);
        ds.execute("DEFINE INDEX vec ON TABLE point COLUMNS v HNSW DIMENSION 2 M 8 EFC 32 EFS 256", s, null);
        ds.execute("CREATE point:1 CONTENT {v: [1.0, 1.0]}", s, null);

        AxonValue before = ds.execute("SELECT VALUE id FROM point ORDER BY vector::distance::euclidean(v, [0.1, 0.1]) LIMIT 1", s, null);
        assertEquals("point:0", idText(before.asArray().get(0)));

        ds.execute("DELETE point:0", s, null);
        AxonValue after = ds.execute("SELECT VALUE id FROM point ORDER BY vector::distance::euclidean(v, [0.1, 0.1]) LIMIT 1", s, null);
        assertEquals("point:1", idText(after.asArray().get(0)));
    }

    @Test
    @DisplayName("linhas inseridas na ordem textual das chaves, como na construção em segundo plano, mantêm o recall")
    void recallSurvivesInsertionInTextualKeyOrder() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE INDEX vec ON TABLE grid COLUMNS v HNSW DIMENSION 2 DIST euclidean M 12 EFC 64 EFS 256",
            s, null);
        int n = 1500;
        // 0, 1, 10, 100, 1000, 1001...: cada faixa cai longe da anterior na grade
        List<Integer> order = java.util.stream.IntStream.range(0, n).boxed()
            .sorted(java.util.Comparator.comparing(String::valueOf)).toList();
        for (int i : order) {
            double[] v = gridPoint(i);
            ds.execute("CREATE grid:" + i + " CONTENT { n: " + i + ", v: [" + v[0] + ", " + v[1] + "] }", s, null);
        }

        List<String> bad = new ArrayList<>();
        for (int gx = 0; gx < 10; gx++) {
            for (int gy = 0; gy < 10; gy++) {
                double[] query = {(gx + 0.5) / 10, (gy + 0.5) / 10};
                double tenth = java.util.stream.IntStream.range(0, n)
                    .mapToDouble(i -> dist(gridPoint(i), query)).sorted().skip(9).findFirst().orElseThrow();
                AxonValue rows = ds.execute("SELECT VALUE n FROM grid ORDER BY vector::distance::euclidean(v, ["
                    + query[0] + ", " + query[1] + "]) LIMIT 10", s, null);
                // empates de distância contam como acerto
                long hit = rows.asArray().stream()
                    .filter(r -> dist(gridPoint((int) r.asLong()), query) <= tenth + 1e-9).count();
                if (hit < 9) {
                    bad.add("[" + query[0] + ", " + query[1] + "]=" + hit);
                }
            }
        }
        assertTrue(bad.isEmpty(), "consultas com menos de 9 dos 10 vizinhos: " + bad);
    }

    private double[] gridPoint(int i) {
        return new double[]{(i % 40) / 39.0, (i / 40) / 39.0};
    }

    private String idText(AxonValue v) {
        return v.isRecordId() ? v.asRecordId().toString() : v.asString();
    }

    private List<String> bruteForce(int n, double[] query, int k) {
        return java.util.stream.IntStream.range(0, n)
            .boxed()
            .sorted((a, b) -> Double.compare(dist(vectorAt(a), query), dist(vectorAt(b), query)))
            .limit(k)
            .map(String::valueOf)
            .toList();
    }

    private double[] vectorAt(int i) {
        return new double[]{(i % 20) / 19.0, (i / 20) / 9.0};
    }

    private double dist(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }
}