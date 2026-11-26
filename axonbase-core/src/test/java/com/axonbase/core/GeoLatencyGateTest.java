package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portão da E4: latência da busca geográfica com 10 milhões de pontos em RocksDB. Só roda com
 * {@code -Daxon.gate.geo=true}, em máquina dedicada.
 *
 * <p>Parâmetros (propriedades de sistema, prefixo {@code axon.gate.geo.}): {@code points} (10000000),
 * {@code queries} (5000), {@code warmup} (1000), {@code threads} (8), {@code radius} em metros (500),
 * {@code neighbours} (20), {@code limit} do raio (100), {@code side} lado do quadrado em graus (1.0),
 * {@code p99.knn} e {@code p99.radius} metas em milissegundos (50 e 50), {@code dir} diretório da base (temporário;
 * quando informado e já carregado com os mesmos parâmetros, a carga é reaproveitada).</p>
 */
@DisplayName("Portão: busca geográfica em escala")
@EnabledIfSystemProperty(named = "axon.gate.geo", matches = "true")
class GeoLatencyGateTest {

    private static final double WEST = -47.1;
    private static final double SOUTH = -24.0;
    private static final int BATCH = 500;

    private final int points = Integer.getInteger("axon.gate.geo.points", 10_000_000);
    private final int queries = Integer.getInteger("axon.gate.geo.queries", 5_000);
    private final int warmup = Integer.getInteger("axon.gate.geo.warmup", 1_000);
    private final int threads = Integer.getInteger("axon.gate.geo.threads", 8);
    private final int neighbours = Integer.getInteger("axon.gate.geo.neighbours", 20);
    private final int limit = Integer.getInteger("axon.gate.geo.limit", 100);
    private final double radius = number("axon.gate.geo.radius", 500);
    private final double side = number("axon.gate.geo.side", 1.0);
    private final double knnTarget = number("axon.gate.geo.p99.knn", 50);
    private final double radiusTarget = number("axon.gate.geo.p99.radius", 50);

    private static double number(String property, double fallback) {
        return Double.parseDouble(System.getProperty(property, String.valueOf(fallback)));
    }

    @Test
    void latency(@TempDir Path temporary) throws Exception {
        String chosen = System.getProperty("axon.gate.geo.dir");
        Path dir = chosen == null ? temporary : Path.of(chosen);
        Files.createDirectories(dir);
        Path done = dir.resolve("carga-" + points + "-" + side);
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("db").toString(), RocksDbBackend.SyncMode.OFF)) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            if (!Files.exists(done)) {
                load(ds);
                Files.createFile(done);
            }
            assertTrue(IndexBuilder.awaitAll(3_600_000), "índice não ficou pronto");
            Function<String, String> knn = c -> "SELECT id FROM g ORDER BY geo::distance(pos, " + c + ") LIMIT "
                + neighbours;
            Function<String, String> near = c -> "SELECT id FROM g WHERE geo::distance(pos, " + c + ") <= " + radius
                + " LIMIT " + limit;
            Function<String, String> all = c -> "SELECT id FROM g WHERE geo::distance(pos, " + c + ") <= " + radius;
            String probe = center(new Random(1));
            assertTrue("GEO_KNN".equals(strategy(ds, knn.apply(probe))), "os mais próximos não usam o índice");
            assertTrue("GEO".equals(strategy(ds, near.apply(probe))), "o raio não usa o índice");

            double knnP99 = measure(ds, "mais próximos", knn, queries);
            double radiusP99 = measure(ds, "raio com LIMIT", near, queries);
            // informativo: sem LIMIT o custo é proporcional aos pontos devolvidos
            measure(ds, "raio sem LIMIT", all, Math.max(1, queries / 10));
            assertTrue(knnP99 <= knnTarget, "p99 dos mais próximos " + knnP99 + " ms, meta " + knnTarget);
            assertTrue(radiusP99 <= radiusTarget, "p99 do raio " + radiusP99 + " ms, meta " + radiusTarget);
        }
    }

    private static String strategy(Datastore ds, String sql) {
        return ds.execute("EXPLAIN " + sql, GeoTestData.session(), null).asObject().get("strategy").asString();
    }

    private String center(Random rnd) {
        // longe da borda, para a densidade em volta ser a da carga
        return GeoTestData.point(WEST + side * (0.05 + 0.9 * rnd.nextDouble()),
            SOUTH + side * (0.05 + 0.9 * rnd.nextDouble()));
    }

    /** Carga em lotes com o índice já criado, que é o custo de escrita do índice em regime. */
    private void load(Datastore ds) throws Exception {
        ds.execute("DEFINE TABLE g SCHEMALESS; " + GeoTestData.INDEX, GeoTestData.session(), null);
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> parts = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int first = t;
                parts.add(pool.submit(() -> {
                    Session s = GeoTestData.session();
                    Random rnd = new Random(1000 + first);
                    StringBuilder rows = new StringBuilder();
                    int n = 0;
                    for (int i = first; i < points; i += threads) {
                        rows.append(n++ == 0 ? "" : ",").append("{id: ").append(i).append(", pos: ")
                            .append(GeoTestData.point(WEST + side * rnd.nextDouble(), SOUTH + side * rnd.nextDouble()))
                            .append('}');
                        if (n == BATCH || i + threads >= points) {
                            ds.execute("INSERT INTO g [" + rows + "]", s, null);
                            rows.setLength(0);
                            n = 0;
                        }
                    }
                }));
            }
            for (Future<?> part : parts) {
                part.get();
            }
        } finally {
            pool.shutdown();
        }
        System.out.printf(Locale.ROOT, "GEO carga: %d pontos em %.0f s%n", points, (System.nanoTime() - start) / 1e9);
    }

    /** p99 em milissegundos de {@code count} consultas com centros sorteados, depois do aquecimento. */
    private double measure(Datastore ds, String label, Function<String, String> query, int count) throws Exception {
        run(ds, query, Math.min(warmup, count), 1);
        long start = System.nanoTime();
        double[][] results = run(ds, query, count, 2);
        double seconds = (System.nanoTime() - start) / 1e9;
        double[] times = results[0];
        Arrays.sort(times);
        double p99 = times[Math.min(times.length - 1, (int) Math.ceil(times.length * 0.99) - 1)];
        System.out.printf(Locale.ROOT, "GEO %-16s n=%d %.0f consultas/s p50 %.2f ms p99 %.2f ms max %.2f ms"
                + " linhas por consulta %.1f%n", label, count, count / seconds, times[times.length / 2], p99,
            times[times.length - 1], Arrays.stream(results[1]).average().orElse(0));
        return p99;
    }

    /** {@code [0]} tempos em ms e {@code [1]} linhas devolvidas, uma posição por consulta. */
    private double[][] run(Datastore ds, Function<String, String> query, int count, int seed) throws Exception {
        double[][] out = new double[2][count];
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> parts = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int first = t;
                parts.add(pool.submit(() -> {
                    Session s = GeoTestData.session();
                    Random rnd = new Random(seed * 31L + first);
                    for (int i = first; i < count; i += threads) {
                        String sql = query.apply(center(rnd));
                        long begin = System.nanoTime();
                        AxonValue rows = ds.execute(sql, s, null);
                        out[0][i] = (System.nanoTime() - begin) / 1e6;
                        out[1][i] = rows.asArray().size();
                    }
                }));
            }
            for (Future<?> part : parts) {
                part.get();
            }
        } finally {
            pool.shutdown();
        }
        return out;
    }
}
