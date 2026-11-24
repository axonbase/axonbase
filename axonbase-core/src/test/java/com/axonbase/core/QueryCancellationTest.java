package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.QueryContext;
import com.axonbase.core.storage.ConflictStats;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.Transaction;
import com.axonbase.core.storage.VersionConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cancelamento cooperativo e conflitos de versão")
class QueryCancellationTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static Datastore store(int rows) {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session();
        ds.execute("DEFINE TABLE t SCHEMALESS", s, null);
        for (int base = 0; base < rows; base += 500) {
            StringBuilder sb = new StringBuilder("INSERT INTO t [");
            for (int i = base; i < Math.min(rows, base + 500); i++) {
                if (i > base) sb.append(',');
                sb.append("{id: ").append(i).append(", v: ").append(i % 10).append("}");
            }
            ds.execute(sb.append(']').toString(), s, null);
        }
        return ds;
    }

    @Test
    @DisplayName("consulta já cancelada para no primeiro acesso ao armazenamento")
    void cancelledQueryStopsAtFirstAccess() {
        Datastore ds = store(100);
        QueryContext.Handle h = new QueryContext.Handle("q1");
        h.cancel("admin");
        QueryContext.Handle prev = QueryContext.attach(h);
        try {
            AxonError e = assertThrows(AxonError.class, () -> ds.execute("SELECT * FROM t", session(), null));
            assertEquals(QueryContext.CANCELLED, e.code());
        } finally {
            QueryContext.detach(prev);
        }
    }

    @Test
    @DisplayName("sem handle ligado a consulta roda normalmente")
    void withoutHandleRunsNormally() {
        Datastore ds = store(100);
        assertNotNull(ds.execute("SELECT * FROM t", session(), null));
    }

    @Test
    @DisplayName("cancelar de outra thread interrompe uma consulta longa antes de ela terminar")
    void cancelFromAnotherThreadStopsLongScan() throws Exception {
        Datastore ds = store(20000);
        QueryContext.Handle h = new QueryContext.Handle("q2");
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> finished = new AtomicReference<>(false);
        Thread worker = new Thread(() -> {
            QueryContext.Handle prev = QueryContext.attach(h);
            try {
                started.countDown();
                // varredura sem índice repetida: dura bem mais do que o tempo até o cancelamento
                for (int i = 0; i < 200; i++) {
                    ds.execute("SELECT * FROM t WHERE v = 5", session(), null);
                }
                finished.set(true);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                QueryContext.detach(prev);
            }
        });
        worker.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        h.cancel("admin");
        worker.join(10_000);

        assertTrue(!worker.isAlive(), "a thread deve terminar");
        assertTrue(failure.get() instanceof AxonError, "esperava AxonError, veio " + failure.get());
        assertEquals(QueryContext.CANCELLED, ((AxonError) failure.get()).code());
        assertTrue(!finished.get());
        assertEquals("admin", h.cancelledBy());
    }

    @Test
    @DisplayName("conflito de versão é contado por tabela")
    void conflictsAreCountedPerTable() {
        long before = ConflictStats.total();
        MemoryBackend backend = new MemoryBackend();
        String key = "test\u0000dev\u0000contas\u0000contas:1";
        backend.put(key, new byte[] {1});

        Transaction tx = new Transaction(backend);
        tx.get(key);
        backend.put(key, new byte[] {2});
        tx.put(key, new byte[] {3});

        assertThrows(VersionConflictException.class, tx::commit);
        assertEquals(before + 1, ConflictStats.total());
        assertTrue(ConflictStats.byTarget().stream().anyMatch(e -> e.target().equals("test/dev/contas") && e.count() >= 1));
    }

    @Test
    @DisplayName("transação expõe quando abriu e quantas escritas guarda")
    void transactionExposesAgeAndStagedCounts() {
        MemoryBackend backend = new MemoryBackend();
        backend.put("c", new byte[] {9});
        Transaction tx = new Transaction(backend);
        long opened = tx.openedAtMs();
        tx.put("a", new byte[] {1});
        tx.put("b", new byte[] {1});
        tx.delete("c");
        assertTrue(opened > 0);
        assertEquals(2, tx.stagedWriteCount());
        assertEquals(1, tx.stagedDeleteCount());
    }
}
