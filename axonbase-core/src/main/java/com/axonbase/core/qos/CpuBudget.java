package com.axonbase.core.qos;

import java.util.function.LongSupplier;

/**
 * Orçamento de execução das consultas analíticas: um balde que enche à razão de {@code núcleos × percentual} e é
 * esvaziado pelo tempo que as threads analíticas executam. Com o balde negativo, quem cobra precisa pausar.
 */
final class CpuBudget {

    /** Folga acumulável: o quanto as analíticas podem correr de uma vez depois de paradas. */
    private static final long BURST_WALL_NANOS = 100_000_000L;
    /** Pausa máxima por cobrança, para a consulta continuar respondendo a cancelamento. */
    static final long MAX_PAUSE_NANOS = 50_000_000L;
    /** Dívida máxima, em tempo de relógio: além disso um excesso antigo seguraria consultas novas. */
    private static final long MAX_DEBT_WALL_NANOS = 1_000_000_000L;

    private final double rate;
    private final double burst;
    private final LongSupplier clock;
    private double balance;
    private long refilledAt;

    CpuBudget(double rate, LongSupplier clock) {
        this.rate = rate;
        this.burst = rate * BURST_WALL_NANOS;
        this.clock = clock;
        this.balance = burst;
        this.refilledAt = clock.getAsLong();
    }

    /**
     * Cobra {@code executed} nanossegundos de execução.
     *
     * @return quantos nanossegundos quem cobrou deve pausar, 0 se ainda há saldo
     */
    synchronized long charge(long executed) {
        long now = clock.getAsLong();
        balance = Math.min(burst, balance + (now - refilledAt) * rate);
        refilledAt = now;
        balance = Math.max(-rate * MAX_DEBT_WALL_NANOS, balance - executed);
        if (balance >= 0) {
            return 0;
        }
        return Math.min(MAX_PAUSE_NANOS, (long) (-balance / rate));
    }
}
