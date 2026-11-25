package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.axonbase.core.ScaleBench.order;
import static com.axonbase.core.ScaleBench.out;
import static com.axonbase.core.ScaleBench.run;
import static com.axonbase.core.ScaleBench.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Portão da E1.2: UNIQUE criado em segundo plano numa tabela grande, com escrita concorrente, fica idêntico a um
 * índice criado antes da carga, e uma duplicata aborta sem deixar rastro. Só roda com {@code -Daxon.gate.index=true};
 * {@code -Daxon.gate.index.rows} muda o tamanho (padrão 5 milhões, o alvo do plano).
 */
@EnabledIfSystemProperty(named = "axon.gate.index", matches = "true")
class OnlineIndexScaleGateTest {

    private final int rows = Integer.getInteger("axon.gate.index.rows", 5_000_000);

    private static long count(KvBackend kv, String prefix) {
        long[] n = {0};
        kv.scanPrefix(prefix, k -> {
            n[0]++;
            return true;
        });
        return n[0];
    }

    private static String churn(int i, int rows, String t) {
        return switch (i % 3) {
            case 0 -> "CREATE " + t + ":" + (rows + i) + " CONTENT " + order(rows + i);
            case 1 -> "UPDATE " + t + ":" + ((i * 7919L) % rows) + " MERGE {codigo: 'z" + i + "'}";
            default -> "DELETE " + t + ":" + ((i * 104729L) % rows);
        };
    }

    @Test
    void indexAfterLoadMatchesIndexBeforeLoad() throws Exception {
        try (RocksDbBackend backend = new RocksDbBackend(Files.createTempDirectory("index-gate").toString())) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            ds.findDatabase("test", "dev").indexInlineRows(10_000);
            Session s = session();

            ds.execute("DEFINE TABLE ref SCHEMALESS", s, null);
            ds.execute("DEFINE INDEX ref_codigo ON TABLE ref COLUMNS codigo UNIQUE", s, null);
            long t0 = System.nanoTime();
            run(ds, 8, rows, (i, x) -> "CREATE ref:" + i + " CONTENT " + order(i));
            run(ds, 8, rows, (i, x) -> "CREATE onl:" + i + " CONTENT " + order(i));
            out("INDEX carga de 2 x %d linhas: %.1f s", rows, (System.nanoTime() - t0) / 1e9);

            // escrita contínua enquanto o índice é construído
            AtomicBoolean building = new AtomicBoolean(true);
            AtomicInteger ops = new AtomicInteger();
            List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Thread> writers = new ArrayList<>();
            int[] cursor = {0};
            for (int w = 0; w < 4; w++) {
                Thread th = new Thread(() -> {
                    Session ws = session();
                    while (building.get()) {
                        int i;
                        synchronized (cursor) {
                            i = cursor[0]++;
                        }
                        long a = System.nanoTime();
                        try {
                            ds.execute(churn(i, rows, "onl"), ws, null);
                        } catch (RuntimeException ignored) {
                            // delete de linha já apagada e afins: a mesma operação roda na referência
                        }
                        latencies.add(System.nanoTime() - a);
                        ops.incrementAndGet();
                    }
                });
                writers.add(th);
                th.start();
            }
            long b0 = System.nanoTime();
            ds.execute("DEFINE INDEX onl_codigo ON TABLE onl COLUMNS codigo UNIQUE", s, null);
            assertTrue(IndexBuilder.awaitAll(3_600_000), "construção não terminou");
            double buildSecs = (System.nanoTime() - b0) / 1e9;
            building.set(false);
            for (Thread th : writers) {
                th.join();
            }
            long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            out("INDEX construção %.1f s com %d escritas concorrentes, p99 %.1f ms, max %.1f ms", buildSecs,
                ops.get(), sorted[(int) (sorted.length * 0.99)] / 1e6, sorted[sorted.length - 1] / 1e6);

            // a mesma sequência de escritas na referência
            Session rs = session();
            for (int i = 0; i < cursor[0]; i++) {
                try {
                    ds.execute(churn(i, rows, "ref"), rs, null);
                } catch (RuntimeException ignored) {
                    // idem
                }
            }

            KvBackend kv = ds.findDatabase("test", "dev").records();
            long refEntries = count(kv, "test\u0000dev\u0000ref\u0000!uidx\u0000codigo\u0000");
            long onlEntries = count(kv, "test\u0000dev\u0000onl\u0000!uidx\u0000codigo\u0000");
            out("INDEX entradas: referência %d, em segundo plano %d", refEntries, onlEntries);
            assertEquals(refEntries, onlEntries);
            Random random = new Random(42);
            for (int p = 0; p < 2000; p++) {
                String probe = random.nextBoolean() ? "c" + random.nextInt(rows * 2) : "z" + random.nextInt(cursor[0] + 1);
                String a = com.axonbase.value.AxonJson.write(
                    ds.execute("SELECT codigo FROM ref WHERE codigo = '" + probe + "'", s, null));
                String b = com.axonbase.value.AxonJson.write(
                    ds.execute("SELECT codigo FROM onl WHERE codigo = '" + probe + "'", s, null));
                assertEquals(a, b, probe);
            }

            // duplicata: aborta e não deixa rastro
            int small = Math.min(rows, 200_000);
            run(ds, 8, small, (i, x) -> "CREATE dup:" + i + " CONTENT " + order(i));
            ds.execute("CREATE dup:extra CONTENT " + order(small / 2), s, null);
            ds.execute("DEFINE INDEX dup_codigo ON TABLE dup COLUMNS codigo UNIQUE", s, null);
            assertTrue(IndexBuilder.awaitAll(3_600_000));
            assertEquals(0L, count(kv, "test\u0000dev\u0000dup\u0000!uidx\u0000"));
            assertEquals(0L, count(kv, "IXB|"));
            AxonValue info = ds.execute("INFO FOR TABLE dup", s, null);
            if (!com.axonbase.value.AxonJson.write(info).contains("index_failures")) {
                fail("falha não registrada");
            }
        }
    }
}
