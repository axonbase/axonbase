package com.axonbase.core.qos;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.engine.QueryContext;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** As vagas das consultas analíticas, com uma fila de espera limitada em tamanho e em tempo. */
final class AnalyticalSlots {

    private final Semaphore permits;
    private final int concurrency;
    private final int queueLimit;
    private final long timeoutMillis;
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong queueFull = new AtomicLong();
    private final AtomicLong queueTimeout = new AtomicLong();
    private final LongAdder waitNanos = new LongAdder();

    AnalyticalSlots(int concurrency, int queueLimit, long timeoutMillis) {
        this.concurrency = concurrency;
        this.queueLimit = queueLimit;
        this.timeoutMillis = timeoutMillis;
        this.permits = new Semaphore(concurrency, true);
    }

    /**
     * Ocupa uma vaga, esperando na fila se preciso.
     *
     * @throws AxonError {@link QosCodes#REJECTED} sem vaga, ou {@link QueryContext#CANCELLED} se a consulta foi
     *                   cancelada na fila. Nada foi executado em nenhum dos casos.
     */
    void acquire(QueryContext.Handle handle) {
        try {
            if (permits.tryAcquire(0, TimeUnit.NANOSECONDS)) {
                return;
            }
        } catch (InterruptedException interrupted) {
            throw interrupted(handle);
        }
        if (queued.incrementAndGet() > queueLimit) {
            queued.decrementAndGet();
            queueFull.incrementAndGet();
            throw new AxonError(QosCodes.REJECTED, Messages.get("qos_queue_full"));
        }
        waitInQueue(handle);
    }

    private void waitInQueue(QueryContext.Handle handle) {
        long started = System.nanoTime();
        Thread waiter = Thread.currentThread();
        Runnable wake = waiter::interrupt;
        boolean acquired = false;
        try {
            handle.onCancel(wake);
            acquired = permits.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            throw interrupted(handle);
        } finally {
            handle.removeOnCancel(wake);
            queued.decrementAndGet();
            waitNanos.add(System.nanoTime() - started);
        }
        if (handle.cancelled()) {
            // o aviso de cancelamento pode ter chegado junto com a vaga
            Thread.interrupted();
            if (acquired) {
                permits.release();
            }
            throw new AxonError(QueryContext.CANCELLED, Messages.get("query_cancelled"));
        }
        if (!acquired) {
            queueTimeout.incrementAndGet();
            throw new AxonError(QosCodes.REJECTED, Messages.get("qos_queue_timeout", timeoutMillis));
        }
    }

    private static AxonError interrupted(QueryContext.Handle handle) {
        if (!handle.cancelled()) {
            Thread.currentThread().interrupt();
        }
        return new AxonError(QueryContext.CANCELLED, Messages.get("query_cancelled"));
    }

    void release() {
        permits.release();
    }

    int running() {
        return concurrency - permits.availablePermits();
    }

    int queued() {
        return queued.get();
    }

    long rejectedQueueFull() {
        return queueFull.get();
    }

    long rejectedTimeout() {
        return queueTimeout.get();
    }

    long waitNanos() {
        return waitNanos.sum();
    }
}
