package com.axonbase.core.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Estado global de package com várias threads: sem perda de atualização e sem serializar funções puras. */
class ProgramsConcurrencyTest {

    private final Datastore ds = Datastore.memory();
    private final Session s0 = ProgramsTest.session(ds);

    @Test
    void contadorGlobalNaoPerdeAtualizacao() throws Exception {
        ds.execute("CREATE PACKAGE cont AS $$\nn = 0\ndef inc():\n    global n\n    tmp = n\n"
            + "    x = 0\n    for i in range(20):\n        x = x + i\n    n = tmp + 1\n    return n\n"
            + "def get():\n    return n\n$$", s0, null);
        int threads = 8;
        int each = 300;
        runParallel(threads, each, "RETURN cont::inc()");
        assertEquals(threads * each, ds.execute("RETURN cont::get()", s0, null).asLong());
    }

    @Test
    void listaGlobalMutadaSemDeclararGlobalTambemEhProtegida() throws Exception {
        ds.execute("CREATE PACKAGE lst AS $$\nitens = []\ndef add(v):\n    itens.append(v)\n    return len(itens)\n"
            + "def total():\n    return len(itens)\n$$", s0, null);
        runParallel(8, 250, "RETURN lst::add(1)");
        assertEquals(2000, ds.execute("RETURN lst::total()", s0, null).asLong());
    }

    @Test
    void funcoesPurasNaoTomamATravaEPackagesComEstadoTomam() throws Exception {
        // puro: só constante imutável. com_estado: contador global. Mesma função lenta nos dois.
        String lenta = "def lenta():\n    x = 0\n    for i in range(60000):\n        x = x + 1\n    return x\n";
        ds.execute("CREATE PACKAGE puro AS $$\nK = 3\n" + lenta + "$$", s0, null);
        ds.execute("CREATE PACKAGE com_estado AS $$\nn = 0\ndef inc():\n    global n\n    n = n + 1\n"
            + "    return n\n" + lenta + "$$", s0, null);
        ProgramDef puro = ds.programs().catalog("t", "d").get("puro");
        ProgramDef comEstado = ds.programs().catalog("t", "d").get("com_estado");

        assertEquals(false, observeLock("puro", puro), "package puro nao deve tomar a trava");
        assertEquals(true, observeLock("com_estado", comEstado), "package com estado deve tomar a trava");
    }

    /** Roda a função lenta em 4 threads e devolve se a trava do package foi vista tomada em algum momento. */
    private boolean observeLock(String pkg, ProgramDef def) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                fs.add(pool.submit(() -> {
                    Session s = ProgramsTest.session(ds);
                    go.await();
                    for (int i = 0; i < 20; i++) {
                        assertEquals(60000, ds.execute("RETURN " + pkg + "::lenta()", s, null).asLong());
                    }
                    return null;
                }));
            }
            go.countDown();
            boolean seen = false;
            while (!fs.stream().allMatch(Future::isDone)) {
                seen |= def.stateLock().isLocked();
            }
            for (Future<?> f : fs) {
                f.get();
            }
            return seen;
        } finally {
            pool.shutdownNow();
        }
    }

    private void runParallel(int threads, int each, String sql) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                fs.add(pool.submit(() -> {
                    Session s = ProgramsTest.session(ds);
                    go.await();
                    for (int i = 0; i < each; i++) {
                        ds.execute(sql, s, null);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
