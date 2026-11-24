package com.axonbase.core.engine;

import com.axonbase.common.AxonError;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Varredura de um prefixo do armazenamento com o trabalho pesado (ler, decodificar e avaliar cada registro) em várias
 * threads. A thread da consulta percorre as chaves em ordem, que é barato, e entrega lotes de chaves a um pool com
 * tantas threads quanto núcleos. Cada lote vira um acumulador próprio, e os acumuladores são fundidos <b>na ordem
 * dos lotes</b>, então o resultado é o mesmo de uma varredura sequencial (a ordem de chegada dos grupos inclusive).
 *
 * <p>Os primeiros {@value #SEQUENTIAL_HEAD} registros são consumidos na própria thread: tabelas pequenas nunca
 * pagam pelo paralelismo. O cancelamento da consulta ({@link QueryContext}) vale nas threads do pool.</p>
 */
final class ParallelScan {

    /** Registros lidos na thread da consulta antes de qualquer paralelismo. */
    static final int SEQUENTIAL_HEAD = 10_000;
    private static final int BATCH = 2_048;
    /** Lotes entregues ao pool desde o início do processo (para testes e diagnóstico). */
    static final java.util.concurrent.atomic.AtomicLong BATCHES = new java.util.concurrent.atomic.AtomicLong();

    private ParallelScan() {
    }

    /** Graus de paralelismo: os núcleos disponíveis. */
    static int degree() {
        return Runtime.getRuntime().availableProcessors();
    }

    private static final class Pool {
        private static final AtomicInteger COUNTER = new AtomicInteger();
        static final ExecutorService INSTANCE = Executors.newFixedThreadPool(Math.max(2, degree()), runnable -> {
            Thread t = new Thread(runnable, "axon-scan-" + COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        private Pool() {
        }
    }

    /**
     * Percorre as chaves entregues por {@code scan}; {@code consume} soma uma chave num acumulador, {@code merge} funde o segundo no primeiro.
     * {@code head} recebe as primeiras chaves e, no fim, o resultado de tudo.
     */
    static <A> void run(Consumer<Predicate<String>> scan, A head, Supplier<A> fresh,
                        BiConsumer<A, String> consume, BiConsumer<A, A> merge) {
        if (degree() < 2) {
            scan.accept(key -> {
                QueryContext.checkCancelled();
                consume.accept(head, key);
                return true;
            });
            return;
        }
        QueryContext.Handle handle = QueryContext.current();
        ArrayDeque<Future<A>> pending = new ArrayDeque<>();
        int maxInFlight = 2 * degree();
        long[] seen = {0};
        List<List<String>> current = new ArrayList<>();
        current.add(new ArrayList<>(BATCH));
        try {
            scan.accept(key -> {
                QueryContext.checkCancelled();
                if (seen[0]++ < SEQUENTIAL_HEAD) {
                    consume.accept(head, key);
                    return true;
                }
                List<String> batch = current.get(0);
                batch.add(key);
                if (batch.size() >= BATCH) {
                    pending.add(submit(batch, handle, fresh, consume));
                    current.set(0, new ArrayList<>(BATCH));
                    while (pending.size() >= maxInFlight) {
                        merge.accept(head, await(pending.poll()));
                    }
                }
                return true;
            });
            if (!current.get(0).isEmpty()) {
                pending.add(submit(current.get(0), handle, fresh, consume));
            }
            while (!pending.isEmpty()) {
                merge.accept(head, await(pending.poll()));
            }
        } catch (RuntimeException | Error e) {
            pending.forEach(f -> f.cancel(true));
            throw e;
        }
    }

    private static <A> Future<A> submit(List<String> keys, QueryContext.Handle handle, Supplier<A> fresh,
                                        BiConsumer<A, String> consume) {
        BATCHES.incrementAndGet();
        return Pool.INSTANCE.submit(() -> {
            QueryContext.Handle previous = QueryContext.attach(handle);
            try {
                A acc = fresh.get();
                for (String key : keys) {
                    consume.accept(acc, key);
                }
                QueryContext.checkCancelled();
                return acc;
            } finally {
                QueryContext.detach(previous);
            }
        });
    }

    private static <A> A await(Future<A> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AxonError(QueryContext.CANCELLED, "scan interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        }
    }
}
