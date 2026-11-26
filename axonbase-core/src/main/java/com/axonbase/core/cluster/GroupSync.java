package com.axonbase.core.cluster;

import com.axonbase.core.storage.RocksDbBackend.SyncMode;

import java.util.function.LongSupplier;

/**
 * Group commit do log: quem pede durabilidade enquanto um fsync está em curso espera o próximo, e um único
 * fsync atende todos que escreveram antes dele começar. Não há thread própria: a primeira que chega faz o
 * fsync pelas outras.
 *
 * <p>Com {@code batch} e {@code off} ninguém espera; em {@code batch} o dono chama {@link #periodicSync()} a
 * cada segundo. Um fsync que falha é definitivo: o sistema operacional pode ter descartado as páginas, então
 * nada mais é dado como durável.</p>
 */
final class GroupSync {

    private final SyncMode mode;
    private final LongSupplier written;
    private final Runnable fsync;
    private long durable;
    private long epoch;
    private long syncs;
    private boolean syncing;
    private RuntimeException failure;

    GroupSync(SyncMode mode, LongSupplier written, Runnable fsync) {
        this.mode = mode;
        this.written = written;
        this.fsync = fsync;
    }

    /**
     * Garante a durabilidade que o modo promete para a entrada {@code index}.
     *
     * @return {@code false} se a entrada não está mais no log (truncada)
     */
    boolean awaitDurable(long index) {
        if (mode != SyncMode.ALWAYS) {
            synchronized (this) {
                ensureHealthy();
            }
            return index <= written.getAsLong();
        }
        boolean interrupted = false;
        try {
            while (true) {
                long target;
                long started;
                synchronized (this) {
                    ensureHealthy();
                    if (durable >= index) {
                        return true;
                    }
                    if (index > written.getAsLong()) {
                        return false;
                    }
                    if (syncing) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                        continue;
                    }
                    syncing = true;
                    target = written.getAsLong();
                    started = epoch;
                }
                flush(target, started);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** No modo {@code batch}, sincroniza se há entradas ainda não sincronizadas. */
    void periodicSync() {
        if (mode != SyncMode.BATCH) {
            return;
        }
        long target;
        long started;
        synchronized (this) {
            ensureHealthy();
            target = written.getAsLong();
            if (syncing || target <= durable) {
                return;
            }
            syncing = true;
            started = epoch;
        }
        flush(target, started);
    }

    private void flush(long target, long started) {
        boolean done = false;
        try {
            fsync.run();
            done = true;
        } catch (RuntimeException e) {
            synchronized (this) {
                failure = e;
            }
            throw new IllegalStateException("fsync do log Raft falhou", e);
        } finally {
            synchronized (this) {
                syncing = false;
                if (done) {
                    syncs++;
                    // Um truncamento durante o fsync invalida o alvo.
                    if (started == epoch) {
                        durable = Math.max(durable, target);
                    }
                }
                notifyAll();
            }
        }
    }

    /** As entradas a partir de {@code index} deixaram de existir. */
    synchronized void truncated(long index) {
        epoch++;
        durable = Math.min(durable, Math.max(0, index - 1));
        notifyAll();
    }

    /** Último índice que conta para o quórum neste nó. */
    long durableIndex() {
        if (mode != SyncMode.ALWAYS) {
            return written.getAsLong();
        }
        synchronized (this) {
            return durable;
        }
    }

    /** Um fsync falhou: este nó não pode mais confirmar, votar nem concorrer. */
    synchronized boolean failed() {
        return failure != null;
    }

    synchronized long syncCount() {
        return syncs;
    }

    private void ensureHealthy() {
        if (failure != null) {
            throw new IllegalStateException("fsync do log Raft falhou antes", failure);
        }
    }
}
