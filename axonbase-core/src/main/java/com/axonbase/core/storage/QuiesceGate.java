package com.axonbase.core.storage;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Portão que deixa uma operação rara (trocar a base inteira) esperar as operações em curso terminarem e
 * segurar as novas, sem custo relevante no caminho quente. Cada operação faz {@link #enter()} e
 * {@link #exit()}; os contadores ficam repartidos em faixas por thread (uma linha de cache por faixa), então
 * threads diferentes quase nunca disputam o mesmo contador.
 *
 * <p>Reentrante por thread: uma operação que já está dentro (por exemplo, um visitante de varredura que lê
 * outra chave) passa mesmo com o portão fechado, senão o fechamento esperaria por ela para sempre.</p>
 */
final class QuiesceGate {

    private static final int STRIPES = 64;
    private static final int PAD = 16; // 16 longs = 128 bytes: um contador por linha de cache

    private final AtomicLongArray counts = new AtomicLongArray(STRIPES * PAD);
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);
    private final Object monitor = new Object();
    private volatile boolean closed;

    private static int slot() {
        return (int) (Thread.currentThread().threadId() & (STRIPES - 1)) * PAD;
    }

    void enter() {
        int[] d = depth.get();
        if (d[0]++ > 0) {
            return;
        }
        int slot = slot();
        while (true) {
            counts.incrementAndGet(slot);
            if (!closed) {
                return;
            }
            counts.decrementAndGet(slot);
            awaitOpen();
        }
    }

    void exit() {
        int[] d = depth.get();
        if (--d[0] > 0) {
            return;
        }
        counts.decrementAndGet(slot());
    }

    private void awaitOpen() {
        synchronized (monitor) {
            while (closed) {
                try {
                    monitor.wait(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Fecha o portão e espera as operações em curso terminarem. Devolve {@code false} (e reabre) se passarem
     * {@code timeoutMs} sem esvaziar, por exemplo por causa de uma varredura que não acaba.
     */
    boolean quiesce(long timeoutMs) {
        closed = true;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (active() > 0) {
            if (System.nanoTime() > deadline) {
                resume();
                return false;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                resume();
                return false;
            }
        }
        return true;
    }

    void resume() {
        synchronized (monitor) {
            closed = false;
            monitor.notifyAll();
        }
    }

    private long active() {
        long sum = 0;
        for (int i = 0; i < STRIPES; i++) {
            sum += counts.get(i * PAD);
        }
        return sum;
    }
}
