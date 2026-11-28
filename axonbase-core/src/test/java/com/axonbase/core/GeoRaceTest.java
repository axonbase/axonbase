package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.axonbase.core.GeoTestData.INDEX;
import static com.axonbase.core.GeoTestData.insert;
import static com.axonbase.core.GeoTestData.ns;
import static com.axonbase.core.GeoTestData.point;
import static com.axonbase.core.GeoTestData.run;
import static com.axonbase.core.GeoTestData.sortedNs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Índice GEO com escritas replicadas no meio da leitura")
class GeoRaceTest {

    private static final String CENTER = point(10, 10);
    private static final String FAR = point(20, 20);

    private final HookBackend backend = new HookBackend();

    private Datastore seeded() {
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "dev");
        run(ds, "DEFINE TABLE g SCHEMALESS; DEFINE TABLE h SCHEMALESS; " + INDEX);
        for (int i = 0; i < 30; i++) {
            insert(ds, i, new double[] {10, 10 + i * 0.001});
        }
        return ds;
    }

    private static String recordKey(int n) {
        return "test\u0000dev\u0000g\u0000n" + n;
    }

    @Test
    @DisplayName("registro que muda enquanto o seguidor constrói o índice é reindexado")
    void changeDuringBuildIsReindexed() throws InterruptedException {
        Datastore ds = seeded();
        String key = recordKey(5);
        byte[] before = backend.inner.get(key).orElseThrow();
        run(ds, "UPDATE g:5 SET pos = " + FAR + "; UPDATE h:5 SET pos = " + FAR);
        byte[] moved = backend.inner.get(key).orElseThrow();
        backend.inner.put(key, before);
        backend.inner.delete("SHV|test|dev|g|g_pos");
        AtomicBoolean once = new AtomicBoolean();
        // o consenso aplica o lote sem o indexLock: a leitura devolve o valor antigo e o novo chega logo depois
        backend.afterGet = k -> {
            if (k.equals(key) && Thread.currentThread().getName().equals("axon-index-build")
                && once.compareAndSet(false, true)) {
                backend.inner.put(key, moved);
            }
        };
        Datastore reopened = new Datastore(backend);
        assertTrue(IndexBuilder.awaitAll(60_000));
        assertTrue(once.get(), "a construção não leu o registro");
        for (String center : List.of(CENTER, FAR)) {
            String where = " WHERE geo::distance(pos, " + center + ") <= 5000";
            assertEquals(sortedNs(reopened, "SELECT n FROM h" + where), sortedNs(reopened, "SELECT n FROM g" + where));
        }
        assertEquals(List.of(5L), ns(reopened, "SELECT n FROM g WHERE geo::distance(pos, " + FAR + ") <= 5"));
    }

    @Test
    @DisplayName("quem se move entre a leitura da entrada e a do documento continua entre os mais próximos")
    void recordMovingDuringNearestIsNotLost() {
        Datastore ds = seeded();
        String key = recordKey(0);
        run(ds, "UPDATE g:0 SET pos = " + point(10, 10.00001));
        byte[] moved = backend.inner.get(key).orElseThrow();
        run(ds, "UPDATE g:0 SET pos = " + CENTER);
        AtomicBoolean once = new AtomicBoolean();
        backend.beforeGet = k -> {
            if (k.equals(key) && once.compareAndSet(false, true)) {
                backend.inner.put(key, moved);
            }
        };
        List<Long> nearest = ns(ds, "SELECT n FROM g ORDER BY geo::distance(pos, " + CENTER + ") LIMIT 3");
        assertTrue(once.get());
        assertEquals(List.of(0L, 1L, 2L), nearest);
    }
}
