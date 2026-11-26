package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;

/**
 * Consulta em execução na thread atual, para cancelamento cooperativo. O servidor cria um {@link Handle} por instrução,
 * liga-o à thread que executa ({@link #attach}) e o {@code Executor} confere o sinal a cada acesso ao armazenamento
 * ({@link #checkCancelled}). Sem handle ligado (uso embarcado, testes) a checagem não faz nada.
 */
public final class QueryContext {

    /** Código de erro de uma consulta cancelada (-32010 e -32011 já são de liderança e quórum). */
    public static final int CANCELLED = -32014;

    private static final ThreadLocal<Handle> CURRENT = new ThreadLocal<>();

    private QueryContext() {}

    /** Ponto de contenção de uma consulta, chamado a cada acesso ao armazenamento (veja o QoS). */
    @FunctionalInterface
    public interface Pacer {
        /** Pode pausar a thread, ou lançar para interromper a consulta. */
        void checkpoint();
    }

    /** Uma consulta em andamento. {@code cancel()} pode vir de qualquer thread. */
    public static final class Handle {
        private final String id;
        private final long startedNanos = System.nanoTime();
        private final long startedAtMs = System.currentTimeMillis();
        private volatile boolean cancelled;
        private volatile String cancelledBy;
        private volatile Pacer pacer;
        private volatile String requestedWorkload;
        private final java.util.concurrent.CopyOnWriteArrayList<Runnable> cancelHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

        public Handle(String id) {
            this.id = id;
        }

        public Pacer pacer() {
            return pacer;
        }

        public void pacer(Pacer pacer) {
            this.pacer = pacer;
        }

        /** O tipo de carga que o cliente pediu para esta consulta, ou {@code null}. */
        public String requestedWorkload() {
            return requestedWorkload;
        }

        public void requestWorkload(String workload) {
            this.requestedWorkload = workload;
        }

        public String id() {
            return id;
        }

        public long startedAtMs() {
            return startedAtMs;
        }

        public long elapsedNanos() {
            return System.nanoTime() - startedNanos;
        }

        public boolean cancelled() {
            return cancelled;
        }

        public String cancelledBy() {
            return cancelledBy;
        }

        /** Pede o cancelamento. A consulta para no próximo acesso ao armazenamento. */
        public void cancel(String by) {
            this.cancelledBy = by;
            this.cancelled = true;
            for (Runnable hook : cancelHooks) {
                hook.run();
            }
        }

        /** Registra o que fazer quando a consulta for cancelada (roda já, se ela já foi). Usado pelo AxonPL. */
        public void onCancel(Runnable hook) {
            cancelHooks.add(hook);
            if (cancelled) {
                hook.run();
            }
        }

        public void removeOnCancel(Runnable hook) {
            cancelHooks.remove(hook);
        }
    }

    /** Liga o handle à thread atual e devolve o anterior (para restaurar com {@link #detach}). */
    public static Handle attach(Handle handle) {
        Handle previous = CURRENT.get();
        CURRENT.set(handle);
        return previous;
    }

    public static void detach(Handle previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static Handle current() {
        return CURRENT.get();
    }

    /** Lança se a consulta desta thread foi cancelada. Barata: uma leitura de ThreadLocal e de um campo volátil. */
    public static void checkCancelled() {
        Handle h = CURRENT.get();
        if (h == null) {
            return;
        }
        if (h.cancelled) {
            throw new AxonError(CANCELLED, Messages.get("query_cancelled"));
        }
        Pacer pacer = h.pacer;
        if (pacer != null) {
            pacer.checkpoint();
        }
    }
}
