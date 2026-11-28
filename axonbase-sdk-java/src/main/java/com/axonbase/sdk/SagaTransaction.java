package com.axonbase.sdk;

import com.axonbase.common.Messages;
import com.axonbase.value.AxonValue;

import java.util.Map;

/**
 * Client-side SagaTransaction for distributed transactions via DATABASE LINK.
 * <p>
 * Usage::
 * <pre>{@code
 * SagaTransaction saga = new SagaTransaction(axon, "pedido", "corr-123");
 * saga.begin();
 * try {
 *     saga.capture("inventory", "sku1", "UPDATE");
 *     saga.capture("ledger", "l1", "UPDATE");
 *     saga.commit();
 * } catch (Exception e) {
 *     saga.rollback();
 * }
 * }</pre>
 */
public class SagaTransaction implements AutoCloseable {

    private final Axon axon;
    private final String sagaName;
    private final String correlationId;
    private boolean begun;
    private boolean finished;

    public SagaTransaction(Axon axon, String sagaName, String correlationId) {
        this.axon = axon;
        this.sagaName = sagaName;
        this.correlationId = correlationId;
    }

    public String sagaName() { return sagaName; }
    public String correlationId() { return correlationId; }
    public boolean isBegun() { return begun; }
    public boolean isFinished() { return finished; }

    /**
     * Inicia a saga. Cria o registro no ledger com status RUNNING.
     */
    public void begin() {
        if (begun) throw new IllegalStateException(Messages.get("sdk_saga_already_begun", correlationId));
        AxonValue result = axon.query("BEGIN SAGA " + target());
        if (result.isObject()) {
            AxonValue status = result.asObject().get("status");
            if (status != null && status.isString() && "RUNNING".equals(status.asString())) {
                begun = true;
                axon.query("LET $saga_corr = " + AxonQlText.literal(correlationId));
                return;
            }
        }
        throw new AxonSdkException(Messages.get("sdk_saga_begin_failed", result));
    }

    /** Executes an AxonQL statement with automatic server-side step capture. */
    public AxonValue step(String axonql) {
        if (!begun) throw new IllegalStateException(Messages.get("sdk_saga_not_begun"));
        if (finished) throw new IllegalStateException(Messages.get("sdk_saga_already_finished"));
        axon.query("LET $saga_corr = " + AxonQlText.literal(correlationId));
        return axon.query(axonql);
    }

    /**
     * Captura o estado atual de um registro e registra como step em system.saga.
     * Deve ser chamado <b>antes</b> da mutação, com o registro no estado original.
     *
     * @param table     nome da tabela (ex: "inventory"), obrigatoriamente um identificador simples
     * @param recordId  chave crua do registro (ex: "sku1"), sem aspas nem crases
     * @param ns        namespace (ex: "test")
     * @param db        database (ex: "saga_test")
     * @param operation tipo de operação ("UPDATE" | "CREATE" | "DELETE")
     * @throws IllegalArgumentException se a tabela não for um identificador simples
     */
    public void capture(String table, String recordId, String ns, String db, String operation) {
        if (!begun) throw new IllegalStateException(Messages.get("sdk_saga_not_begun"));
        if (finished) throw new IllegalStateException(Messages.get("sdk_saga_already_finished"));
        String target = AxonQlText.tableName(table) + ":" + AxonQlText.recordKey(recordId);

        // 1. Get current state (before) from the business database
        axon.use(ns, db);
        AxonValue before = axon.query("SELECT * FROM " + target);
        // SELECT * returns an array; extract the first record if present
        AxonValue beforeRecord = before;
        if (before != null && before.isArray() && !before.asArray().isEmpty()) {
            beforeRecord = before.asArray().get(0);
        }

        // 2. Record step in system.saga
        axon.use("system", "saga");
        AxonValue snapshot = beforeRecord != null && beforeRecord.isObject()
            ? beforeRecord : AxonValue.object(Map.of());
        // sem a correlação na sessão, senão o servidor registraria um passo para a gravação do próprio passo
        axon.query("LET $saga_corr = NULL");
        try {
            recordStep(table, recordId, ns, db, operation, snapshot);
        } finally {
            axon.query("LET $saga_corr = " + AxonQlText.literal(correlationId));
        }

        // 3. Switch back to business database
        axon.use(ns, db);
    }

    /** Grava o passo no ledger. Todos os dados vão como parâmetros: nenhum entra no texto do comando. */
    private void recordStep(String table, String recordId, String ns, String db, String operation,
                            AxonValue snapshot) {
        axon.query("CREATE saga_step CONTENT {"
            + "correlation_id: $step_correlation,"
            + "step_order: $step_order,"
            + "step_ns: $step_ns,"
            + "step_db: $step_db,"
            + "link_name: 'local',"
            + "table_name: $step_table,"
            + "record_key: $step_key,"
            + "before: $step_before,"
            + "operation: $step_operation,"
            + "status: 'PREPARED'}", Map.of(
                "step_correlation", AxonValue.str(correlationId),
                "step_order", AxonValue.num(System.currentTimeMillis()),
                "step_ns", AxonValue.str(ns),
                "step_db", AxonValue.str(db),
                "step_table", AxonValue.str(table),
                "step_key", AxonValue.str(recordId),
                "step_before", snapshot,
                "step_operation", AxonValue.str(operation)));
    }

    /**
     * Confirma a saga. Marca como COMMITTED no ledger.
     */
    public void commit() {
        if (!begun) throw new IllegalStateException(Messages.get("sdk_saga_not_begun"));
        if (finished) throw new IllegalStateException(Messages.get("sdk_saga_already_finished"));
        AxonValue result = axon.query(
            "COMMIT SAGA " + target());
        AxonValue status = result.isObject() ? result.asObject().get("status") : null;
        if (status == null || !status.isString() || !"COMMITTED".equals(status.asString())) {
            throw new AxonSdkException(Messages.get("sdk_saga_commit_failed", result));
        }
        finished = true;
        axon.query("LET $saga_corr = NULL");
    }

    /**
     * Cancela a saga. Dispara a compensação reversa automaticamente
     * no servidor (restaura os before snapshots).
     */
    public void rollback() {
        if (!begun) return;
        if (finished) return;
        axon.query("CANCEL SAGA " + target());
        finished = true;
        axon.query("LET $saga_corr = NULL");
    }

    /**
     * Mostra o estado atual da saga no ledger.
     */
    public AxonValue describe() {
        return axon.query(
            "SHOW SAGA TRANSACTION " + AxonQlText.sagaName(sagaName)
                + " " + AxonQlText.literal(correlationId));
    }

    /** O nome e a correlação, já escritos como entram nos comandos do ciclo de vida. */
    private String target() {
        return AxonQlText.sagaName(sagaName) + " WITH CORRELATION " + AxonQlText.literal(correlationId);
    }

    /** Cancels an unfinished Saga when used in try-with-resources. */
    @Override
    public void close() {
        rollback();
    }
}
