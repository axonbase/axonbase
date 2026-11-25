package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1.2: DEFINE INDEX constrói em segundo plano, aplica as escritas concorrentes, só é usado depois de
 * pronto e, se falhar, não deixa nada para trás.
 */
@DisplayName("Índice criado em segundo plano")
class OnlineIndexBuildTest {

    private static final int ROWS = 3000;

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

    private static Datastore store(KvBackend backend, boolean background) {
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "dev");
        if (background) {
            ds.findDatabase("test", "dev").indexInlineRows(0);
        }
        return ds;
    }

    private static String content(int i) {
        return "{email: 'u" + i + "', city: 'c" + (i % 7) + "', age: " + (i % 50)
            + ", body: 'alpha w" + (i % 13) + "', loc: geometry::point(" + (-43.0 - (i % 100) / 100.0) + ", "
            + (-22.0 - (i % 90) / 100.0) + ")}";
    }

    private static void load(Datastore ds, int from, int to) {
        Session s = session();
        for (int i = from; i < to; i++) {
            ds.execute("CREATE t:" + i + " CONTENT " + content(i), s, null);
        }
    }

    /** Mexidas determinísticas por faixa de ids: create novos, update e delete de antigos. */
    private static void churn(Datastore ds, int lo, int hi) {
        Session s = session();
        for (int i = lo; i < hi; i++) {
            ds.execute("CREATE t:" + (ROWS + i) + " CONTENT " + content(ROWS + i), s, null);
            ds.execute("UPDATE t:" + i + " MERGE {email: 'v" + i + "', city: 'cz'}", s, null);
            if (i % 5 == 0) {
                ds.execute("DELETE t:" + (i + 1), s, null);
            }
        }
    }

    private static void churnConcurrently(Datastore ds, int threads, int span) throws Exception {
        List<Thread> pool = new ArrayList<>();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            int lo = t * span;
            Thread th = new Thread(() -> {
                try {
                    churn(ds, lo, lo + span);
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            pool.add(th);
            th.start();
        }
        for (Thread th : pool) {
            th.join();
        }
        assertTrue(errors.isEmpty(), "escritas concorrentes falharam: " + errors);
    }

    private static Map<String, String> allKeys(Datastore ds) {
        KvBackend kv = ds.findDatabase("test", "dev").records();
        Map<String, String> out = new TreeMap<>();
        for (String key : kv.keysWithPrefix("")) {
            out.put(key, new String(kv.get(key).orElse(new byte[0]), java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    /** Chaves e valores que o índice acrescentou: tudo o que existe com ele e não existe sem ele. */
    private static Map<String, String> indexEntries(Datastore withIndex, Datastore baseline) {
        Map<String, String> base = allKeys(baseline);
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : allKeys(withIndex).entrySet()) {
            if (!base.containsKey(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        // marcadores de controle e de construção não fazem parte do índice
        out.keySet().removeIf(k -> k.startsWith("IXB|") || k.contains("!control") || k.startsWith("CTRL"));
        return out;
    }

    private static AxonValue indexInfo(Datastore ds, String name) {
        AxonValue info = ds.execute("INFO FOR TABLE t", session(), null);
        AxonValue root = info.isArray() ? info.asArray().get(0) : info;
        return root.asObject().get("indexes").asObject().get(name);
    }

    private static String state(Datastore ds, String name) {
        AxonValue ix = indexInfo(ds, name);
        return ix == null ? null : ix.asObject().get("state").asString();
    }

    private static int rows(AxonValue result) {
        return result.isArray() ? result.asArray().size() : 1;
    }

    private static String render(AxonValue v) {
        return com.axonbase.value.AxonJson.write(v);
    }

    private static void assertSameEntries(Map<String, String> expected, Map<String, String> actual) {
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            if (!e.getValue().equals(actual.get(e.getKey()))) {
                diff.add(e.getKey() + " esperado=" + e.getValue() + " obtido=" + actual.get(e.getKey()));
            }
        }
        for (String k : actual.keySet()) {
            if (!expected.containsKey(k)) {
                diff.add(k + " sobrou");
            }
        }
        assertTrue(diff.isEmpty(), diff.size() + " diferenças, primeiras: "
            + diff.subList(0, Math.min(6, diff.size())));
    }

    private void equivalence(String define, List<String> probes) throws Exception {
        equivalence(define, probes, 2, true);
    }

    /**
     * @param writers threads de escrita durante a construção (os contadores de busca já tinham perda de
     *                atualização entre escritores simultâneos, então o teste de SEARCH usa um só)
     * @param builds  se o tipo de índice tem algo a construir (o índice normal fica pronto na hora)
     */
    private void equivalence(String define, List<String> probes, int writers, boolean builds) throws Exception {
        // referência: índice criado antes da carga
        Datastore before = store(new MemoryBackend(), false);
        before.execute("DEFINE TABLE t SCHEMALESS", session(), null);
        before.execute("DEFINE ANALYZER a LOWERCASE", session(), null);
        before.execute(define, session(), null);
        load(before, 0, ROWS);
        churn(before, 0, 200);
        churn(before, 200, 400);

        // sem índice, para isolar as chaves que o índice acrescenta
        Datastore plain = store(new MemoryBackend(), false);
        plain.execute("DEFINE TABLE t SCHEMALESS", session(), null);
        plain.execute("DEFINE ANALYZER a LOWERCASE", session(), null);
        load(plain, 0, ROWS);
        churn(plain, 0, 200);
        churn(plain, 200, 400);

        // candidato: índice depois da carga, em segundo plano, com escritas durante a construção
        Datastore after = store(new MemoryBackend(), true);
        after.execute("DEFINE TABLE t SCHEMALESS", session(), null);
        after.execute("DEFINE ANALYZER a LOWERCASE", session(), null);
        load(after, 0, ROWS);
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
        after.execute(define, session(), null);
        if (builds) {
            assertTrue(started.await(30, TimeUnit.SECONDS));
        }
        // as escritas entram enquanto a construção está parada no meio
        churnConcurrently(after, writers, 400 / writers);
        go.countDown();
        assertTrue(IndexBuilder.awaitAll(60_000), "construção não terminou");

        String name = define.split(" ")[2];
        assertEquals("ready", state(after, name));
        assertSameEntries(indexEntries(before, plain), indexEntries(after, plain));
        for (String probe : probes) {
            assertEquals(render(before.execute(probe, session(), null)),
                render(after.execute(probe, session(), null)), probe);
        }
        assertFalse(allKeys(after).keySet().stream().anyMatch(k -> k.startsWith("IXB|")), "marcador sobrou");
    }

    @Test
    @DisplayName("UNIQUE em segundo plano com escrita concorrente é idêntico ao índice criado antes da carga")
    void uniqueEquivalence() throws Exception {
        equivalence("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE",
            List.of("SELECT * FROM t WHERE email = 'u1500'", "SELECT * FROM t WHERE email = 'v10'",
                "SELECT * FROM t WHERE email = 'u3100'", "SELECT * FROM t WHERE email = 'nada'"));
    }

    @Test
    @DisplayName("índice normal em segundo plano fica pronto e as consultas seguem iguais")
    void normalEquivalence() throws Exception {
        equivalence("DEFINE INDEX c ON TABLE t COLUMNS city",
            List.of("SELECT * FROM t WHERE city = 'c3' ORDER BY id", "SELECT count() FROM t GROUP ALL"), 2, false);
    }

    @Test
    @DisplayName("COLUMNAR em segundo plano é idêntico ao índice criado antes da carga")
    void columnarEquivalence() throws Exception {
        equivalence("DEFINE INDEX a ON TABLE t COLUMNS age COLUMNAR",
            List.of("SELECT count() FROM t GROUP ALL", "SELECT age, count() FROM t GROUP BY age ORDER BY age"));
    }

    @Test
    @DisplayName("GEO em segundo plano é idêntico ao índice criado antes da carga")
    void geoEquivalence() throws Exception {
        equivalence("DEFINE INDEX g ON TABLE t COLUMNS loc GEO",
            List.of("SELECT id FROM t WHERE geo::distance(loc, geometry::point(-43.5, -22.5)) < 20000 ORDER BY id"));
    }

    @Test
    @DisplayName("SEARCH em segundo plano é idêntico ao índice criado antes da carga")
    void searchEquivalence() throws Exception {
        equivalence("DEFINE INDEX s ON TABLE t COLUMNS body SEARCH ANALYZER a",
            List.of("SELECT id FROM t WHERE body @@ 'w3' ORDER BY id"), 1, true);
    }

    @Test
    @DisplayName("enquanto constrói, o índice aparece como building, tem progresso e a consulta não o usa")
    void buildingIsVisibleAndNotUsed() throws Exception {
        Datastore ds = store(new MemoryBackend(), true);
        ds.execute("DEFINE TABLE t SCHEMALESS", session(), null);
        load(ds, 0, ROWS);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        IndexBuilder.chunkHook(() -> {
            if (chunks.incrementAndGet() == 3) {
                paused.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ds.execute("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE", session(), null);
        assertTrue(paused.await(30, TimeUnit.SECONDS));

        assertEquals("building", state(ds, "e"));
        long processed = indexInfo(ds, "e").asObject().get("processed").asLong();
        assertTrue(processed > 0 && processed < ROWS, "progresso " + processed);
        // a consulta devolve o certo mesmo sem usar o índice (varredura completa)
        assertEquals(1, rows(ds.execute("SELECT * FROM t WHERE email = 'u2999'", session(), null)));

        go.countDown();
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertEquals("ready", state(ds, "e"));
        assertEquals(1, rows(ds.execute("SELECT * FROM t WHERE email = 'u2999'", session(), null)));
    }

    @Test
    @DisplayName("duplicata em UNIQUE aborta a construção sem deixar índice, entradas nem marcador")
    void duplicateAbortsCleanly() throws Exception {
        MemoryBackend backend = new MemoryBackend();
        Datastore ds = store(backend, true);
        ds.execute("DEFINE TABLE t SCHEMALESS", session(), null);
        load(ds, 0, ROWS);
        ds.execute("CREATE t:dup CONTENT {email: 'u1700'}", session(), null);
        Set<String> keysBefore = new TreeSet<>(allKeys(ds).keySet());

        ds.execute("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE", session(), null);
        assertTrue(IndexBuilder.awaitAll(60_000));

        assertEquals(null, state(ds, "e"), "índice parcial ficou visível");
        assertEquals(keysBefore, new TreeSet<>(allKeys(ds).keySet()), "sobrou efeito colateral");
        String failures = render(ds.execute("INFO FOR TABLE t", session(), null));
        assertTrue(failures.contains("index_failures"), failures);
        // a tabela segue aceitando o valor repetido, e o índice pode ser criado de novo depois de corrigir
        ds.execute("CREATE t:dup2 CONTENT {email: 'u1700'}", session(), null);
        ds.execute("DELETE t:dup", session(), null);
        ds.execute("DELETE t:dup2", session(), null);
        ds.execute("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE", session(), null);
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertEquals("ready", state(ds, "e"));
        assertThrows(AxonError.class, () -> ds.execute("CREATE t:z CONTENT {email: 'u5'}", session(), null));
    }

    @Test
    @DisplayName("duplicata criada por escrita concorrente durante a construção também aborta")
    void concurrentDuplicateAborts() throws Exception {
        Datastore ds = store(new MemoryBackend(), true);
        ds.execute("DEFINE TABLE t SCHEMALESS", session(), null);
        load(ds, 0, ROWS);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        IndexBuilder.chunkHook(() -> {
            if (chunks.incrementAndGet() == 2) {
                paused.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ds.execute("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE", session(), null);
        assertTrue(paused.await(30, TimeUnit.SECONDS));
        // linha ainda não varrida (id alto) ganha o email de outra linha também não varrida
        ds.execute("UPDATE t:2900 MERGE {email: 'u2800'}", session(), null);
        go.countDown();
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertEquals(null, state(ds, "e"));
        assertFalse(allKeys(ds).keySet().stream().anyMatch(k -> k.contains("!uidx") || k.startsWith("IXB|")));
    }

    @Test
    @DisplayName("reiniciar no meio da construção não deixa o índice pronto: ele é refeito até o fim")
    void restartMidBuildResumes() throws Exception {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = store(backend, true);
        first.execute("DEFINE TABLE t SCHEMALESS", session(), null);
        load(first, 0, ROWS);
        AtomicInteger chunks = new AtomicInteger();
        IndexBuilder.chunkHook(() -> {
            if (chunks.incrementAndGet() == 3) {
                throw new IndexBuilder.Crash();
            }
        });
        first.execute("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE", session(), null);
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertTrue(allKeys(first).keySet().stream().anyMatch(k -> k.startsWith("IXB|")), "sem marcador após a queda");

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        IndexBuilder.chunkHook(() -> {
            resumed.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Datastore second = new Datastore(backend);
        second.ensureDatabase("test", "dev");
        assertTrue(resumed.await(30, TimeUnit.SECONDS), "construção não foi retomada");
        assertEquals("building", state(second, "e"));
        assertEquals(1, rows(second.execute("SELECT * FROM t WHERE email = 'u10'", session(), null)));

        go.countDown();
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertEquals("ready", state(second, "e"));
        assertFalse(allKeys(second).keySet().stream().anyMatch(k -> k.startsWith("IXB|")));
        assertEquals((long) ROWS, allKeys(second).keySet().stream().filter(k -> k.contains("!uidx")).count());
        assertThrows(AxonError.class, () -> second.execute("CREATE t:z CONTENT {email: 'u5'}", session(), null));
        assertNotEquals(null, state(second, "e"));
    }

    @Test
    @DisplayName("tabela pequena segue síncrona: duplicata falha no próprio comando")
    void smallTablesStaySynchronous() {
        Datastore ds = store(new MemoryBackend(), false);
        ds.execute("CREATE t:1 CONTENT {email: 'a'}", session(), null);
        ds.execute("CREATE t:2 CONTENT {email: 'a'}", session(), null);
        assertThrows(AxonError.class,
            () -> ds.execute("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE", session(), null));
        assertEquals(null, state(ds, "e"));
    }

    @Test
    @DisplayName("DROP INDEX durante a construção interrompe e limpa")
    void dropDuringBuild() throws Exception {
        Datastore ds = store(new MemoryBackend(), true);
        ds.execute("DEFINE TABLE t SCHEMALESS", session(), null);
        load(ds, 0, ROWS);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger chunks = new AtomicInteger();
        IndexBuilder.chunkHook(() -> {
            if (chunks.incrementAndGet() == 2) {
                paused.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ds.execute("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE", session(), null);
        assertTrue(paused.await(30, TimeUnit.SECONDS));
        ds.execute("DROP INDEX e", session(), null);
        go.countDown();
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertEquals(null, state(ds, "e"));
        assertFalse(allKeys(ds).keySet().stream().anyMatch(k -> k.startsWith("IXB|")));
    }
}
