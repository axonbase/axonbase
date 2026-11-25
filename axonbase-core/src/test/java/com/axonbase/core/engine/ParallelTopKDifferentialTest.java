package com.axonbase.core.engine;

import com.axonbase.core.Session;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ORDER BY/LIMIT e filtros sem índice em paralelo: mesmo resultado de ordenar tudo e cortar")
class ParallelTopKDifferentialTest {

    private static final int ROWS = ParallelScan.SEQUENTIAL_HEAD + 7_000;

    private final Datastore ds = Datastore.memory();
    private final Session session = Session.create();

    ParallelTopKDifferentialTest() {
        ds.createDatabase("test", "dev");
        session.namespace("test");
        session.database("dev");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    /** Valores variados: nulos, ausentes, repetidos, negativos, decimais, texto, texto numérico e booleanos. */
    private static String mixed(Random r) {
        return switch (r.nextInt(10)) {
            case 0 -> "null";
            case 1 -> null;
            case 2 -> String.valueOf(r.nextInt(20) - 10);
            case 3 -> String.valueOf(r.nextInt(2000) - 1000);
            case 4 -> String.format(java.util.Locale.ROOT, "%.3f", r.nextDouble() * 100 - 50);
            case 5 -> "'s" + r.nextInt(50) + "'";
            case 6 -> "'" + r.nextInt(30) + "'";
            case 7 -> r.nextBoolean() ? "true" : "false";
            case 8 -> String.valueOf(r.nextInt(5));
            default -> String.valueOf(r.nextInt(100000));
        };
    }

    private void fill() {
        Random r = new Random(42);
        for (int i = 0; i < ROWS; i++) {
            StringBuilder b = new StringBuilder("{n: " + i + ", c: " + (i % 9) + ", d: " + r.nextInt(300));
            String a = mixed(r);
            if (a != null) {
                b.append(", a: ").append(a);
            }
            String x = mixed(r);
            if (x != null) {
                b.append(", b: ").append(x);
            }
            run("CREATE t:" + i + " CONTENT " + b + "}");
        }
    }

    /** As linhas como texto: AxonValue não compara por conteúdo dentro de listas. */
    private static List<String> rows(AxonValue v) {
        return v.asArray().stream().map(AxonValue::toString).toList();
    }

    private static List<String> slice(List<String> all, int start, int limit) {
        int from = Math.min(start, all.size());
        return new ArrayList<>(all.subList(from, Math.min(all.size(), from + limit)));
    }

    @Test
    @DisplayName("ORDER BY com LIMIT e START, uma e várias chaves, ASC e DESC, tipos mistos")
    void orderByMatchesFullSort() {
        fill();
        long before = ParallelScan.BATCHES.get();
        String[] orders = {"a", "a DESC", "b", "b DESC", "c, a", "c DESC, b DESC", "d DESC", "d, n DESC", "a, b", "b DESC, a",
            "c", "c DESC"};
        int[][] windows = {{1, 0}, {10, 0}, {10, 7}, {1, 500}, {100, 0}, {3, 0}, {50, 25}, {2000, 0}, {5, 10_000}, {20, 0}};
        int checked = 0;
        for (String o : orders) {
            List<String> all = rows(run("SELECT * FROM t ORDER BY " + o));
            assertEquals(ROWS, all.size());
            for (int[] w : windows) {
                String sql = "SELECT * FROM t ORDER BY " + o + " LIMIT " + w[0] + (w[1] > 0 ? " START " + w[1] : "");
                assertEquals(slice(all, w[1], w[0]), rows(run(sql)), sql);
                checked++;
            }
        }
        assertTrue(checked >= 100);
        assertTrue(ParallelScan.BATCHES.get() > before, "a varredura paralela não foi usada");
    }

    @Test
    @DisplayName("WHERE sem índice com LIMIT/START, com e sem ORDER BY, inclusive sem nenhum casamento")
    void filterWindowMatchesFullScan() {
        fill();
        String[] conds = {"d > 250", "d > 299", "d > 9999", "a > 5", "b > 1000", "b < 0", "c = 3 AND d < 100",
            "a > 0 OR b > 0", "d >= 0"};
        int[][] windows = {{1, 0}, {10, 0}, {10, 7}, {500, 0}, {20, 3000}, {50, 100_000}};
        for (String c : conds) {
            List<String> all = rows(run("SELECT * FROM t WHERE " + c));
            List<String> sorted = rows(run("SELECT * FROM t WHERE " + c + " ORDER BY a DESC, n"));
            for (int[] w : windows) {
                String tail = " LIMIT " + w[0] + (w[1] > 0 ? " START " + w[1] : "");
                String sql = "SELECT * FROM t WHERE " + c + tail;
                assertEquals(slice(all, w[1], w[0]), rows(run(sql)), sql);
                String sql2 = "SELECT * FROM t WHERE " + c + " ORDER BY a DESC, n" + tail;
                assertEquals(slice(sorted, w[1], w[0]), rows(run(sql2)), sql2);
            }
        }
    }

    @Test
    @DisplayName("LIMIT 0 e tabela vazia")
    void edges() {
        run("DEFINE TABLE e SCHEMALESS");
        assertEquals(0, rows(run("SELECT * FROM e ORDER BY a LIMIT 5")).size());
        fill();
        assertEquals(0, rows(run("SELECT * FROM t ORDER BY a LIMIT 0")).size());
        assertEquals(0, rows(run("SELECT * FROM t WHERE d > 1 LIMIT 0")).size());
    }
}
