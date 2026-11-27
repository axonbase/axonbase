package com.axonbase.core.qos;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.QueryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QoS: vagas e fila das analíticas")
@Timeout(30)
class AnalyticalSlotsTest {

    private static QueryContext.Handle handle() {
        return new QueryContext.Handle("q");
    }

    private static void awaitQueued(AnalyticalSlots slots, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (slots.queued() != expected) {
            assertTrue(System.nanoTime() < deadline, "fila em " + slots.queued());
            Thread.sleep(5);
        }
    }

    @Test
    @DisplayName("sem vaga e sem fila, recusa na hora")
    void rejectsWhenQueueIsFull() {
        AnalyticalSlots slots = new AnalyticalSlots(1, 0, 1_000);
        slots.acquire(handle());

        AxonError refused = assertThrows(AxonError.class, () -> slots.acquire(handle()));

        assertEquals(QosCodes.REJECTED, refused.code());
        assertEquals(1, slots.rejectedQueueFull());
        assertEquals(1, slots.running());
        assertEquals(0, slots.queued());
    }

    @Test
    @DisplayName("a fila espera até o prazo e recusa")
    void rejectsAfterTimeout() {
        AnalyticalSlots slots = new AnalyticalSlots(1, 1, 50);
        slots.acquire(handle());

        AxonError refused = assertThrows(AxonError.class, () -> slots.acquire(handle()));

        assertEquals(QosCodes.REJECTED, refused.code());
        assertEquals(1, slots.rejectedTimeout());
        assertEquals(0, slots.queued());
        assertTrue(slots.waitNanos() >= TimeUnit.MILLISECONDS.toNanos(40));
    }

    @Test
    @DisplayName("quem espera entra quando a vaga é liberada")
    void waiterGetsReleasedSlot() throws Exception {
        AnalyticalSlots slots = new AnalyticalSlots(1, 1, 20_000);
        slots.acquire(handle());
        CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> slots.acquire(handle()));
        awaitQueued(slots, 1);

        slots.release();

        waiter.get(10, TimeUnit.SECONDS);
        assertEquals(1, slots.running());
    }

    @Test
    @DisplayName("cancelar uma consulta na fila a tira da fila sem gastar vaga")
    void cancelLeavesQueue() throws Exception {
        AnalyticalSlots slots = new AnalyticalSlots(1, 1, 20_000);
        slots.acquire(handle());
        QueryContext.Handle queued = handle();
        boolean[] interruptedAfter = new boolean[1];
        CompletableFuture<Integer> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                slots.acquire(queued);
                return 0;
            } catch (AxonError cancelled) {
                interruptedAfter[0] = Thread.currentThread().isInterrupted();
                return cancelled.code();
            }
        });
        awaitQueued(slots, 1);

        queued.cancel("teste");

        assertEquals(QueryContext.CANCELLED, waiter.get(10, TimeUnit.SECONDS));
        assertFalse(interruptedAfter[0], "a interrupção usada para acordar não pode vazar");
        assertEquals(1, slots.running());
        assertEquals(0, slots.queued());
    }
}
