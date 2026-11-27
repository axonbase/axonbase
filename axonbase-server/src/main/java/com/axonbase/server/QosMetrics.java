package com.axonbase.server;

import com.axonbase.core.qos.QosStats;
import com.axonbase.core.qos.WorkloadGovernor;

/** Métricas {@code axe_qos_*} no formato do Prometheus. */
final class QosMetrics {

    private QosMetrics() {
    }

    static void append(StringBuilder sb, WorkloadGovernor governor) {
        sb.append("# TYPE axe_qos_enabled gauge\n")
          .append("axe_qos_enabled ").append(governor == null ? 0 : 1).append("\n");
        if (governor == null) {
            return;
        }
        QosStats stats = governor.stats();
        sb.append("# TYPE axe_qos_running gauge\n")
          .append("axe_qos_running{class=\"transactional\"} ").append(stats.transactionalRunning()).append("\n")
          .append("axe_qos_running{class=\"analytical\"} ").append(stats.analyticalRunning()).append("\n")
          .append("# TYPE axe_qos_queued gauge\n")
          .append("axe_qos_queued{class=\"analytical\"} ").append(stats.analyticalQueued()).append("\n")
          .append("# TYPE axe_qos_admitted_total counter\n")
          .append("axe_qos_admitted_total{class=\"transactional\"} ").append(stats.transactionalAdmitted()).append("\n")
          .append("axe_qos_admitted_total{class=\"analytical\"} ").append(stats.analyticalAdmitted()).append("\n")
          .append("# TYPE axe_qos_rejected_total counter\n")
          .append("axe_qos_rejected_total{reason=\"queue_full\"} ").append(stats.rejectedQueueFull()).append("\n")
          .append("axe_qos_rejected_total{reason=\"queue_timeout\"} ").append(stats.rejectedTimeout()).append("\n")
          .append("# TYPE axe_qos_demoted_total counter\n")
          .append("axe_qos_demoted_total ").append(stats.demoted()).append("\n")
          .append("# TYPE axe_qos_budget_exceeded_total counter\n")
          .append("axe_qos_budget_exceeded_total ").append(stats.budgetExceeded()).append("\n")
          .append("# TYPE axe_qos_wait_seconds_total counter\n")
          .append("axe_qos_wait_seconds_total ").append(stats.waitNanos() / 1e9).append("\n")
          .append("# TYPE axe_qos_throttle_seconds_total counter\n")
          .append("axe_qos_throttle_seconds_total ").append(stats.throttleNanos() / 1e9).append("\n")
          .append("# TYPE axe_qos_query_duration_seconds histogram\n");
        histogram(sb, "transactional", stats.transactional());
        histogram(sb, "analytical", stats.analytical());
    }

    private static void histogram(StringBuilder sb, String workload, QosStats.Histogram histogram) {
        double[] bounds = histogram.boundsSeconds();
        long[] cumulative = histogram.cumulative();
        for (int i = 0; i < bounds.length; i++) {
            sb.append("axe_qos_query_duration_seconds_bucket{class=\"").append(workload).append("\",le=\"")
              .append(bounds[i]).append("\"} ").append(cumulative[i]).append("\n");
        }
        sb.append("axe_qos_query_duration_seconds_bucket{class=\"").append(workload).append("\",le=\"+Inf\"} ")
          .append(histogram.count()).append("\n")
          .append("axe_qos_query_duration_seconds_sum{class=\"").append(workload).append("\"} ")
          .append(histogram.sumSeconds()).append("\n")
          .append("axe_qos_query_duration_seconds_count{class=\"").append(workload).append("\"} ")
          .append(histogram.count()).append("\n");
    }
}
