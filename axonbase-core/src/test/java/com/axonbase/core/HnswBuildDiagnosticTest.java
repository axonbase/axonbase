package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnóstico do recall do índice vetorial no cenário de {@code OnlineIndexBuildTest.vectorEquivalence}. Não
 * reprova por recall: mede e imprime uma linha {@code HNSWDIAG} por execução, separando grafo desconectado
 * (mais de um componente no nível 0) de grafo conectado mas mal navegável.
 *
 * <p>Uso: {@code -Daxon.hnsw.diag=true -Daxon.hnsw.runs=20 -Dtest=HnswBuildDiagnosticTest}.</p>
 */
@DisplayName("Diagnóstico do grafo vetorial (opcional)")
@EnabledIfSystemProperty(named = "axon.hnsw.diag", matches = "true")
class HnswBuildDiagnosticTest {

    private static final int ROWS = 1500;
    private static final String DEFINE =
        "DEFINE INDEX vx ON TABLE t COLUMNS v HNSW DIMENSION 2 DIST euclidean M 12 EFC 64 EFS 256";

    /** Como o índice é montado em cada variante. */
    private enum Variant {
        /** Índice definido antes da carga, linhas em ordem numérica (a referência do teste). */
        INLINE,
        /** Construção em segundo plano sem escrita concorrente: indexa na ordem textual das chaves. */
        BUILD_ONLY,
        /** O cenário do teste: construção em segundo plano com criação e remoção concorrentes. */
        BUILD_WITH_WRITES
    }

    @AfterEach
    void reset() {
        IndexBuilder.chunkHook(null);
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static double[] point(int i) {
        return new double[] {(i % 40) / 39.0, (i / 40 % 40) / 39.0};
    }

    private static void create(Datastore ds, Session s, int id, int shape, Map<Integer, double[]> live) {
        double[] p = point(shape);
        ds.execute("CREATE t:" + id + " CONTENT {v: [" + p[0] + ", " + p[1] + "], n: " + id + "}", s, null);
        live.put(id, p);
    }

    private static void concurrentWrites(Datastore ds, Session s, Map<Integer, double[]> live) {
        for (int i = 0; i < 100; i++) {
            create(ds, s, ROWS + i, ROWS + 7 * i, live);
            ds.execute("DELETE t:" + (i * 3), s, null);
            live.remove(i * 3);
        }
    }

    private static Datastore build(Variant variant, Map<Integer, double[]> live) throws Exception {
        Datastore ds = new Datastore(new MemoryBackend());
        ds.createDatabase("test", "dev");
        Session s = session();
        ds.execute("DEFINE TABLE t SCHEMALESS", s, null);
        if (variant == Variant.INLINE) {
            ds.execute(DEFINE, s, null);
        } else {
            ds.findDatabase("test", "dev").indexInlineRows(0);
        }
        for (int i = 0; i < ROWS; i++) {
            create(ds, s, i, i, live);
        }
        if (variant == Variant.INLINE) {
            concurrentWrites(ds, s, live);
            return ds;
        }
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        IndexBuilder.chunkHook(() -> {
            started.countDown();
            if (chunks.incrementAndGet() == 2) {
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ds.execute(DEFINE, s, null);
        assertTrue(started.await(30, TimeUnit.SECONDS));
        if (variant == Variant.BUILD_WITH_WRITES) {
            concurrentWrites(ds, s, live);
        }
        go.countDown();
        assertTrue(IndexBuilder.awaitAll(60_000));
        IndexBuilder.chunkHook(null);
        return ds;
    }

    /** Fração dos 10 mais próximos achada pelo índice; empates de distância contam como acerto. */
    private static double recall(Datastore ds, Map<Integer, double[]> live, double x, double y) {
        List<Double> exact = new ArrayList<>(live.size());
        for (double[] p : live.values()) {
            exact.add(Math.hypot(p[0] - x, p[1] - y));
        }
        Collections.sort(exact);
        double limit = exact.get(9) + 1e-9;
        AxonValue rows = ds.execute("SELECT VALUE n FROM t ORDER BY vector::distance::euclidean(v, ["
            + x + ", " + y + "]) LIMIT 10", session(), null);
        int hit = 0;
        for (AxonValue row : rows.asArray()) {
            double[] p = live.get((int) row.asLong());
            if (p != null && Math.hypot(p[0] - x, p[1] - y) <= limit) {
                hit++;
            }
        }
        return hit / 10.0;
    }

    private static String find(Map<String, String> parent, String node) {
        String current = node;
        while (!parent.get(current).equals(current)) {
            parent.put(current, parent.get(parent.get(current)));
            current = parent.get(current);
        }
        return current;
    }

    /** Presença por nível, componentes do nível 0 e arestas que apontam para nó sem presença. */
    private static String graphShape(Datastore ds) {
        KvBackend kv = ds.findDatabase("test", "dev").records();
        String prefix = "VH|test|dev|t|vx|";
        Map<Integer, Integer> perLevel = new TreeMap<>();
        Map<String, String> parent = new HashMap<>();
        List<String[]> edges = new ArrayList<>();
        for (String key : kv.keysWithPrefix(prefix)) {
            String[] parts = key.substring(prefix.length()).split("\\|");
            int level = Integer.parseInt(parts[0]);
            if (parts[1].equals("@entry")) {
                continue;
            }
            if (parts[1].equals("@N")) {
                perLevel.merge(level, 1, Integer::sum);
                if (level == 0) {
                    parent.put(parts[2], parts[2]);
                }
            } else if (level == 0) {
                edges.add(new String[] {parts[1], parts[2]});
            }
        }
        int dangling = 0;
        for (String[] edge : edges) {
            if (!parent.containsKey(edge[0]) || !parent.containsKey(edge[1])) {
                dangling++;
                continue;
            }
            parent.put(find(parent, edge[0]), find(parent, edge[1]));
        }
        Map<String, Integer> components = new LinkedHashMap<>();
        for (String node : new ArrayList<>(parent.keySet())) {
            components.merge(find(parent, node), 1, Integer::sum);
        }
        List<Integer> sizes = new ArrayList<>(components.values());
        sizes.sort(Collections.reverseOrder());
        return "components=" + sizes.size() + " sizes=" + sizes.subList(0, Math.min(5, sizes.size()))
            + " dangling=" + dangling + " avgDegree=" + (parent.isEmpty() ? 0 : edges.size() / parent.size())
            + " levels=" + perLevel;
    }

    @Test
    void measureRecallAndGraphShape() throws Exception {
        int runs = Integer.getInteger("axon.hnsw.runs", 20);
        for (Variant variant : Variant.values()) {
            for (int run = 0; run < runs; run++) {
                Map<Integer, double[]> live = new TreeMap<>();
                Datastore ds = build(variant, live);
                double centre = recall(ds, live, 0.5, 0.5);
                double sum = 0;
                double worst = 1;
                int low = 0;
                for (int gx = 0; gx < 10; gx++) {
                    for (int gy = 0; gy < 10; gy++) {
                        double value = recall(ds, live, (gx + 0.5) / 10, (gy + 0.5) / 10);
                        sum += value;
                        worst = Math.min(worst, value);
                        low += value < 0.9 ? 1 : 0;
                    }
                }
                System.out.println("HNSWDIAG variant=" + variant + " run=" + run + " centre=" + centre + " mean="
                    + sum / 100 + " worst=" + worst + " queriesBelow09=" + low + "/100 " + graphShape(ds));
            }
        }
    }
}
