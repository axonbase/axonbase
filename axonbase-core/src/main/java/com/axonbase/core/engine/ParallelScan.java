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
import java.util.function.BooleanSupplier;
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
    static <A, I> void run(Consumer<Predicate<I>> scan, A head, Supplier<A> fresh,
                        BiConsumer<A, I> consume, BiConsumer<A, A> merge) {
        run(scan, head, fresh, consume, merge, () -> false);
    }

    /**
     * Como {@link #run(Consumer, Object, Supplier, BiConsumer, BiConsumer)}, mas para de ler assim que {@code done}
     * (avaliado sobre o acumulador {@code head}, que já tem tudo o que veio antes) for verdadeiro. Os lotes ainda em
     * andamento são descartados, então o que sobra em {@code head} é um prefixo da varredura em ordem.
     */
    static <A, I> void run(Consumer<Predicate<I>> scan, A head, Supplier<A> fresh,
                        BiConsumer<A, I> consume, BiConsumer<A, A> merge, BooleanSupplier done) {
        if (degree() < 2) {
            scan.accept(key -> {
                QueryContext.checkCancelled();
                consume.accept(head, key);
                return !done.getAsBoolean();
            });
            return;
        }
        QueryContext.Handle handle = QueryContext.current();
        ArrayDeque<Future<A>> pending = new ArrayDeque<>();
        int maxInFlight = 2 * degree();
        long[] seen = {0};
        List<List<I>> current = new ArrayList<>();
        current.add(new ArrayList<>(BATCH));
        try {
            scan.accept(key -> {
                QueryContext.checkCancelled();
                if (seen[0]++ < SEQUENTIAL_HEAD) {
                    consume.accept(head, key);
                    return !done.getAsBoolean();
                }
                List<I> batch = current.get(0);
                batch.add(key);
                if (batch.size() >= BATCH) {
                    pending.add(submit(batch, handle, fresh, consume));
                    current.set(0, new ArrayList<>(BATCH));
                    while (pending.size() >= maxInFlight) {
                        merge.accept(head, await(pending.poll()));
                    }
                    // com muitos lotes já fundidos, só vale continuar se a janela ainda não fechou
                    while (!pending.isEmpty() && pending.peek().isDone()) {
                        merge.accept(head, await(pending.poll()));
                    }
                }
                return !done.getAsBoolean();
            });
            if (done.getAsBoolean()) {
                pending.forEach(f -> f.cancel(true));
                return;
            }
            if (!current.get(0).isEmpty()) {
                pending.add(submit(current.get(0), handle, fresh, consume));
            }
            while (!pending.isEmpty()) {
                merge.accept(head, await(pending.poll()));
                if (done.getAsBoolean()) {
                    pending.forEach(f -> f.cancel(true));
                    return;
                }
            }
        } catch (RuntimeException | Error e) {
            pending.forEach(f -> f.cancel(true));
            throw e;
        }
    }

    private static <A, I> Future<A> submit(List<I> keys, QueryContext.Handle handle, Supplier<A> fresh,
                                           BiConsumer<A, I> consume) {
        BATCHES.incrementAndGet();
        return Pool.INSTANCE.submit(() -> {
            QueryContext.Handle previous = QueryContext.attach(handle);
            QueryContext.sharedWorker(true);
            QueryContext.resumed();
            try {
                A acc = fresh.get();
                for (I key : keys) {
                    // cancelamento e orçamento do QoS também valem onde o registro é lido e avaliado
                    QueryContext.checkCancelled();
                    consume.accept(acc, key);
                }
                QueryContext.checkCancelled();
                return acc;
            } finally {
                QueryContext.sharedWorker(false);
                QueryContext.detach(previous);
            }
        });
    }

    private static <A> A await(Future<A> future) {
        try {
            A result = future.get();
            QueryContext.resumed();
            return result;
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
