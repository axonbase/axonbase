package com.axonbase.core.hlc;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Relógio híbrido (HLC, Hybrid Logical Clock) de um nó.
 *
 * <ul>
 * <li><b>Emissão</b> ({@link #now()}): {@code físico = max(físico anterior, relógio local)}; se o físico não
 *     avançou o contador incrementa, senão volta a 0. O carimbo nunca recua.</li>
 * <li><b>Recepção</b> ({@link #receive}): o relógio vira o {@code max} entre o próprio valor e o recebido. Um
 *     carimbo cujo físico passe do relógio local mais o desvio máximo é rejeitado e gera alerta.</li>
 * <li><b>Reinício</b> ({@link #restore}): {@code max(relógio, persistido + 1)}.</li>
 * <li><b>Teto</b> ({@link #guard}): nenhum carimbo é emitido acima do teto durável sem antes subi-lo, então o que
 *     precisa ir para o disco é só o teto, de tempos em tempos, e não cada carimbo.</li>
 * </ul>
 */
public final class HybridClock {

    /** Desvio máximo aceito entre nós, em milissegundos. */
    public static final long DEFAULT_MAX_DRIFT_MS = 500L;

    /** Um lote rejeitado por desvio de relógio. */
    public record DriftEvent(HlcTimestamp received, long wallMillis, long driftMs) {
    }

    /** Teto de carimbos que sobrevive a uma queda. */
    public interface Ceiling {
        /** Até que milissegundo físico já está garantido em disco. Chamado a cada emissão: precisa ser barato. */
        long durableMillis();

        /**
         * Grava um teto que cubra {@code atLeastMillis} e só volta quando ele estiver em disco. Uma falha precisa
         * virar exceção: o carimbo que pediu não é emitido.
         */
        void raise(long atLeastMillis);
    }

    private final LongSupplier wall;
    private final long maxDriftMs;
    private final Consumer<DriftEvent> alert;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong maxObservedDrift = new AtomicLong();
    private long physical;
    private int logical;
    private Ceiling ceiling;

    public HybridClock(LongSupplier wall, long maxDriftMs, Consumer<DriftEvent> alert) {
        this.wall = wall;
        this.maxDriftMs = maxDriftMs;
        this.alert = alert;
    }

    /**
     * Relógio do sistema, com o desvio máximo de {@code AXON_HLC_MAX_DRIFT_MS} (opcional, padrão
     * {@value #DEFAULT_MAX_DRIFT_MS} ms) e alerta no log de erro.
     */
    public static HybridClock system() {
        long maxDrift = DEFAULT_MAX_DRIFT_MS;
        String raw = System.getenv("AXON_HLC_MAX_DRIFT_MS");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("axon.hlc.max-drift-ms");
        }
        if (raw != null && !raw.isBlank()) {
            try {
                maxDrift = Math.max(0L, Long.parseLong(raw.trim()));
            } catch (NumberFormatException ignored) {
                // valor ilegível: fica o padrão
            }
        }
        return new HybridClock(System::currentTimeMillis, maxDrift, event -> System.err.println(
            "{\"event\":\"hlc_drift_rejected\",\"driftMs\":" + event.driftMs() + ",\"received\":\""
                + event.received() + "\"}"));
    }

    /** Emite um carimbo novo, estritamente maior que todos os já emitidos ou observados. */
    public synchronized HlcTimestamp now() {
        long clock = wall.getAsLong();
        long nextPhysical = physical;
        int nextLogical = 0;
        if (clock > physical) {
            nextPhysical = clock;
        } else if (logical == Integer.MAX_VALUE) {
            nextPhysical++;
        } else {
            nextLogical = logical + 1;
        }
        if (ceiling != null && nextPhysical > ceiling.durableMillis()) {
            ceiling.raise(nextPhysical);
        }
        physical = nextPhysical;
        logical = nextLogical;
        return new HlcTimestamp(nextPhysical, nextLogical);
    }

    /** O maior carimbo emitido ou observado. */
    public synchronized HlcTimestamp last() {
        return new HlcTimestamp(physical, logical);
    }

    /**
     * Recebe o carimbo de um lote vindo de outro nó.
     *
     * @throws ClockDriftException quando o carimbo passa do relógio local mais o desvio máximo; o relógio não muda
     */
    public void receive(HlcTimestamp remote) {
        if (remote.isNone()) {
            return;
        }
        long clock = wall.getAsLong();
        long drift = remote.physical() - clock;
        maxObservedDrift.accumulateAndGet(drift, Math::max);
        if (drift > maxDriftMs) {
            rejected.incrementAndGet();
            alert.accept(new DriftEvent(remote, clock, drift));
            throw new ClockDriftException(remote, drift, maxDriftMs);
        }
        observe(remote);
    }

    /**
     * Avança o relógio até um carimbo já aceito (o log local relido, uma base restaurada), sem a checagem de
     * desvio.
     */
    public synchronized void observe(HlcTimestamp seen) {
        if (seen.compareTo(new HlcTimestamp(physical, logical)) > 0) {
            physical = seen.physical();
            logical = seen.logical();
        }
    }

    /** Reinício do nó: o próximo carimbo é maior que o persistido, mesmo que o relógio local esteja atrás. */
    public void restore(HlcTimestamp persisted) {
        if (!persisted.isNone()) {
            observe(new HlcTimestamp(persisted.physical() + 1, 0));
        }
    }

    /**
     * Daqui em diante nenhum carimbo é emitido acima do teto. Um relógio guarda um teto só (o da última chamada):
     * não o compartilhe entre bases abertas ao mesmo tempo.
     */
    public synchronized void guard(Ceiling newCeiling) {
        this.ceiling = newCeiling;
    }

    /**
     * Roda {@code atRest} com o físico do último carimbo emitido ou observado, sem deixar emitir nenhum outro
     * enquanto isso. Serve para apertar o teto no desligamento.
     */
    public synchronized void settle(LongConsumer atRest) {
        atRest.accept(physical);
    }

    public long maxDriftMs() {
        return maxDriftMs;
    }

    /** Lotes rejeitados por desvio desde o início do processo. */
    public long rejectedCount() {
        return rejected.get();
    }

    /** Maior adiantamento de um carimbo recebido em relação ao relógio local (métrica de desvio entre nós). */
    public long maxObservedDriftMs() {
        return maxObservedDrift.get();
    }
}
