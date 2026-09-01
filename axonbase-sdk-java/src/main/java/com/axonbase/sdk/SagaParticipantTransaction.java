package com.axonbase.sdk;

import com.axonbase.common.Messages;
import com.axonbase.value.AxonValue;

/** Joins an active SAGA while controlling only this client's local transaction. */
public final class SagaParticipantTransaction implements AutoCloseable {
    private final Axon axon;
    private final String correlationId;
    private boolean begun;
    private boolean finished;

    public SagaParticipantTransaction(Axon axon, String correlationId) {
        this.axon = java.util.Objects.requireNonNull(axon, Messages.get("sdk_axon_required"));
        this.correlationId = java.util.Objects.requireNonNull(correlationId, Messages.get("sdk_correlation_id_required"));
    }

    public String correlationId() { return correlationId; }
    public boolean isBegun() { return begun; }
    public boolean isFinished() { return finished; }

    public void begin() {
        if (begun) throw new IllegalStateException(Messages.get("sdk_transaction_already_begun", correlationId));
        axon.begin();
        try {
            axon.query("LET $saga_corr = '" + escape(correlationId) + "'");
            begun = true;
        } catch (RuntimeException error) {
            try {
                axon.cancel();
            } catch (RuntimeException ignored) {
                error.addSuppressed(ignored);
            }
            throw error;
        }
    }

    public AxonValue step(String axonql) {
        ensureActive();
        return axon.query(axonql);
    }

    public void commit() {
        ensureActive();
        axon.commit();
        finished = true;
    }

    public void rollback() {
        if (!begun || finished) return;
        axon.cancel();
        finished = true;
    }

    @Override
    public void close() {
        rollback();
    }

    private void ensureActive() {
        if (!begun) throw new IllegalStateException(Messages.get("sdk_transaction_not_begun"));
        if (finished) throw new IllegalStateException(Messages.get("sdk_transaction_already_finished"));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }
}
