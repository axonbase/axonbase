package com.axonbase.core.cluster;

/**
 * Desfecho de uma transferência de liderança.
 *
 * @param transferred se outro nó assumiu
 * @param leader      o líder novo, quando houve transferência
 * @param reason      por que não houve, quando não houve
 * @param millis      quanto durou a tentativa
 */
public record TransferResult(boolean transferred, String leader, String reason, long millis) {

    static TransferResult done(String leader, long startedNanos) {
        return new TransferResult(true, leader, "", elapsed(startedNanos));
    }

    static TransferResult refused(String reason, long startedNanos) {
        return new TransferResult(false, "", reason, elapsed(startedNanos));
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
