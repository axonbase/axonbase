package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.QueryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Os caminhos novos de agregação e índice respeitam o cancelamento")
class AqlCancellationTest {

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
        ds.execute("DEFINE INDEX t_pos ON TABLE t COLUMNS pos GEO", s, null);
        for (int base = 0; base < rows; base += 500) {
            StringBuilder sb = new StringBuilder("INSERT INTO t [");
            for (int i = base; i < Math.min(rows, base + 500); i++) {
                if (i > base) sb.append(',');
                sb.append("{v: ").append(i % 10).append(", pos: {type: 'Point', coordinates: [")
                    .append(-46.65 + (i % 100) * 0.0001).append(", -23.55]}}");
            }
            ds.execute(sb.append(']').toString(), s, null);
        }
        ds.execute("INSERT INTO area [{geo: {type: 'Polygon', coordinates: [[[-46.66,-23.56],[-46.63,-23.56],[-46.63,-23.54],[-46.66,-23.54],[-46.66,-23.56]]]}}]", s, null);
        return ds;
    }

    private static void assertCancelled(Datastore ds, String sql) {
        QueryContext.Handle h = new QueryContext.Handle("q");
        h.cancel("admin");
        QueryContext.Handle previous = QueryContext.attach(h);
        try {
            AxonError e = assertThrows(AxonError.class, () -> ds.execute(sql, session(), null), sql);
            assertEquals(QueryContext.CANCELLED, e.code(), sql);
        } finally {
            QueryContext.detach(previous);
        }
    }

    @Test
    @DisplayName("consulta já cancelada para em agregação, GROUP BY, geo::contains indexado e subquery correlacionada")
    void cancelledAggregationsStop() {
        Datastore ds = store(300);
        assertCancelled(ds, "SELECT count() AS n FROM t GROUP ALL");
        assertCancelled(ds, "SELECT v, count() AS n FROM t GROUP BY v ORDER BY n DESC");
        assertCancelled(ds, "SELECT * FROM t WHERE geo::contains({type: 'Polygon', coordinates: [[[-46.66,-23.56],[-46.63,-23.56],[-46.63,-23.54],[-46.66,-23.54],[-46.66,-23.56]]]}, pos)");
        assertCancelled(ds, "SELECT (SELECT VALUE count() FROM t WHERE geo::contains($parent.geo, pos)) AS n FROM area");
    }

    @Test
    @DisplayName("cancelar de outra thread interrompe uma agregação longa")
    void cancelFromAnotherThreadStopsLongAggregation() throws Exception {
        Datastore ds = store(20000);
        QueryContext.Handle h = new QueryContext.Handle("q2");
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> finished = new AtomicReference<>(false);
        Thread worker = new Thread(() -> {
            QueryContext.Handle prev = QueryContext.attach(h);
            try {
                started.countDown();
                for (int i = 0; i < 200; i++) {
                    ds.execute("SELECT v, count() AS n, count(DISTINCT v) AS d FROM t WHERE v >= 0 GROUP BY v", session(), null);
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
    }
}
