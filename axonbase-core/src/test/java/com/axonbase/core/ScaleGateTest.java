package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.axonbase.core.ScaleBench.order;
import static com.axonbase.core.ScaleBench.out;
import static com.axonbase.core.ScaleBench.run;
import static com.axonbase.core.ScaleBench.session;
import static com.axonbase.core.ScaleBench.time;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Teste de portão de escala: roda os cenários essenciais do benchmark de linha de base e reprova quando uma meta é
 * quebrada. Só roda com {@code -Daxon.gate=true}. As metas são razões e comparações com a linha de base
 * normalizada por calibração de CPU (Central Processing Unit) feita na mesma execução, para resistir ao ruído de
 * executores compartilhados. Na falha há uma segunda tentativa antes de reprovar.
 *
 * <p>{@code -Daxon.gate.update=true} regrava {@code src/test/resources/scale-baseline.json}; {@code -Daxon.gate.rows}
 * muda o tamanho (padrão 200000, o que as metas assumem); {@code -Daxon.gate.baseline} aponta outro arquivo.</p>
 */
@EnabledIfSystemProperty(named = "axon.gate", matches = "true")
class ScaleGateTest {

    static final int THREADS = 8;
    static final double MAX_REGRESSION = 0.25;
    /** Fração da meta abaixo da qual uma piora relativa de métrica em ms é tratada como ruído. */
    static final double NOISE_FLOOR = 0.5;
    private static final int REPEATS = 3;
    private static final String DEFAULT_FILE = "src/test/resources/scale-baseline.json";

    private final int rows = Integer.getInteger("axon.gate.rows", 200_000);
    private final Path baselineFile = Path.of(System.getProperty("axon.gate.baseline", DEFAULT_FILE));

    /** Uma execução: métricas brutas (nome para valor) e as calibrações. */
    record Run(Map<String, Double> raw, double calSingle, double calParallel, int cpus) {
        double normalized(Metric m) {
            double v = raw.get(m.name);
            return switch (m.kind) {
                case RATIO -> v;
                case SINGLE -> v / calSingle;
                case PARALLEL -> v / calParallel;
            };
        }
    }

    enum Kind { RATIO, SINGLE, PARALLEL }

    /** Métrica do portão; {@code max} é o teto absoluto (ou piso, para razão) da meta e {@code floor} diz qual dos dois. */
    enum Metric {
        INGEST_RATIO("ingest_ratio_8x1", Kind.RATIO, 2.0, true),
        LIMIT10_MS("select_limit10_ms", Kind.SINGLE, 50, false),
        NEAREST_P99_MS("nearest20_p99_ms", Kind.SINGLE, 100, false),
        ORDER_TOPK_MS("order_by_limit10_ms", Kind.PARALLEL, 500, false),
        FILTER_SCAN_MS("where_nomatch_limit10_ms", Kind.PARALLEL, 500, false);

        final String name;
        final Kind kind;
        final double goal;
        final boolean floor;

        Metric(String name, Kind kind, double goal, boolean floor) {
            this.name = name;
            this.kind = kind;
            this.goal = goal;
            this.floor = floor;
        }
    }

    @Test
    void gate() throws Exception {
        boolean update = Boolean.getBoolean("axon.gate.update");
        Run run = measure();
        if (update) {
            write(run);
            System.out.println("BASE GATE linha de base regravada em " + baselineFile.toAbsolutePath());
            return;
        }
        Run baseline = read();
        List<String> failures = evaluate(run, baseline);
        if (!failures.isEmpty()) {
            System.out.println("BASE GATE falhou na primeira tentativa, repetindo: " + failures);
            run = measure();
            failures = evaluate(run, baseline);
        }
        if (!failures.isEmpty()) {
            fail("Teste de portão de escala reprovado:\n - " + String.join("\n - ", failures));
        }
        out("GATE ok");
    }

    /** Falhas da execução frente às metas absolutas e à linha de base; vazio quando passou. */
    static List<String> evaluate(Run run, Run baseline) {
        List<String> failures = new ArrayList<>();
        for (Metric m : Metric.values()) {
            double v = run.raw.get(m.name);
            if (m.floor ? v < m.goal : v > m.goal) {
                failures.add(String.format(Locale.ROOT, "%s = %.2f quebra a meta (%s %.2f)", m.name, v, m.floor ? ">=" : "<", m.goal));
            }
            if (baseline == null || !baseline.raw.containsKey(m.name)) {
                continue;
            }
            double now = run.normalized(m);
            double base = baseline.normalized(m);
            if (m.kind == Kind.RATIO && baseline.cpus != run.cpus) {
                out("GATE %s: regressao nao comparada (linha de base com %d CPUs, esta maquina %d)", m.name, baseline.cpus, run.cpus);
                continue;
            }
            // piora relativa só conta quando o valor bruto já passa de metade da meta: abaixo disso é ruído de medida
            boolean significant = m.floor || v > m.goal * NOISE_FLOOR;
            boolean worse = significant
                && (m.floor ? now < base * (1 - MAX_REGRESSION) : now > base * (1 + MAX_REGRESSION));
            out("GATE %-26s bruto %9.2f  normalizado %9.4f  base %9.4f  %s", m.name, v, now, base, worse ? "PIOROU" : "ok");
            if (worse) {
                failures.add(String.format(Locale.ROOT, "%s piorou mais de %d%% frente a linha de base (normalizado %.4f contra %.4f)",
                    m.name, (int) (MAX_REGRESSION * 100), now, base));
            }
        }
        return failures;
    }

    // ---- medição

    private Run measure() throws Exception {
        double calSingle = calibrate(1);
        int cpus = Runtime.getRuntime().availableProcessors();
        double calParallel = calibrate(cpus);
        out("GATE calibracao: 1 thread %.1f ms, %d threads %.1f ms, rows=%d", calSingle, cpus, calParallel, rows);
        Map<String, Double> raw = new LinkedHashMap<>();
        try (RocksDbBackend backend = new RocksDbBackend(Files.createTempDirectory("scale-gate").toString())) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            Session admin = session();

            double[] a1 = run(ds, 1, rows / 4, (i, s) -> "CREATE pedido_a:" + i + " CONTENT " + order(i));
            double[] a2 = run(ds, THREADS, rows, (i, s) -> "CREATE pedido_b:" + i + " CONTENT " + order(i));
            out("A1 ingestao 1 thread           %8.0f ops/s", a1[0]);
            out("A2 ingestao %d threads          %8.0f ops/s", THREADS, a2[0]);
            raw.put(Metric.INGEST_RATIO.name, a2[0] / a1[0]);

            time(ds, "SELECT * FROM pedido_b LIMIT 10");
            raw.put(Metric.LIMIT10_MS.name, bestOf(ds, "SELECT * FROM pedido_b LIMIT 10"));
            out("B3b SELECT * LIMIT 10, 2a vez     %8.1f ms", raw.get(Metric.LIMIT10_MS.name));
            raw.put(Metric.FILTER_SCAN_MS.name, bestOf(ds, "SELECT * FROM pedido_b WHERE valor > 9999 LIMIT 10"));
            out("B6 WHERE valor > 9999 LIMIT 10    %8.1f ms", raw.get(Metric.FILTER_SCAN_MS.name));
            raw.put(Metric.ORDER_TOPK_MS.name, bestOf(ds, "SELECT * FROM pedido_b ORDER BY valor DESC LIMIT 10"));
            out("B8 ORDER BY valor DESC LIMIT 10   %8.1f ms", raw.get(Metric.ORDER_TOPK_MS.name));

            int drivers = Math.min(rows, 100_000);
            ds.execute("DEFINE TABLE entregador SCHEMALESS; CREATE INDEX ent_pos ON TABLE entregador COLUMNS pos GEO", admin, null);
            run(ds, THREADS, drivers, (i, s) -> "CREATE entregador:" + i + " CONTENT {nome: 'e" + i
                + "', pos: {type: 'Point', coordinates: [" + (-46.8 + (i % 400) * 0.001) + ", " + (-23.7 + (i / 400 % 250) * 0.001) + "]}}");
            double[] d3 = null;
            for (int r = 0; r < REPEATS; r++) {
                double[] one = run(ds, 4, 2_000, (i, s) -> "SELECT nome FROM entregador ORDER BY geo::distance(pos, {type: 'Point', coordinates: ["
                    + (-46.7 + (i % 100) * 0.001) + ", -23.6]}) LIMIT 20");
                if (d3 == null || one[2] < d3[2]) {
                    d3 = one;
                }
            }
            out("D3 20 mais proximos, 4 threads    %8.0f ops/s  p50 %.2f ms  p99 %.2f ms", d3[0], d3[1], d3[2]);
            raw.put(Metric.NEAREST_P99_MS.name, d3[2]);
        }
        return new Run(raw, calSingle, calParallel, cpus);
    }

    /** Menor tempo (ms) de {@link #REPEATS} execuções: o ruído de um executor compartilhado só soma tempo. */
    private static double bestOf(Datastore ds, String sql) {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < REPEATS; r++) {
            best = Math.min(best, time(ds, sql));
        }
        return best;
    }

    /** Trabalho de CPU fixo (ordenar, misturar bits e montar texto) rodado em {@code threads} threads; melhor de 5, em ms. */
    private static double calibrate(int threads) throws Exception {
        double best = Double.MAX_VALUE;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 6; round++) {
                long t0 = System.nanoTime();
                List<Future<Long>> fs = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    fs.add(pool.submit(ScaleGateTest::cpuWork));
                }
                for (Future<Long> f : fs) {
                    f.get();
                }
                double ms = (System.nanoTime() - t0) / 1e6;
                if (round > 0) {
                    best = Math.min(best, ms);
                }
            }
        } finally {
            pool.shutdown();
        }
        return best;
    }

    private static long cpuWork() {
        long h = 1125899906842597L;
        int[] a = new int[400_000];
        for (int i = 0; i < a.length; i++) {
            h = h * 31 + i;
            a[i] = (int) (h ^ (h >>> 29));
        }
        java.util.Arrays.sort(a);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60_000; i++) {
            sb.append("c").append(a[i * 5]).append(',');
            if (sb.length() > 4096) {
                h += sb.toString().hashCode();
                sb.setLength(0);
            }
        }
        return h + sb.length();
    }

    // ---- linha de base

    private void write(Run run) throws Exception {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"rows\": ").append(rows).append(",\n");
        sb.append("  \"cpus\": ").append(run.cpus).append(",\n");
        sb.append("  \"os\": \"").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append("\",\n");
        sb.append(String.format(Locale.ROOT, "  \"calibration\": {\"single_ms\": %.2f, \"parallel_ms\": %.2f},\n", run.calSingle, run.calParallel));
        sb.append("  \"raw\": {\n");
        sb.append(join(run, false)).append("  },\n  \"normalized\": {\n").append(join(run, true)).append("  }\n}\n");
        Files.createDirectories(baselineFile.toAbsolutePath().getParent());
        Files.writeString(baselineFile, sb.toString());
    }

    private static String join(Run run, boolean normalized) {
        StringBuilder sb = new StringBuilder();
        Metric[] ms = Metric.values();
        for (int i = 0; i < ms.length; i++) {
            double v = normalized ? run.normalized(ms[i]) : run.raw.get(ms[i].name);
            sb.append(String.format(Locale.ROOT, "    \"%s\": %.4f%s\n", ms[i].name, v, i < ms.length - 1 ? "," : ""));
        }
        return sb.toString();
    }

    /** Só o necessário para comparar: a linha de base guarda as normalizadas e as calibrações, que aqui viram uma {@link Run} equivalente. */
    private Run read() throws Exception {
        if (!Files.exists(baselineFile)) {
            fail("Linha de base ausente em " + baselineFile.toAbsolutePath() + "; gere com -Daxon.gate.update=true");
        }
        AxonValue json = AxonJson.decode(Files.readAllBytes(baselineFile));
        Map<String, AxonValue> root = json.asObject();
        Map<String, AxonValue> cal = root.get("calibration").asObject();
        double single = cal.get("single_ms").asDouble();
        double parallel = cal.get("parallel_ms").asDouble();
        Map<String, AxonValue> raw = root.get("raw").asObject();
        Map<String, Double> values = new LinkedHashMap<>();
        for (Metric m : Metric.values()) {
            if (raw.containsKey(m.name)) {
                values.put(m.name, raw.get(m.name).asDouble());
            }
        }
        return new Run(values, single, parallel, (int) root.get("cpus").asLong());
    }
}
