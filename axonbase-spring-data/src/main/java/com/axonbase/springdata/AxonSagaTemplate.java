package com.axonbase.springdata;

import com.axonbase.common.Messages;
import com.axonbase.sdk.Axon;
import com.axonbase.value.AxonValue;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Executes local Spring Data work as a participant in an already active Saga.
 *
 * <p>The Saga lifecycle belongs to its orchestrator. This template only verifies
 * the supplied correlation, binds it for the duration of the local transaction,
 * and clears the binding afterwards.</p>
 */
public final class AxonSagaTemplate {
    private final TransactionTemplate transactionTemplate;
    private final SagaCorrelationScope sagaScope;

    public AxonSagaTemplate(Axon axon, PlatformTransactionManager transactionManager) {
        this(new AxonSagaCorrelationScope(Objects.requireNonNull(axon, Messages.get("sdk_axon_required"))), transactionManager);
    }

    AxonSagaTemplate(SagaCorrelationScope sagaScope, PlatformTransactionManager transactionManager) {
        this.sagaScope = Objects.requireNonNull(sagaScope, Messages.get("spring_saga_scope_required"));
        this.transactionTemplate = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, Messages.get("spring_transaction_manager_required")));
    }

    /**
     * Runs work in a local Spring transaction after joining the active Saga.
     * This method never begins, commits, or cancels the Saga itself.
     */
    public <T> T execute(String sagaName, String correlationId, TransactionCallback<T> work) {
        requireActiveSaga(sagaName, correlationId);
        return transactionTemplate.execute(status -> executeBound(correlationId, work, status));
    }

    /** Convenience overload for work that does not need the transaction status. */
    public <T> T execute(String sagaName, String correlationId, Supplier<T> work) {
        Objects.requireNonNull(work, Messages.get("spring_work_required"));
        return execute(sagaName, correlationId, status -> work.get());
    }

    /** Convenience overload for work that does not return a value. */
    public void executeWithoutResult(String sagaName, String correlationId, Consumer<TransactionStatus> work) {
        execute(sagaName, correlationId, status -> {
            work.accept(status);
            return null;
        });
    }

    private <T> T executeBound(String correlationId, TransactionCallback<T> work, TransactionStatus status) {
        Objects.requireNonNull(work, Messages.get("spring_work_required"));
        sagaScope.bind(correlationId);
        try {
            return work.doInTransaction(status);
        } finally {
            sagaScope.clear();
        }
    }

    private void requireActiveSaga(String sagaName, String correlationId) {
        if (sagaName == null || sagaName.isBlank()) {
            throw new IllegalArgumentException(Messages.get("spring_saga_name_required"));
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException(Messages.get("spring_correlation_id_blank"));
        }

        AxonValue result = sagaScope.show(sagaName, correlationId);
        AxonValue saga = result.isObject() ? result.asObject().get("saga") : null;
        if (saga == null || !saga.isObject()
            || !hasValue(saga, "name", sagaName)
            || !hasValue(saga, "correlation_id", correlationId)
            || !hasValue(saga, "status", "RUNNING")) {
            throw new IllegalStateException(Messages.get("spring_no_active_saga", correlationId));
        }
    }

    private static boolean hasValue(AxonValue object, String field, String expected) {
        AxonValue value = object.asObject().get(field);
        return value != null && value.isString() && expected.equals(value.asString());
    }

    interface SagaCorrelationScope {
        AxonValue show(String sagaName, String correlationId);

        void bind(String correlationId);

        void clear();
    }

    private static final class AxonSagaCorrelationScope implements SagaCorrelationScope {
        private final Axon axon;

        private AxonSagaCorrelationScope(Axon axon) {
            this.axon = axon;
        }

        @Override
        public AxonValue show(String sagaName, String correlationId) {
            return axon.query("SHOW SAGA TRANSACTION " + escape(sagaName)
                + " '" + escape(correlationId) + "'");
        }

        @Override
        public void bind(String correlationId) {
            axon.query("LET $saga_corr = '" + escape(correlationId) + "'");
        }

        @Override
        public void clear() {
            axon.query("LET $saga_corr = NULL");
        }

        private static String escape(String value) {
            return value.replace("'", "''").replace("\\", "\\\\");
        }
    }
}
