package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.journal.PitrJournal;

/** Métricas do journal de PITR e do relógio híbrido, no formato do Prometheus. */
final class PitrMetrics {

    private PitrMetrics() {
    }

    static void append(StringBuilder sb, PitrJournal journal, Datastore ds) {
        metric(sb, "axe_pitr_enabled", "gauge", journal == null ? 0 : 1);
        if (journal != null) {
            metric(sb, "axe_pitr_dropped_batches_total", "counter", journal.droppedBatches());
            metric(sb, "axe_pitr_write_errors_total", "counter", journal.writeErrors());
            metric(sb, "axe_pitr_pending_batches", "gauge", journal.pendingBatches());
            metric(sb, "axe_pitr_last_seal_timestamp_seconds", "gauge", journal.lastSealMillis() / 1000);
        }
        ds.rocksBackend().ifPresent(rocks -> {
            metric(sb, "axe_hlc_rejected_total", "counter", rocks.clock().rejectedCount());
            metric(sb, "axe_hlc_drift_max_seconds", "gauge", rocks.clock().maxObservedDriftMs() / 1000.0);
        });
    }

    private static void metric(StringBuilder sb, String name, String type, Number value) {
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n')
          .append(name).append(' ').append(value).append('\n');
    }
}
