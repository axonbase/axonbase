package com.axonbase.core.qos;

/**
 * Retrato do governador para as métricas.
 *
 * @param waitNanos       tempo somado na fila de analíticas
 * @param throttleNanos   tempo somado em pausas do orçamento
 * @param transactional   duração das consultas transacionais
 * @param analytical      duração das consultas analíticas (inclusive as rebaixadas)
 */
public record QosStats(int transactionalRunning, int analyticalRunning, int analyticalQueued,
                       long transactionalAdmitted, long analyticalAdmitted, long rejectedQueueFull,
                       long rejectedTimeout, long demoted, long budgetExceeded, long waitNanos, long throttleNanos,
                       Histogram transactional, Histogram analytical) {

    /** Contagens acumuladas por faixa, no formato do Prometheus: {@code cumulative[i]} conta até {@code bounds[i]}. */
    public record Histogram(double[] boundsSeconds, long[] cumulative, long count, double sumSeconds) {
    }
}
