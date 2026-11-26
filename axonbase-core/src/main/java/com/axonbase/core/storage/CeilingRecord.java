package com.axonbase.core.storage;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * O teto gravado na chave {@code !hlc}: o maior milissegundo e o maior índice que podem ser usados sem gravar de
 * novo. Depois de uma queda, o relógio e o índice recomeçam acima dele.
 *
 * <p>Invariante: o que {@link #durableMillis()} e {@link #durableIndex()} anunciam nunca passa do que está em disco.
 * Por isso a memória só sobe depois de a gravação durável voltar, e desce antes de uma gravação menor começar.</p>
 *
 * <p>Ordem das travas: quem chama pode estar com o monitor do {@link CommitSequencer} e o do relógio. Nada feito com
 * a trava deste teto pode pedir um desses dois.</p>
 */
final class CeilingRecord implements HybridClock.Ceiling {

    private final Consumer<byte[]> store;
    private final long leadMillis;
    private final long indexBlock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long millis;
    private volatile long index;
    private volatile boolean closed;

    /**
     * @param store grava o valor na chave do teto e só volta com ele sincronizado em disco; falha vira exceção
     * @param leadMillis quanto o teto vai à frente do carimbo que o pede
     * @param indexBlock quantos índices cada teto reserva
     */
    CeilingRecord(Consumer<byte[]> store, long leadMillis, long indexBlock) {
        this.store = store;
        this.leadMillis = leadMillis;
        this.indexBlock = indexBlock;
    }

    /** Na abertura: o que já está em disco. */
    void load(CommitWatermark stored) {
        millis = stored.stamp().physical();
        index = stored.index();
    }

    @Override
    public long durableMillis() {
        return millis;
    }

    long durableIndex() {
        return index;
    }

    boolean closed() {
        return closed;
    }

    @Override
    public void raise(long atLeastMillis) {
        lock.lock();
        try {
            requireOpen();
            if (atLeastMillis > millis) {
                write(atLeastMillis + leadMillis, index);
            }
        } finally {
            lock.unlock();
        }
    }

    void raiseIndex(long atLeast) {
        lock.lock();
        try {
            requireOpen();
            if (atLeast > index) {
                write(millis, atLeast + indexBlock);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Já passou da metade da folga: vale subir antes que alguém precise esperar. */
    boolean needsRenewal(long usedMillis, long usedIndex) {
        return usedMillis > millis - leadMillis / 2 || usedIndex > index - indexBlock / 2;
    }

    /** Sobe o teto com antecedência. Não espera: se outra thread já está gravando, não há o que fazer aqui. */
    void renew(long usedMillis, long usedIndex) {
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (!closed && needsRenewal(usedMillis, usedIndex)) {
                write(Math.max(millis, usedMillis + leadMillis), Math.max(index, usedIndex + indexBlock));
            }
        } finally {
            lock.unlock();
        }
    }

    /** Desce o teto ao exato último uso. Só no desligamento, sem ninguém emitindo. */
    void lower(long exactMillis, long exactIndex) {
        lock.lock();
        try {
            requireOpen();
            millis = Math.min(millis, exactMillis);
            index = Math.min(index, exactIndex);
            write(exactMillis, exactIndex);
        } finally {
            lock.unlock();
        }
    }

    /** Roda {@code writes} sem que o teto seja gravado no meio. */
    void locked(Runnable writes) {
        lock.lock();
        try {
            writes.run();
        } finally {
            lock.unlock();
        }
    }

    /** Fecha a base: {@code release} roda com a trava, e depois dele nada mais é gravado. */
    void close(Runnable release) {
        lock.lock();
        try {
            closed = true;
            release.run();
        } finally {
            lock.unlock();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("a base está fechada: o teto do relógio não pode ser gravado");
        }
    }

    /** Só com a trava. O contador do carimbo gravado é o máximo: o teto vale o milissegundo inteiro. */
    private void write(long newMillis, long newIndex) {
        store.accept(CommitSequencer.encode(new HlcTimestamp(newMillis, Integer.MAX_VALUE), newIndex));
        millis = newMillis;
        index = newIndex;
    }
}
