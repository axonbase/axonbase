package com.axonbase.core.hlc;

/** O carimbo recebido passa do relógio local mais o desvio máximo aceito: o lote é rejeitado. */
public final class ClockDriftException extends RuntimeException {

    private final transient HlcTimestamp received;
    private final long driftMs;

    public ClockDriftException(HlcTimestamp received, long driftMs, long maxDriftMs) {
        super("carimbo " + received + " está " + driftMs + " ms à frente do relógio local (máximo " + maxDriftMs
            + " ms)");
        this.received = received;
        this.driftMs = driftMs;
    }

    public HlcTimestamp received() {
        return received;
    }

    public long driftMs() {
        return driftMs;
    }
}
