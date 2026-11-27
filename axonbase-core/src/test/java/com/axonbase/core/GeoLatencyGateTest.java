package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portão da E4: latência da busca geográfica com 10 milhões de pontos em RocksDB. Só roda com
 * {@code -Daxon.gate.geo=true}, em máquina dedicada.
 *
 * <p>Parâmetros (propriedades de sistema, prefixo {@code axon.gate.geo.}): {@code points} (10000000),
 * {@code queries} (5000), {@code warmup} (1000), {@code threads} (8), {@code radius} em metros (500),
 * {@code neighbours} (20), {@code limit} do raio (100), {@code side} lado do quadrado em graus (1.0),
 * {@code p99.knn} e {@code p99.radius} metas em milissegundos (50 e 50), {@code moves} registros movidos depois das
 * consultas (um décimo dos pontos; 0 desliga), {@code step} tamanho de cada movimento em metros (30), {@code settle}
 * pausa em segundos antes de uma terceira medida (0 desliga), {@code dir} diretório da base (temporário; quando
 * informado e já carregado com os mesmos parâmetros, a carga é reaproveitada).</p>
 *
 * <p>As metas valem só para a primeira medida, antes dos movimentos. As repetições, os movimentos e as consultas
 * seguintes são informativos: servem para entender a primeira medida e comparar resoluções de armazenamento (ver
 * {@code scripts/geo-comparar-resolucoes.sh}).</p>
 */
@DisplayName("Portão: busca geográfica em escala")
@EnabledIfSystemProperty(named = "axon.gate.geo", matches = "true")
class GeoLatencyGateTest {

    private static final double WEST = -47.1;
    private static final double SOUTH = -24.0;
    private static final int BATCH = 500;
    private static final double METERS_PER_DEGREE = 111_320;

    private final int points = Integer.getInteger("axon.gate.geo.points", 10_000_000);
    private final int queries = Integer.getInteger("axon.gate.geo.queries", 5_000);
    private final int warmup = Integer.getInteger("axon.gate.geo.warmup", 1_000);
    private final int threads = Integer.getInteger("axon.gate.geo.threads", 8);
    private final int neighbours = Integer.getInteger("axon.gate.geo.neighbours", 20);
    private final int limit = Integer.getInteger("axon.gate.geo.limit", 100);
    private final int moves = Math.min(points, Integer.getInteger("axon.gate.geo.moves", points / 10));
    private final int settle = Integer.getInteger("axon.gate.geo.settle", 0);
    private final double step = number("axon.gate.geo.step", 30);
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
        Path db = dir.resolve("db");
        try (RocksDbBackend backend = new RocksDbBackend(db.toString(), RocksDbBackend.SyncMode.OFF)) {
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
            checkStrategies(knn, near);

            backend.flush();
            printSize(db, "depois da carga");
            double knnP99 = measure(ds, "mais próximos", knn, queries);
            double radiusP99 = measure(ds, "raio com LIMIT", near, queries);
            // informativo: sem LIMIT o custo é proporcional aos pontos devolvidos
            measure(ds, "raio sem LIMIT", all, Math.max(1, queries / 10));
            repeat(ds, db, knn, near);
            if (moves > 0) {
                move(ds);
                backend.flush();
                printSize(db, "depois dos movimentos");
                measure(ds, "próximos pós-mov", knn, queries);
                measure(ds, "raio pós-mov", near, queries);
            }
            assertTrue(knnP99 <= knnTarget, "p99 dos mais próximos " + knnP99 + " ms, meta " + knnTarget);
            assertTrue(radiusP99 <= radiusTarget, "p99 do raio " + radiusP99 + " ms, meta " + radiusTarget);
        }
    }

    /**
     * As consultas medidas precisam usar o índice. A conferência é feita numa base pequena em memória, porque o
     * {@code EXPLAIN} estima as linhas lendo a tabela inteira para a memória.
     */
    private void checkStrategies(Function<String, String> knn, Function<String, String> near) {
        Datastore small = new Datastore(new MemoryBackend());
        small.createDatabase("test", "dev");
        String probe = center(new Random(1));
        GeoTestData.run(small, "DEFINE TABLE g SCHEMALESS; " + GeoTestData.INDEX);
        GeoTestData.run(small, "CREATE g:0 SET pos = " + probe);
        assertTrue("GEO_KNN".equals(GeoTestData.strategy(small, knn.apply(probe))),
            "os mais próximos não usam o índice");
        assertTrue("GEO".equals(GeoTestData.strategy(small, near.apply(probe))), "o raio não usa o índice");
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
        parallel(first -> {
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
        });
        System.out.printf(Locale.ROOT, "GEO carga: %d pontos em %.0f s%n", points, (System.nanoTime() - start) / 1e9);
    }

    /**
     * Deslocamentos curtos em direções sorteadas, como os de um veículo entre duas posições: é o custo de escrita que a
     * resolução das chaves muda. As posições atuais são lidas antes, fora do tempo medido.
     */
    private void move(Datastore ds) throws Exception {
        int stride = points / moves;
        String[] next = new String[moves];
        parallel(first -> {
            Session s = GeoTestData.session();
            Random rnd = new Random(7000 + first);
            for (int i = first; i < moves; i += threads) {
                // a seleção de um único registro devolve o próprio valor, sem lista em volta
                List<AxonValue> at = ds.execute("SELECT VALUE pos FROM g:" + i * stride, s, null)
                    .asObject().get("coordinates").asArray();
                double heading = rnd.nextDouble() * 2 * Math.PI;
                double lat = at.get(1).asDouble() + step * Math.cos(heading) / METERS_PER_DEGREE;
                double lon = at.get(0).asDouble()
                    + step * Math.sin(heading) / (METERS_PER_DEGREE * Math.cos(Math.toRadians(lat)));
                next[i] = GeoTestData.point(lon, lat);
            }
        });
        double[] times = new double[moves];
        long start = System.nanoTime();
        parallel(first -> {
            Session s = GeoTestData.session();
            for (int i = first; i < moves; i += threads) {
                String sql = "UPDATE g:" + i * stride + " SET pos = " + next[i];
                long begin = System.nanoTime();
                ds.execute(sql, s, null);
                times[i] = (System.nanoTime() - begin) / 1e6;
            }
        });
        double seconds = (System.nanoTime() - start) / 1e9;
        Arrays.sort(times);
        System.out.printf(Locale.ROOT, "GEO movimentos: n=%d passo %.0f m %.0f atualizações/s p50 %.2f ms p99 %.2f ms%n",
            moves, step, moves / seconds, times[times.length / 2], p99(times));
    }

    /**
     * Repete as consultas antes dos movimentos, sem escrever nada, para separar as causas de uma primeira medida lenta.
     * A primeira repetição usa os mesmos centros, então mostra o efeito do cache; a segunda vem depois de uma pausa,
     * em que a compactação em segundo plano pode avançar.
     */
    private void repeat(Datastore ds, Path db, Function<String, String> knn, Function<String, String> near)
        throws Exception {
        printSize(db, "antes da repetição");
        measure(ds, "próximos de novo", knn, queries);
        measure(ds, "raio de novo", near, queries);
        if (settle > 0) {
            Thread.sleep(settle * 1_000L);
            printSize(db, "depois da pausa de " + settle + " s");
            measure(ds, "próximos pós-pausa", knn, queries);
            measure(ds, "raio pós-pausa", near, queries);
        }
    }

    /**
     * Tamanho e quantidade de tabelas SST (Sorted String Table) nos arquivos da base: uma queda na quantidade entre
     * dois momentos indica compactação.
     */
    private static void printSize(Path db, String moment) {
        File[] files = db.toFile().listFiles(File::isFile);
        long bytes = files == null ? 0 : Arrays.stream(files).mapToLong(File::length).sum();
        long tables = files == null ? 0 : Arrays.stream(files).filter(f -> f.getName().endsWith(".sst")).count();
        System.out.printf(Locale.ROOT, "GEO base %s: %.0f MB, %d tabelas SST%n", moment, bytes / 1048576.0, tables);
    }

    private static double p99(double[] sorted) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.99) - 1)];
    }

    /** p99 em milissegundos de {@code count} consultas com centros sorteados, depois do aquecimento. */
    private double measure(Datastore ds, String label, Function<String, String> query, int count) throws Exception {
        run(ds, query, Math.min(warmup, count), 1);
        long start = System.nanoTime();
        double[][] results = run(ds, query, count, 2);
        double seconds = (System.nanoTime() - start) / 1e9;
        double[] times = results[0];
        Arrays.sort(times);
        double p99 = p99(times);
        System.out.printf(Locale.ROOT, "GEO %-16s n=%d %.0f consultas/s p50 %.2f ms p99 %.2f ms max %.2f ms"
                + " linhas por consulta %.1f%n", label, count, count / seconds, times[times.length / 2], p99,
            times[times.length - 1], Arrays.stream(results[1]).average().orElse(0));
        return p99;
    }

    /** {@code [0]} tempos em ms e {@code [1]} linhas devolvidas, uma posição por consulta. */
    private double[][] run(Datastore ds, Function<String, String> query, int count, int seed) throws Exception {
        double[][] out = new double[2][count];
        parallel(first -> {
            Session s = GeoTestData.session();
            Random rnd = new Random(seed * 31L + first);
            for (int i = first; i < count; i += threads) {
                String sql = query.apply(center(rnd));
                long begin = System.nanoTime();
                AxonValue rows = ds.execute(sql, s, null);
                out[0][i] = (System.nanoTime() - begin) / 1e6;
                out[1][i] = rows.asArray().size();
            }
        });
        return out;
    }

    /** Roda {@code worker} em cada thread, com o número dela, e propaga a primeira falha. */
    private void parallel(IntConsumer worker) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> parts = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int first = t;
                parts.add(pool.submit(() -> worker.accept(first)));
            }
            for (Future<?> part : parts) {
                part.get();
            }
        } finally {
            pool.shutdown();
        }
    }
}
