package com.axonbase.springdata;

import com.axonbase.common.Messages;
import com.axonbase.jdbc.SagaBinding;
import com.axonbase.jdbc.SagaScope;
import com.axonbase.sdk.Axon;
import com.axonbase.value.AxonValue;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Executes local Spring Data work as a participant in an already active Saga.
 *
 * <p>The Saga lifecycle belongs to its orchestrator. This template only verifies
 * the supplied correlation, binds it for the duration of the local transaction,
 * and clears the binding afterwards.</p>
 *
 * <p>Designed to work with a {@link DataSource}. The saga scope is obtained
 * from the transaction-bound JDBC connection via {@link SagaScope}.</p>
 */
public final class AxonSagaTemplate {
    private final TransactionTemplate transactionTemplate;
    private final SagaScopeFactory sagaScopeFactory;

    /**
     * @deprecated Use {@link #AxonSagaTemplate(DataSource, PlatformTransactionManager)}.
     * This variant shares a single {@link Axon} client across all transactions,
     * which can cause saga correlation to leak between concurrent requests.
     */
    @Deprecated
    public AxonSagaTemplate(Axon axon, PlatformTransactionManager transactionManager) {
        this(new AxonSagaCorrelationScope(Objects.requireNonNull(axon, Messages.get("sdk_axon_required"))), transactionManager);
    }

    /**
     * Creates a template that obtains the saga scope from the transaction-bound
     * JDBC connection.
     */
    public AxonSagaTemplate(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this(new DataSourceSagaScope(dataSource), transactionManager);
    }

    AxonSagaTemplate(SagaScopeFactory sagaScopeFactory, PlatformTransactionManager transactionManager) {
        this.sagaScopeFactory = Objects.requireNonNull(sagaScopeFactory, Messages.get("spring_saga_scope_required"));
        this.transactionTemplate = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, Messages.get("spring_transaction_manager_required")));
    }

    /**
     * Runs work in a local Spring transaction after joining the active Saga.
     * This method never begins, commits, or cancels the Saga itself.
     */
    public <T> T execute(String sagaName, String correlationId, TransactionCallback<T> work) {
        Objects.requireNonNull(sagaName, Messages.get("spring_saga_name_required"));
        Objects.requireNonNull(correlationId, Messages.get("spring_correlation_id_blank"));
        return transactionTemplate.execute(status -> {
            SagaBinding binding = sagaScopeFactory.joinSaga(sagaName, correlationId);
            try {
                return work.doInTransaction(status);
            } finally {
                try {
                    binding.close();
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        });
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

    @FunctionalInterface
    interface SagaScopeFactory {
        SagaBinding joinSaga(String sagaName, String correlationId);
    }

    private static final class AxonSagaCorrelationScope implements SagaScopeFactory {
        private final Axon axon;

        private AxonSagaCorrelationScope(Axon axon) {
            this.axon = axon;
        }

        @Override
        public SagaBinding joinSaga(String sagaName, String correlationId) {
            // Fallback: previous behavior using SDK directly
            AxonValue result = axon.query("SHOW SAGA TRANSACTION " + escape(sagaName)
                + " '" + escape(correlationId) + "'");
            AxonValue saga = result.isObject() ? result.asObject().get("saga") : null;
            if (saga == null || !saga.isObject()
                || !hasValue(saga, "name", sagaName)
                || !hasValue(saga, "correlation_id", correlationId)
                || !hasValue(saga, "status", "RUNNING")) {
                throw new IllegalStateException(Messages.get("spring_no_active_saga", correlationId));
            }
            axon.query("LET $saga_corr = '" + escape(correlationId) + "'");
            return () -> axon.query("LET $saga_corr = NULL");
        }
    }

    private static final class DataSourceSagaScope implements SagaScopeFactory {
        private final DataSource dataSource;

        private DataSourceSagaScope(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, Messages.get("spring_datasource_required"));
        }

        @Override
        public SagaBinding joinSaga(String sagaName, String correlationId) {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                SagaScope sagaScope = connection.unwrap(SagaScope.class);
                return sagaScope.joinSaga(sagaName, correlationId);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(Messages.get("spring_join_saga_failed", correlationId), e);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    private static boolean hasValue(AxonValue object, String field, String expected) {
        AxonValue value = object.asObject().get(field);
        return value != null && value.isString() && expected.equals(value.asString());
    }

    private static String escape(String value) {
        return value.replace("'", "''").replace("\\", "\\\\");
    }
}
