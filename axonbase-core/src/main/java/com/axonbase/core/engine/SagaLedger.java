package com.axonbase.core.engine;

import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.core.catalog.RecordId;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ledger de transações SAGA no namespace {@code system}, database {@code saga}.
 * <p>
 * Fases 2-3: captura de before em writes, compensação reversa idempotente em CANCEL.
 */
public class SagaLedger {

    private static final String SAGA_NS = "system";
    private static final String SAGA_DB = "saga";

    private final Datastore ds;
    private final DatabaseLinkClient linkClient = new DatabaseLinkClient();
    private static final java.util.concurrent.atomic.AtomicInteger stepCounter =
        new java.util.concurrent.atomic.AtomicInteger(0);

    public SagaLedger(Datastore ds) {
        this.ds = ds;
    }

    public void ensureTables() {
        ds.execute("DEFINE TABLE saga SCHEMAFULL;", sagaSession(), null);
        ds.execute("DEFINE FIELD name ON TABLE saga TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD correlation_id ON TABLE saga TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD status ON TABLE saga TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD created_at ON TABLE saga TYPE datetime;", sagaSession(), null);
        ds.execute("DEFINE INDEX saga_corr ON TABLE saga COLUMNS correlation_id UNIQUE;", sagaSession(), null);

        ds.execute("DEFINE TABLE saga_step SCHEMAFULL;", sagaSession(), null);
        ds.execute("DEFINE FIELD correlation_id ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD step_order ON TABLE saga_step TYPE int;", sagaSession(), null);
        ds.execute("DEFINE FIELD step_ns ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD step_db ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD link_ns ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD link_name ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD table_name ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD record_key ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD before ON TABLE saga_step TYPE object;", sagaSession(), null);
        ds.execute("DEFINE FIELD operation ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD status ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD participant_step_order ON TABLE saga_step TYPE int;", sagaSession(), null);
        ds.execute("DEFINE FIELD participant_status ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE INDEX step_corr ON TABLE saga_step COLUMNS correlation_id, step_order UNIQUE;", sagaSession(), null);
        ds.execute("DEFINE INDEX step_participant ON TABLE saga_step COLUMNS correlation_id, link_ns, link_name, participant_step_order UNIQUE;", sagaSession(), null);
    }

    public void beginSaga(String sagaResource, String correlationId) {
        ds.execute("UPSERT saga:" + escKey(correlationId)
            + " CONTENT {name: \"" + esc(sagaResource)
            + "\", correlation_id: \"" + esc(correlationId)
            + "\", status: \"RUNNING\", created_at: time::now()}", sagaSession(), null);
    }

    public void commitSaga(String sagaResource, String correlationId) {
        ds.execute("UPDATE saga:" + escKey(correlationId) + " SET status = 'COMMITTED'",
            sagaSession(), null);
    }

    public boolean isSagaActive(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return false;
        AxonValue result = ds.execute(
            "SELECT VALUE status FROM saga:" + escKey(correlationId), sagaSession(), null);
        if (result.isString()) return "RUNNING".equals(result.asString());
        if (result.isArray() && !result.asArray().isEmpty()
            && result.asArray().get(0).isObject()) {
            AxonValue st = result.asArray().get(0).asObject().get("status");
            return st != null && st.isString() && "RUNNING".equals(st.asString());
        }
        return false;
    }

    /**
     * Registra um step com o snapshot anterior e aplica a compensação.
     */
    public void recordStep(String correlationId, String stepNs, String stepDb,
                            String linkNamespace, String linkName, String table,
                            RecordId rid, AxonValue beforeValue, String operation) {
        if (correlationId == null || correlationId.isBlank()) return;
        String beforeJson = beforeValue != null && beforeValue.isObject()
            ? AxonJson.write(beforeValue) : "{}";
        int order = nextStepOrder(correlationId);
        ds.execute("CREATE saga_step CONTENT {"
            + "correlation_id: \"" + esc(correlationId) + "\","
            + "step_order: " + order + ","
            + "step_ns: \"" + esc(stepNs) + "\","
            + "step_db: \"" + esc(stepDb) + "\","
            + "link_ns: \"" + esc(linkNamespace) + "\","
            + "link_name: \"" + esc(linkName) + "\","
            + "table_name: \"" + esc(table) + "\","
            + "record_key: \"" + esc(recordKey(rid)) + "\","
            + "before: " + beforeJson + ","
            + "operation: \"" + esc(operation) + "\","
            + "status: \"PREPARED\"}", sagaSession(), null);
    }

    /**
     * Mirrors local participant steps in the orchestrator ledger for auditability.
     * Mirrored steps are intentionally not PREPARED: the participant owns their
     * compensation after receiving CANCEL, so replaying them here would duplicate it.
     */
    public void reportParticipantSteps(String correlationId, String linkNamespace, String linkName,
                                       List<AxonValue> participantSteps) {
        for (AxonValue value : participantSteps) {
            if (!value.isObject()) continue;
            Map<String, AxonValue> step = value.asObject();
            if (!"local".equals(strField(step, "link_name"))) continue;
            int participantOrder = (int) longField(step, "step_order");
            if (participantOrder <= 0 || participantStepReported(correlationId, linkNamespace, linkName, participantOrder)) {
                continue;
            }
            AxonValue before = step.get("before");
            String beforeJson = before != null && before.isObject() ? AxonJson.write(before) : "{}";
            ds.execute("CREATE saga_step CONTENT {"
                + "correlation_id: \"" + esc(correlationId) + "\","
                + "step_order: " + nextStepOrder(correlationId) + ","
                + "step_ns: \"" + esc(strField(step, "step_ns")) + "\","
                + "step_db: \"" + esc(strField(step, "step_db")) + "\","
                + "link_ns: \"" + esc(linkNamespace) + "\","
                + "link_name: \"" + esc(linkName) + "\","
                + "table_name: \"" + esc(strField(step, "table_name")) + "\","
                + "record_key: \"" + esc(strField(step, "record_key")) + "\","
                + "before: " + beforeJson + ","
                + "operation: \"" + esc(strField(step, "operation")) + "\","
                + "status: \"REPORTED\","
                + "participant_step_order: " + participantOrder + ","
                + "participant_status: \"" + esc(strField(step, "status")) + "\"}", sagaSession(), null);
        }
    }

    private boolean participantStepReported(String correlationId, String linkNamespace, String linkName, int participantOrder) {
        AxonValue existing = ds.execute(
            "SELECT * FROM saga_step WHERE correlation_id = \"" + esc(correlationId)
                + "\" AND link_ns = \"" + esc(linkNamespace)
                + "\" AND link_name = \"" + esc(linkName)
                + "\" AND participant_step_order = " + participantOrder,
            sagaSession(), null);
        return existing.isArray() && !existing.asArray().isEmpty();
    }

    /**
     * Compensação automática: percorre steps em ordem reversa e restaura o before.
     * Idempotente: cada step é marcado como COMPENSATED e a busca é por status PREPARED.
     */
    public void cancelSaga(String sagaResource, String correlationId) {
        ds.execute("UPDATE saga:" + escKey(correlationId) + " SET status = 'COMPENSATING'",
            sagaSession(), null);

        // Busca steps em ordem reversa que ainda estão PREPARED
        AxonValue steps = ds.execute(
            "SELECT * FROM saga_step WHERE correlation_id = \"" + esc(correlationId)
                + "\" AND status = 'PREPARED' ORDER BY step_order DESC",
            sagaSession(), null);

        List<Map<String, AxonValue>> stepsList = new ArrayList<>();
        if (steps.isArray()) {
            for (AxonValue item : steps.asArray()) {
                if (item.isObject()) stepsList.add(item.asObject());
            }
        }

        for (Map<String, AxonValue> step : stepsList) {
            compensateStep(step, correlationId);
        }

        ds.execute("UPDATE saga:" + escKey(correlationId) + " SET status = 'FAILED'",
            sagaSession(), null);
    }

    private void compensateStep(Map<String, AxonValue> step, String corrId) {
        String operation = strField(step, "operation");
        AxonValue before = step.get("before");
        String tableName = strField(step, "table_name");
        String stepNs = strField(step, "step_ns");
        String stepDb = strField(step, "step_db");
        String linkNamespace = strField(step, "link_ns");
        String linkName = strField(step, "link_name");
        int stepOrder = (int) longField(step, "step_order");

        Session targetSession = Session.create();
        targetSession.namespace(stepNs.isEmpty() ? SAGA_NS : stepNs);
        targetSession.database(stepDb.isEmpty() ? SAGA_DB : stepDb);

        Datastore.DatabaseLinkDef linkDef = null;
        if (!"local".equals(linkName)) {
            linkDef = ds.databaseLink(linkNamespace, linkName);
            if (linkDef == null) {
                throw new IllegalStateException(Messages.get("saga_compensation_link_missing", linkName));
            }
        }

        if (before == null || !before.isObject() || before.asObject().isEmpty()) {
            if ("CREATE".equalsIgnoreCase(operation)) {
                String recordKey = strField(step, "record_key");
                if (!recordKey.isEmpty()) {
                    executeCompensation(linkDef, "DELETE " + tableName + ":" + recordKey, targetSession);
                }
            }
            markCompensated(corrId, stepOrder);
            return;
        }

        AxonValue beforeId = before.asObject().get("id");
        String keyStr = beforeId != null ? extractKey(beforeId) : "";

        Map<String, AxonValue> restoreData = new LinkedHashMap<>(before.asObject());
        restoreData.remove("id");
        String restoreJson = AxonJson.write(AxonValue.object(restoreData));

        String restoreSql = "UPSERT " + tableName + ":" + keyStr
            + " CONTENT " + restoreJson;
        executeCompensation(linkDef, restoreSql, targetSession);

        markCompensated(corrId, stepOrder);
    }

    private void markCompensated(String corrId, int stepOrder) {
        ds.execute("UPDATE saga_step SET status = 'COMPENSATED' WHERE correlation_id = \""
            + esc(corrId) + "\" AND step_order = " + stepOrder, sagaSession(), null);
    }

    private void executeCompensation(Datastore.DatabaseLinkDef linkDef, String sql, Session targetSession) {
        if (linkDef == null) {
            ds.execute(sql, targetSession, null);
        } else {
            linkClient.query(linkDef, sql);
        }
    }

    private static String recordKey(RecordId recordId) {
        AxonValue key = recordId.key();
        return key.isString() ? key.asString() : key.toString();
    }

    /** Extrai a chave de um valor que pode ser string, record id ou object. */
    private static String extractKey(AxonValue v) {
        if (v.isRecordId()) {
            Object key = v.asRecordId().key();
            if (key instanceof AxonValue kv && kv.isString()) return kv.asString();
            if (key instanceof AxonValue kv && kv.isNumber()) return kv.toString();
            return String.valueOf(key);
        }
        if (v.isString()) {
            String s = v.asString();
            int i = s.indexOf(':');
            return i >= 0 ? s.substring(i + 1) : s;
        }
        return v.toString();
    }

    private int nextStepOrder(String corrId) {
        return stepCounter.incrementAndGet();
    }

    private static Session sagaSession() {
        Session s = Session.create();
        s.namespace(SAGA_NS);
        s.database(SAGA_DB);
        return s;
    }

    private static String strField(Map<String, AxonValue> obj, String key) {
        AxonValue v = obj.get(key);
        return v != null && v.isString() ? v.asString() : "";
    }

    private static long longField(Map<String, AxonValue> obj, String key) {
        AxonValue v = obj.get(key);
        return v != null && v.isNumber() ? v.asLong() : 0;
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escKey(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
