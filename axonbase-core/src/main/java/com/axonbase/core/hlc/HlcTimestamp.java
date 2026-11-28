package com.axonbase.core.hlc;

/**
 * Carimbo do relógio híbrido (HLC, Hybrid Logical Clock): milissegundos físicos e um contador lógico, comparados
 * nessa ordem. Ocupa 12 bytes quando serializado. {@link #NONE} significa "sem carimbo" (lotes anteriores à 0.11).
 */
public record HlcTimestamp(long physical, int logical) implements Comparable<HlcTimestamp> {

    /** Ausência de carimbo. Menor que qualquer carimbo emitido. */
    public static final HlcTimestamp NONE = new HlcTimestamp(0L, 0);

    /** O maior carimbo possível dentro do milissegundo: corte "até este instante, inclusive". */
    public static HlcTimestamp endOfMillisecond(long epochMillis) {
        return new HlcTimestamp(epochMillis, Integer.MAX_VALUE);
    }

    public boolean isNone() {
        return equals(NONE);
    }

    /** O carimbo imediatamente anterior a este. */
    public HlcTimestamp predecessor() {
        if (logical > 0) {
            return new HlcTimestamp(physical, logical - 1);
        }
        return physical > 0 ? new HlcTimestamp(physical - 1, Integer.MAX_VALUE) : NONE;
    }

    public static HlcTimestamp max(HlcTimestamp a, HlcTimestamp b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    @Override
    public int compareTo(HlcTimestamp other) {
        int byPhysical = Long.compare(physical, other.physical);
        return byPhysical != 0 ? byPhysical : Integer.compare(logical, other.logical);
    }

    @Override
    public String toString() {
        return java.time.Instant.ofEpochMilli(physical) + "#" + logical;
    }
}
