package com.axonbase.core.qos;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QoS: motor com prioridade ligada")
@Timeout(120)
class QosDatastoreTest {

    private static final int ROWS = 15_000;

    private static Session session() {
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        return session;
    }

    private static Datastore loaded() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        Session session = session();
        ds.beginSession(session);
        for (int i = 0; i < ROWS; i++) {
            ds.execute("CREATE item:" + i + " CONTENT { grupo: " + (i % 7) + " }", session, null);
        }
        ds.commitSession(session);
        return ds;
    }

    private static WorkloadGovernor governor(Map<String, String> env) {
        return new WorkloadGovernor(QosSettings.from(env::get, 4, 112));
    }

    @Test
    @DisplayName("com a vaga analítica ocupada, leituras por id e escritas continuam passando")
    void transactionsIgnoreFullAnalyticalQueue() throws Exception {
        Datastore ds = loaded();
        WorkloadGovernor governor = governor(Map.of("AXON_QOS", "auto", "AXON_QOS_ANALYTICAL_CONCURRENCY", "1",
            "AXON_QOS_ANALYTICAL_QUEUE", "0"));
        ds.workloadGovernor(governor);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try (var ticket = governor.admit("SELECT * FROM item", session())) {
                holding.countDown();
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(holding.await(10, TimeUnit.SECONDS));

        AxonError refused = assertThrows(AxonError.class,
            () -> ds.execute("SELECT grupo, count() FROM item GROUP BY grupo", session(), null));
        assertEquals(QosCodes.REJECTED, refused.code());
        ds.execute("CREATE item:novo CONTENT { grupo: 1 }", session(), null);
        assertTrue(AxonJson.write(ds.execute("SELECT * FROM item:novo", session(), null)).contains("grupo"));

        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertEquals(7, ds.execute("SELECT grupo, count() FROM item GROUP BY grupo", session(), null)
            .asArray().size());
    }

    @Test
    @DisplayName("uma varredura com filtro é rebaixada e devolve o mesmo resultado")
    void scanIsDemotedWithSameResult() {
        Datastore ds = loaded();
        String sql = "SELECT * FROM item WHERE grupo = 3";
        int expected = ds.execute(sql, session(), null).asArray().size();
        WorkloadGovernor governor = governor(Map.of("AXON_QOS", "auto", "AXON_QOS_SCAN_THRESHOLD", "1000"));
        ds.workloadGovernor(governor);

        int actual = ds.execute(sql, session(), null).asArray().size();

        assertEquals(expected, actual);
        assertEquals(1, governor.stats().demoted());
        assertEquals(0, governor.stats().analyticalRunning());
        assertEquals(0, governor.stats().transactionalRunning());
    }

    @Test
    @DisplayName("sem governador nada muda")
    void noGovernor() {
        Datastore ds = loaded();
        ds.workloadGovernor(null);

        assertEquals(ROWS, ds.execute("SELECT * FROM item", session(), null).asArray().size());
    }
}
