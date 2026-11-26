package com.axonbase.core.qos;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.QueryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QoS: governador de carga")
@Timeout(30)
class WorkloadGovernorTest {

    private static final long MILLI = 1_000_000L;
    private static final String SCAN = "SELECT * FROM item";
    private static final String FILTER = "SELECT * FROM item WHERE x = 1";
    private static final String WRITE = "CREATE item CONTENT { x: 1 }";

    private final AtomicLong now = new AtomicLong(1_000 * MILLI);
    private final List<Long> pauses = new ArrayList<>();

    private WorkloadGovernor governor(Map<String, String> env) {
        QosSettings settings = QosSettings.from(env::get, 4, 112);
        return new WorkloadGovernor(settings, now::get, nanos -> {
            pauses.add(nanos);
            now.addAndGet(nanos);
        });
    }

    private static Map<String, String> auto(String... extra) {
        java.util.HashMap<String, String> env = new java.util.HashMap<>(Map.of("AXON_QOS", "auto",
            "AXON_QOS_ANALYTICAL_CONCURRENCY", "1", "AXON_QOS_ANALYTICAL_QUEUE", "0",
            "AXON_QOS_ANALYTICAL_CPU_PERCENT", "25"));
        for (int i = 0; i + 1 < extra.length; i += 2) {
            env.put(extra[i], extra[i + 1]);
        }
        return env;
    }

    private static Session session() {
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        return session;
    }

    /** Simula o motor: {@code accesses} acessos ao armazenamento, cada um levando {@code each} nanossegundos. */
    private void access(int accesses, long each) {
        for (int i = 0; i < accesses; i++) {
            now.addAndGet(each);
            QueryContext.checkCancelled();
        }
    }

    /** Mantém uma consulta transacional aberta noutra thread, como um cliente faria. */
    private static AutoCloseable transactionalInBackground(WorkloadGovernor governor) throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try (var ticket = governor.admit(WRITE, session())) {
                admitted.countDown();
                done.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(admitted.await(10, TimeUnit.SECONDS));
        return () -> {
            done.countDown();
            running.get(10, TimeUnit.SECONDS);
        };
    }

    @Test
    @DisplayName("desligado não classifica nem cria contexto")
    void offDoesNothing() {
        WorkloadGovernor governor = governor(Map.of());

        try (var ticket = governor.admit(SCAN, session())) {
            assertNull(QueryContext.current());
        }

        assertEquals(0, governor.stats().analyticalAdmitted());
        assertEquals(0, governor.stats().transactionalAdmitted());
    }

    @Test
    @DisplayName("a analítica sem vaga é recusada e a escrita passa")
    void analyticalNeedsSlotAndWritesDoNot() throws Exception {
        WorkloadGovernor governor = governor(auto());
        try (var first = governor.admit(SCAN, session())) {
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> {
                try (var ticket = governor.admit(SCAN, session())) {
                    return 0;
                } catch (AxonError refused) {
                    return refused.code();
                }
            });
            assertEquals(QosCodes.REJECTED, second.get(10, TimeUnit.SECONDS));
            try (var writing = transactionalInBackground(governor)) {
                assertEquals(1, governor.stats().transactionalRunning());
            }
        }

        QosStats stats = governor.stats();
        assertEquals(0, stats.analyticalRunning());
        assertEquals(1, stats.analyticalAdmitted());
        assertEquals(1, stats.rejectedQueueFull());
        assertEquals(1, stats.analytical().count());
        assertEquals(1, stats.transactional().count());
        assertNull(QueryContext.current(), "o contexto criado para a admissão precisa sair");
    }

    @Test
    @DisplayName("consulta aninhada não pede outra vaga")
    void nestedQueryIsNotAdmittedAgain() {
        WorkloadGovernor governor = governor(auto());

        try (var outer = governor.admit(SCAN, session()); var inner = governor.admit(SCAN, session())) {
            assertEquals(1, governor.stats().analyticalRunning());
        }

        assertEquals(0, governor.stats().analyticalRunning());
        try (var next = governor.admit(SCAN, session())) {
            assertEquals(2, governor.stats().analyticalAdmitted());
        }
    }

    @Test
    @DisplayName("sem trabalho transacional a analítica usa a máquina inteira")
    void noThrottleWithoutTransactions() {
        WorkloadGovernor governor = governor(auto());

        try (var ticket = governor.admit(SCAN, session())) {
            access(100 * WorkloadGovernor.STRIDE, MILLI);
        }

        assertTrue(pauses.isEmpty(), "pausas: " + pauses.size());
    }

    @Test
    @DisplayName("com trabalho transacional a analítica fica perto do orçamento")
    void throttlesToBudget() throws Exception {
        WorkloadGovernor governor = governor(auto());
        try (var transactional = transactionalInBackground(governor);
             var ticket = governor.admit(SCAN, session())) {
            long started = now.get();
            long executed = 400L * WorkloadGovernor.STRIDE * MILLI / 10;

            access(400 * WorkloadGovernor.STRIDE, MILLI / 10);

            // 25% de 4 núcleos é um núcleo: uma thread não passa disso, e quase não pausa
            long elapsed = now.get() - started;
            assertTrue(elapsed >= executed && elapsed < executed * 2, "levou " + elapsed / MILLI + " ms");
        }
    }

    @Test
    @DisplayName("acima do orçamento há pausas")
    void pausesWhenOverBudget() throws Exception {
        WorkloadGovernor governor = governor(auto("AXON_QOS_ANALYTICAL_CPU_PERCENT", "5"));
        try (var transactional = transactionalInBackground(governor);
             var ticket = governor.admit(SCAN, session())) {
            access(100 * WorkloadGovernor.STRIDE, MILLI / 10);
        }

        assertTrue(!pauses.isEmpty() && governor.stats().throttleNanos() > 0);
    }

    @Test
    @DisplayName("leitura que passa do limite de acessos é rebaixada")
    void demotesLongRead() {
        WorkloadGovernor governor = governor(auto("AXON_QOS_SCAN_THRESHOLD", "1000"));

        try (var ticket = governor.admit(FILTER, session())) {
            access(500, 1);
            assertEquals(1, governor.stats().transactionalRunning());
            access(1_000, 1);
            assertEquals(0, governor.stats().transactionalRunning());
            assertEquals(1, governor.stats().analyticalRunning());
        }

        QosStats stats = governor.stats();
        assertEquals(1, stats.demoted());
        assertEquals(0, stats.analyticalRunning());
        assertEquals(1, stats.analytical().count());
    }

    @Test
    @DisplayName("escrita longa nunca é rebaixada nem pausada")
    void writesAreNeverDemoted() {
        WorkloadGovernor governor = governor(auto("AXON_QOS_SCAN_THRESHOLD", "10"));

        try (var ticket = governor.admit("UPDATE item SET x = 2", session())) {
            access(10_000, MILLI);
        }

        assertEquals(0, governor.stats().demoted());
        assertTrue(pauses.isEmpty());
    }

    @Test
    @DisplayName("analítica que passa do tempo máximo é interrompida e devolve a vaga")
    void timeBudget() {
        WorkloadGovernor governor = governor(auto("AXON_QOS_ANALYTICAL_TIMEOUT_MS", "10"));

        AxonError over = assertThrows(AxonError.class, () -> {
            try (var ticket = governor.admit(SCAN, session())) {
                access(10 * WorkloadGovernor.STRIDE, MILLI);
            }
        });

        assertEquals(QosCodes.BUDGET_EXCEEDED, over.code());
        assertEquals(1, governor.stats().budgetExceeded());
        assertEquals(0, governor.stats().analyticalRunning());
        assertNull(QueryContext.current());
    }

    @Test
    @DisplayName("usa o pedido guardado no contexto da consulta")
    void honoursRequestedWorkload() {
        WorkloadGovernor governor = governor(auto());
        QueryContext.Handle handle = new QueryContext.Handle("q1");
        handle.requestWorkload("analytical");
        QueryContext.Handle previous = QueryContext.attach(handle);
        try (var ticket = governor.admit("SELECT * FROM item:1", session())) {
            assertEquals(1, governor.stats().analyticalRunning());
        } finally {
            QueryContext.detach(previous);
        }
        assertNull(handle.pacer());
    }
}
