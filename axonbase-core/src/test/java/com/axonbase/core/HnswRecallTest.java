package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import com.axonbase.value.AxonJson;
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