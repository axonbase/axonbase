package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Medição (não é teste de regressão): consultas de congestionamento sobre muitas linhas em RocksDB real.
 * Só roda com {@code -Daxon.bench=true}; o número de linhas vem de {@code -Daxon.bench.rows} (padrão 200000).
 * Imprime linhas {@code BENCH ...} com o tempo de cada consulta, com e sem índice GEO.
 */
@EnabledIfSystemProperty(named = "axon.bench", matches = "true")
class AggregationBenchmarkTest {

    private static final String WINDOW = "time::clock(quando, 'America/Sao_Paulo') BETWEEN '18:00' AND '19:30'";
    private static final int TRECHOS = 15;

    @Test
    void congestionamento() throws Exception {
        int rows = Integer.getInteger("axon.bench.rows", 200_000);
        for (boolean geoIndex : new boolean[] {false, true}) {
            try (RocksDbBackend backend = new RocksDbBackend(Files.createTempDirectory("bench").toString())) {
                Datastore ds = new Datastore(backend);
                ds.createDatabase("test", "dev");
                Session s = Session.create();
                s.namespace("test");
                s.database("dev");
                if (geoIndex) {
                    ds.execute("DEFINE INDEX transito_geo ON TABLE transito COLUMNS pos GEO", s, null);
                }
                long t0 = System.nanoTime();
                load(ds, s, rows);
                System.out.printf(Locale.ROOT, "BENCH geoIndex=%s rows=%d carga=%.1fs%n", geoIndex, rows, (System.nanoTime() - t0) / 1e9);

                time("grade 500 m (GROUP BY geo::cell)", geoIndex, ds, s,
                    "SELECT geo::cell(pos, 500) AS trecho, count() AS pings, count(DISTINCT placa) AS carros FROM transito WHERE "
                        + WINDOW + " GROUP BY trecho ORDER BY pings DESC LIMIT 10");
                time("trechos nomeados ($parent + geo::contains)", geoIndex, ds, s,
                    "SELECT id, nome, (SELECT VALUE count() FROM transito WHERE " + WINDOW
                        + " AND geo::contains($parent.geo, pos)) AS pings FROM trechos ORDER BY pings DESC");
                time("contagem simples com filtro por placa", geoIndex, ds, s,
                    "SELECT count() AS n FROM transito WHERE placa = 'PLC0001' GROUP ALL");
            }
        }
    }

    private void time(String label, boolean geoIndex, Datastore ds, Session s, String sql) {
        long best = Long.MAX_VALUE;
        AxonValue last = null;
        for (int i = 0; i < 2; i++) {
            long t0 = System.nanoTime();
            last = ds.execute(sql, s, null);
            best = Math.min(best, System.nanoTime() - t0);
        }
        assertTrue(last.isArray() && !last.asArray().isEmpty());
        System.out.printf(Locale.ROOT, "BENCH geoIndex=%s %-45s %.2fs%n", geoIndex, label, best / 1e9);
    }

    private void load(Datastore ds, Session s, int rows) {
        Random random = new Random(7);
        StringBuilder trechos = new StringBuilder();
        double[][] centers = new double[TRECHOS][];
        for (int i = 0; i < TRECHOS; i++) {
            centers[i] = new double[] {-46.74 + 0.012 * i, -23.62 + 0.006 * i};
            double lon = centers[i][0];
            double lat = centers[i][1];
            double d = 0.0015;
            if (i > 0) {
                trechos.append(',');
            }
            trechos.append(String.format(Locale.ROOT,
                "{id: 'a%d', nome: 'Trecho %d', geo: {type: 'Polygon', coordinates: [[[%f,%f],[%f,%f],[%f,%f],[%f,%f],[%f,%f]]]}}",
                i, i, lon - d, lat - d, lon + d, lat - d, lon + d, lat + d, lon - d, lat + d, lon - d, lat - d));
        }
        ds.execute("INSERT INTO trechos [" + trechos + "]", s, null);
        int batch = 1000;
        for (int from = 0; from < rows; from += batch) {
            StringBuilder sb = new StringBuilder("INSERT INTO transito [");
            for (int i = from; i < Math.min(rows, from + batch); i++) {
                double lon;
                double lat;
                if (random.nextInt(10) < 6) {
                    double[] c = centers[random.nextInt(TRECHOS)];
                    lon = c[0] + (random.nextDouble() - 0.5) * 0.0028;
                    lat = c[1] + (random.nextDouble() - 0.5) * 0.0028;
                } else {
                    lon = -46.75 + random.nextDouble() * 0.2;
                    lat = -23.65 + random.nextDouble() * 0.15;
                }
                int day = 2 + random.nextInt(21);
                int utcHour = 9 + random.nextInt(16); // 06h às 21h em Brasília
                if (i > from) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.ROOT,
                    "{quando: '2026-11-%02dT%02d:%02d:00Z', placa: 'PLC%04d', pos: {type: 'Point', coordinates: [%.6f, %.6f]}}",
                    day, utcHour % 24, random.nextInt(60), random.nextInt(600), lon, lat));
            }
            ds.execute(sb.append(']').toString(), s, null);
        }
    }
}
