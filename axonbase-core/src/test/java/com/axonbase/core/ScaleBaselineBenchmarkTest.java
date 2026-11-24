package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Linha de base de escala (não é teste de regressão): ingestão, leitura por id, SELECT com e sem índice, interferência
 * entre SELECT lento e DML, e atualização de posição com índice GEO, em RocksDB real e no mesmo processo.
 * Só roda com {@code -Daxon.bench=true}; o tamanho vem de {@code -Daxon.bench.rows} (padrão 200000).
 * Imprime linhas {@code BASE ...}.
 */
@EnabledIfSystemProperty(named = "axon.bench", matches = "true")
class ScaleBaselineBenchmarkTest {

    private static final int THREADS = 8;
    private final int rows = Integer.getInteger("axon.bench.rows", 200_000);

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static void out(String fmt, Object... args) {
        System.out.println("BASE " + String.format(Locale.ROOT, fmt, args));
    }

    /** Percentil (ms) de uma lista de latências em nanossegundos. */
    private static double pct(long[] nanos, int n, double p) {
        long[] copy = Arrays.copyOf(nanos, n);
        Arrays.sort(copy);
        return copy[Math.min(n - 1, (int) Math.floor(p * n))] / 1e6;
    }

    private static String order(int i) {
        String[] status = {"novo", "preparo", "entrega", "entregue"};
        return "{codigo: 'c" + i + "', cliente: 'cli" + (i % 5000) + "', loja: 'loja" + (i % 300) + "', status: '"
            + status[i % 4] + "', valor: " + (10 + i % 500) + ", itens: [{p: 'x', q: 1}, {p: 'y', q: 2}]}";
    }

    /** Roda {@code total} operações em {@code threads} threads e devolve [ops/s, p50 ms, p99 ms, max ms]. */
    private double[] run(Datastore ds, int threads, int total, java.util.function.BiFunction<Integer, Session, String> sql)
        throws Exception {
        long[] lat = new long[total];
        AtomicInteger next = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                Session s = session();
                int i;
                while ((i = next.getAndIncrement()) < total) {
                    long a = System.nanoTime();
                    ds.execute(sql.apply(i, s), s, null);
                    lat[i] = System.nanoTime() - a;
                }
                return null;
            }));
        }
        for (var f : fs) {
            f.get();
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();
        return new double[] {total / secs, pct(lat, total, 0.5), pct(lat, total, 0.99), pct(lat, total, 1.0)};
    }

    private double time(Datastore ds, String sql) {
        Session s = session();
        long a = System.nanoTime();
        ds.execute(sql, s, null);
        return (System.nanoTime() - a) / 1e6;
    }

    @Test
    void baseline() throws Exception {
        try (RocksDbBackend backend = new RocksDbBackend(Files.createTempDirectory("scale").toString())) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            Session admin = session();

            // ---- A. ingestão
            double[] a1 = run(ds, 1, rows / 4, (i, s) -> "CREATE pedido_a:" + i + " CONTENT " + order(i));
            out("A1 ingestao 1 thread, sem indice          %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", a1[0], a1[1], a1[2], a1[3]);
            double[] a2 = run(ds, THREADS, rows, (i, s) -> "CREATE pedido_b:" + i + " CONTENT " + order(i));
            out("A2 ingestao %d threads, sem indice        %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", THREADS, a2[0], a2[1], a2[2], a2[3]);
            ds.execute("DEFINE TABLE pedido SCHEMALESS; CREATE INDEX pedido_cod ON TABLE pedido COLUMNS codigo UNIQUE; "
                + "CREATE INDEX pedido_st ON TABLE pedido COLUMNS status COLUMNAR", admin, null);
            double[] a3 = run(ds, THREADS, rows, (i, s) -> "CREATE pedido:" + i + " CONTENT " + order(i));
            out("A3 ingestao %d threads, UNIQUE+COLUMNAR   %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", THREADS, a3[0], a3[1], a3[2], a3[3]);

            // ---- B. leitura
            double[] b1 = run(ds, THREADS, rows / 2, (i, s) -> "SELECT * FROM pedido:" + (i % rows));
            out("B1 leitura por id %d threads              %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", THREADS, b1[0], b1[1], b1[2], b1[3]);
            out("B2 WHERE codigo = x (UNIQUE)              %8.1f ms", time(ds, "SELECT * FROM pedido WHERE codigo = 'c" + (rows / 2) + "'"));
            out("B3 SELECT * LIMIT 10                      %8.1f ms", time(ds, "SELECT * FROM pedido LIMIT 10"));
            out("B3b mesma consulta, 2a vez                %8.1f ms", time(ds, "SELECT * FROM pedido LIMIT 10"));
            out("B3c LIMIT 10 em tabela sem indice         %8.1f ms", time(ds, "SELECT * FROM pedido_b LIMIT 10"));
            out("B4 WHERE status = 'novo' LIMIT 10 (COL.)  %8.1f ms", time(ds, "SELECT * FROM pedido WHERE status = 'novo' LIMIT 10"));
            out("B5 count() GROUP ALL (coluna indexada)    %8.1f ms", time(ds, "SELECT count() FROM pedido GROUP ALL"));
            out("B6 WHERE valor > 9999 LIMIT 10 (sem idx)  %8.1f ms", time(ds, "SELECT * FROM pedido WHERE valor > 9999 LIMIT 10"));
            out("B7 GROUP BY loja (sem indice)             %8.1f ms", time(ds, "SELECT loja, count() AS n FROM pedido GROUP BY loja ORDER BY n DESC LIMIT 3"));
            out("B8 ORDER BY valor DESC LIMIT 10 (top-K)   %8.1f ms", time(ds, "SELECT * FROM pedido ORDER BY valor DESC LIMIT 10"));

            // ---- C. interferência: DML com e sem SELECT lento ao lado
            AtomicBoolean stop = new AtomicBoolean();
            double[] c1 = run(ds, 4, 40_000, (i, s) -> "UPDATE pedido:" + (i % rows) + " SET status = 'entregue'");
            out("C1 UPDATE 4 threads, sozinho              %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", c1[0], c1[1], c1[2], c1[3]);
            Thread scanner = new Thread(() -> {
                Session s = session();
                while (!stop.get()) {
                    ds.execute("SELECT count() FROM pedido WHERE valor > 9999999 GROUP ALL", s, null);
                }
            });
            scanner.start();
            double[] c2 = run(ds, 4, 40_000, (i, s) -> "UPDATE pedido:" + (i % rows) + " SET status = 'preparo'");
            stop.set(true);
            scanner.join();
            out("C2 UPDATE 4 threads + SELECT varredura    %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", c2[0], c2[1], c2[2], c2[3]);

            // ---- D. entregadores com índice GEO (posição atualizada o tempo todo)
            int drivers = Math.min(rows, 100_000);
            ds.execute("DEFINE TABLE entregador SCHEMALESS; CREATE INDEX ent_pos ON TABLE entregador COLUMNS pos GEO", admin, null);
            Random rnd = new Random(5);
            double[] d0 = run(ds, THREADS, drivers, (i, s) -> "CREATE entregador:" + i + " CONTENT {nome: 'e" + i
                + "', pos: {type: 'Point', coordinates: [" + (-46.8 + (i % 400) * 0.001) + ", " + (-23.7 + (i / 400 % 250) * 0.001) + "]}}");
            out("D0 carga %d entregadores com GEO          %8.0f ops/s  p50 %.2f ms  p99 %.2f ms", drivers, d0[0], d0[1], d0[2]);
            double[] d1 = run(ds, THREADS, 80_000, (i, s) -> "UPDATE entregador:" + (i % drivers) + " SET pos = {type: 'Point', coordinates: ["
                + (-46.8 + ((i * 7) % 400) * 0.001) + ", " + (-23.7 + ((i * 3) / 400 % 250) * 0.001) + "]}");
            out("D1 atualiza posicao %d threads (GEO)      %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", THREADS, d1[0], d1[1], d1[2], d1[3]);
            double[] d2 = run(ds, 4, 2_000, (i, s) -> "SELECT nome FROM entregador WHERE geo::distance(pos, {type: 'Point', coordinates: ["
                + (-46.7 + (i % 100) * 0.001) + ", -23.6]}) <= 2000 LIMIT 20");
            out("D2 raio de 2 km, 4 threads                %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", d2[0], d2[1], d2[2], d2[3]);
            double[] d3 = run(ds, 4, 2_000, (i, s) -> "SELECT nome FROM entregador ORDER BY geo::distance(pos, {type: 'Point', coordinates: ["
                + (-46.7 + (i % 100) * 0.001) + ", -23.6]}) LIMIT 20");
            out("D3 20 mais proximos, 4 threads            %8.0f ops/s  p50 %.2f ms  p99 %.2f ms  max %.0f ms", d3[0], d3[1], d3[2], d3[3]);

            // ---- E. manutenção de índice vetorial e trilha de auditoria: o custo por escrita não deve crescer com o tamanho
            ds.execute("DEFINE TABLE vet SCHEMALESS; CREATE INDEX vet_i ON TABLE vet COLUMNS emb HNSW DIMENSION 8 DIST euclidean", admin, null);
            Random vr = new Random(9);
            int vectors = Math.min(rows / 50, 4_000);
            long[] vlat = new long[vectors];
            for (int i = 0; i < vectors; i++) {
                StringBuilder v = new StringBuilder("[");
                for (int k = 0; k < 8; k++) {
                    v.append(k > 0 ? ", " : "").append(vr.nextDouble());
                }
                long a = System.nanoTime();
                ds.execute("CREATE vet:" + i + " CONTENT {n: " + i + ", emb: " + v + "]}", admin, null);
                vlat[i] = System.nanoTime() - a;
            }
            int slice = Math.max(1, vectors / 10);
            out("E1 insert vetorial: primeiras %d p50 %.2f ms, ultimas %d p50 %.2f ms", slice,
                pct(Arrays.copyOfRange(vlat, 0, slice), slice, 0.5), slice, pct(Arrays.copyOfRange(vlat, vectors - slice, vectors), slice, 0.5));
            ds.auditConfig(true, "auth", false, 65536);
            admin.auth(com.axonbase.value.AxonValue.object(java.util.Map.of("id", com.axonbase.value.AxonValue.str("bench"))));
            int audited = Math.min(rows / 4, 40_000);
            long[] alat = new long[audited];
            for (int i = 0; i < audited; i++) {
                long a = System.nanoTime();
                ds.execute("CREATE aud CONTENT {n: " + i + "}", admin, null);
                alat[i] = System.nanoTime() - a;
            }
            int aslice = Math.max(1, audited / 10);
            out("E2 DML com auditoria: primeiras %d p50 %.3f ms, ultimas %d p50 %.3f ms", aslice,
                pct(Arrays.copyOfRange(alat, 0, aslice), aslice, 0.5), aslice, pct(Arrays.copyOfRange(alat, audited - aslice, audited), aslice, 0.5));
            out("FIM rows=%d", rows);
        }
    }
}
