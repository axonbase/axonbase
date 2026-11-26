package com.axonbase.core.cluster;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Agrupa os pedidos de ReadIndex de um seguidor: enquanto uma consulta ao líder está em curso, as leituras que
 * chegam esperam a próxima, e uma única ida ao líder atende todas. Cada leitura só aceita uma consulta
 * iniciada depois dela, que é o que garante a linearizabilidade.
 */
final class ReadIndexBatcher {

    /** Índice devolvido pelo líder e o instante local em que a consulta começou. */
    record Round(long index, long startedNanos) {
    }

    private final LongSupplier askLeader;
    private CompletableFuture<Round> next;
    private boolean running;

    ReadIndexBatcher(LongSupplier askLeader) {
        this.askLeader = askLeader;
    }

    Round await(long timeoutMillis) {
        CompletableFuture<Round> round;
        synchronized (this) {
            if (next == null) {
                next = new CompletableFuture<>();
            }
            round = next;
            if (!running) {
                running = true;
                Thread.ofVirtual().name("raft-read-index").start(this::run);
            }
        }
        try {
            return round.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new StaleReadException(String.valueOf(failed.getCause()));
        } catch (TimeoutException late) {
            throw new StaleReadException("o líder não respondeu ao pedido de leitura a tempo");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StaleReadException("leitura interrompida");
        }
    }

    private void run() {
        while (true) {
            CompletableFuture<Round> round;
            synchronized (this) {
                round = next;
                next = null;
                if (round == null) {
                    running = false;
                    return;
                }
            }
            try {
                long started = System.nanoTime();
                round.complete(new Round(askLeader.getAsLong(), started));
            } catch (RuntimeException failure) {
                round.completeExceptionally(failure);
            }
        }
    }
}
