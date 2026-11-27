package com.axonbase.core.engine;

import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.core.catalog.RecordId;
import com.axonbase.parser.Render;
import com.axonbase.parser.ast.Statement;
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

    private static final System.Logger LOG = System.getLogger(SagaLedger.class.getName());
    private static final String SAGA_NS = "system";
    private static final String SAGA_DB = "saga";

    private final Datastore ds;
    private final DatabaseLinkClient linkClient = new DatabaseLinkClient();
    private static final java.util.concurrent.atomic.AtomicLong stepCounter =
        new java.util.concurrent.atomic.AtomicLong();

    public SagaLedger(Datastore ds) {
        this.ds = ds;
    }

    public void ensureTables() {
        ds.execute("DEFINE TABLE saga SCHEMAFULL;", sagaSession(), null);
        ds.execute("DEFINE FIELD name ON TABLE saga TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD correlation_id ON TABLE saga TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD status ON TABLE saga TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD created_at ON TABLE saga TYPE datetime;", sagaSession(), null);
        ds.execute("DEFINE FIELD links ON TABLE saga TYPE array;", sagaSession(), null);
        ds.execute("DEFINE FIELD link_namespace ON TABLE saga TYPE string;", sagaSession(), null);
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
        ds.execute("DEFINE FIELD record_literal ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD before ON TABLE saga_step TYPE object;", sagaSession(), null);
        ds.execute("DEFINE FIELD operation ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD status ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE FIELD participant_step_order ON TABLE saga_step TYPE int;", sagaSession(), null);
        ds.execute("DEFINE FIELD participant_status ON TABLE saga_step TYPE string;", sagaSession(), null);
        ds.execute("DEFINE INDEX step_corr ON TABLE saga_step COLUMNS correlation_id, step_order UNIQUE;", sagaSession(), null);
        ds.execute("DEFINE INDEX step_participant ON TABLE saga_step COLUMNS correlation_id, link_ns, link_name, participant_step_order UNIQUE;", sagaSession(), null);
    }

    public void beginSaga(String sagaResource, String correlationId) {
        beginSaga(sagaResource, correlationId, List.of(), "");
    }

    /**
     * @param links participantes da definição no momento do BEGIN; vazio quando a SAGA não tem definição
     * @param linkNamespace namespace de quem abriu a SAGA, onde as ligações dos participantes estão definidas
     */
    public void beginSaga(String sagaResource, String correlationId, List<String> links, String linkNamespace) {
        String participants = Render.value(
            AxonValue.array(links.stream().map(AxonValue::str).toList()));
        ds.execute("UPSERT " + ledgerRecord(correlationId)
            + " CONTENT {name: \"" + esc(sagaResource)
            + "\", correlation_id: \"" + esc(correlationId)
            + "\", links: " + participants
            + ", link_namespace: \"" + esc(linkNamespace)
            + "\", status: \"RUNNING\", created_at: time::now()}", sagaSession(), null);
    }

    /** O registro da transação no ledger, ou {@code null} quando não há nenhuma com essa correlação. */
    Map<String, AxonValue> transaction(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return null;
        AxonValue result = ds.execute("SELECT * FROM " + ledgerRecord(correlationId), sagaSession(), null);
        if (result.isArray()) {
            result = result.asArray().isEmpty() ? AxonValue.nul() : result.asArray().get(0);
        }
        return result.isObject() ? result.asObject() : null;
    }

    public void commitSaga(String sagaResource, String correlationId) {
        ds.execute("UPDATE " + ledgerRecord(correlationId) + " SET status = 'COMMITTED'",
            sagaSession(), null);
    }

    public boolean isSagaActive(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return false;
        AxonValue result = ds.execute(
            "SELECT VALUE status FROM " + ledgerRecord(correlationId), sagaSession(), null);
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
    public long recordStep(String correlationId, String stepNs, String stepDb,
                            String linkNamespace, String linkName, String table,
                            RecordId rid, AxonValue beforeValue, String operation) {
        if (correlationId == null || correlationId.isBlank()) return 0;
        String beforeLiteral = beforeLiteral(beforeValue);
        long order = nextStepOrder();
        ds.execute("CREATE saga_step CONTENT {"
            + "correlation_id: \"" + esc(correlationId) + "\","
            + "step_order: " + order + ","
            + "step_ns: \"" + esc(stepNs) + "\","
            + "step_db: \"" + esc(stepDb) + "\","
            + "link_ns: \"" + esc(linkNamespace) + "\","
            + "link_name: \"" + esc(linkName) + "\","
            + "table_name: \"" + esc(table) + "\","
            + "record_key: \"" + esc(recordKey(rid)) + "\","
            + "record_literal: \"" + esc(keyLiteral(rid)) + "\","
            + "before: " + beforeLiteral + ","
            + "operation: \"" + esc(operation) + "\","
            + "status: \"PREPARED\"}", sagaSession(), null);
        return order;
    }

    /** O passo de um RELATE local: as duas pontas e o que a aresta tinha antes dele. */
    long recordEdgeStep(String correlationId, String stepNs, String stepDb, String kind, RecordId from, RecordId to,
                        boolean existed, Map<String, AxonValue> properties) {
        Map<String, AxonValue> before = new LinkedHashMap<>();
        before.put("from_table", AxonValue.str(from.table()));
        before.put("from_key", AxonValue.str(keyLiteral(from)));
        before.put("to_table", AxonValue.str(to.table()));
        before.put("to_key", AxonValue.str(keyLiteral(to)));
        before.put("existed", AxonValue.bool(existed));
        before.put("properties", AxonValue.object(properties));
        return recordStep(correlationId, stepNs, stepDb, "", "local", kind, from, AxonValue.object(before),
            "RELATE");
    }

    /** Retira o passo de uma escrita que não chegou a ser gravada. */
    void discardStep(String correlationId, long stepOrder) {
        try {
            ds.execute("DELETE saga_step WHERE correlation_id = \"" + esc(correlationId)
                + "\" AND step_order = " + stepOrder, sagaSession(), null);
        } catch (RuntimeException e) {
            // a falha que descartou a escrita é a que precisa chegar a quem chamou
            LOG.log(System.Logger.Level.WARNING, "passo " + stepOrder + " da SAGA " + correlationId
                + " ficou no ledger sem a escrita correspondente", e);
        }
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
            long participantOrder = longField(step, "step_order");
            if (participantOrder <= 0 || participantStepReported(correlationId, linkNamespace, linkName, participantOrder)) {
                continue;
            }
            String beforeLiteral = beforeLiteral(step.get("before"));
            ds.execute("CREATE saga_step CONTENT {"
                + "correlation_id: \"" + esc(correlationId) + "\","
                + "step_order: " + nextStepOrder() + ","
                + "step_ns: \"" + esc(strField(step, "step_ns")) + "\","
                + "step_db: \"" + esc(strField(step, "step_db")) + "\","
                + "link_ns: \"" + esc(linkNamespace) + "\","
                + "link_name: \"" + esc(linkName) + "\","
                + "table_name: \"" + esc(strField(step, "table_name")) + "\","
                + "record_key: \"" + esc(strField(step, "record_key")) + "\","
                + "record_literal: \"" + esc(strField(step, "record_literal")) + "\","
                + "before: " + beforeLiteral + ","
                + "operation: \"" + esc(strField(step, "operation")) + "\","
                + "status: \"REPORTED\","
                + "participant_step_order: " + participantOrder + ","
                + "participant_status: \"" + esc(strField(step, "status")) + "\"}", sagaSession(), null);
        }
    }

    private boolean participantStepReported(String correlationId, String linkNamespace, String linkName, long participantOrder) {
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
        cancelSaga(sagaResource, correlationId, Session.create());
    }

    /** @param caller sessão de quem cancela; a compensação local roda com a identidade dela */
    void cancelSaga(String sagaResource, String correlationId, Session caller) {
        ds.execute("UPDATE " + ledgerRecord(correlationId) + " SET status = 'COMPENSATING'",
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
            compensateStep(step, correlationId, caller);
        }

        ds.execute("UPDATE " + ledgerRecord(correlationId) + " SET status = 'FAILED'",
            sagaSession(), null);
    }

    private void compensateStep(Map<String, AxonValue> step, String corrId, Session caller) {
        String stepNs = strField(step, "step_ns");
        String stepDb = strField(step, "step_db");
        String linkNamespace = strField(step, "link_ns");
        String linkName = strField(step, "link_name");
        long stepOrder = longField(step, "step_order");

        Datastore.DatabaseLinkDef linkDef = null;
        if (!"local".equals(linkName)) {
            linkDef = ds.databaseLink(linkNamespace, linkName);
            if (linkDef == null) {
                throw new IllegalStateException(Messages.get("saga_compensation_link_missing", linkName));
            }
        } else if (stepNs.isEmpty() || stepDb.isEmpty()) {
            throw new IllegalStateException(Messages.get("saga_compensation_refused", stepNs + "/" + stepDb));
        }

        for (Statement undo : SagaUndo.statements(step)) {
            if (linkDef == null) {
                executeLocally(undo, strField(step, "table_name"), caller, stepNs, stepDb);
            } else {
                executeRemotely(linkDef, undo);
            }
        }
        markCompensated(corrId, stepOrder);
    }

    /**
     * Roda a sentença no banco do passo, com a identidade e o teto de papel de quem cancela, numa sessão que não
     * executa nada além dela.
     */
    private void executeLocally(Statement undo, String table, Session caller, String stepNs, String stepDb) {
        // a compensação repõe o que já existia: não passa pelas restrições das escritas novas
        Session target = caller.restoringCopy();
        target.namespace(stepNs);
        target.database(stepDb);
        target.compensation(new SagaCompensation(undo, table));
        ds.executeCompensation(undo, target);
    }

    /**
     * No participante a sentença roda como um comando da credencial da ligação. Um DELETE que as PERMISSIONS de lá
     * pulam não devolve erro, então o registro é procurado antes de o passo contar como compensado.
     */
    private void executeRemotely(Datastore.DatabaseLinkDef linkDef, Statement undo) {
        linkClient.query(linkDef, SagaUndo.remoteText(undo));
        if (undo instanceof Statement.Delete delete
            && linkClient.query(linkDef, SagaUndo.remoteProbe(delete)).stream().anyMatch(AxonValue::isObject)) {
            throw new IllegalStateException(Messages.get("saga_compensation_not_applied", linkDef.name()));
        }
    }

    private void markCompensated(String corrId, long stepOrder) {
        ds.execute("UPDATE saga_step SET status = 'COMPENSATED' WHERE correlation_id = \""
            + esc(corrId) + "\" AND step_order = " + stepOrder, sagaSession(), null);
    }

    private static String recordKey(RecordId recordId) {
        AxonValue key = recordId.key();
        return key.isString() ? key.asString() : key.toString();
    }

    /** A chave como literal AxonQL, que preserva o tipo (texto, número, uuid) e aceita hífen e espaço. */
    private static String keyLiteral(RecordId recordId) {
        AxonValue key = recordId.key();
        return key.isNumber() ? Render.numberKey(key) : Render.value(key);
    }

    /** O estado anterior como literal AxonQL: em JSON, data e decimal voltariam como objetos. */
    private static String beforeLiteral(AxonValue before) {
        return before != null && before.isObject() ? Render.value(before) : "{}";
    }

    /**
     * Ordem crescente também entre reinícios e trocas de líder: a compensação percorre os passos do maior para o
     * menor, então um contador que recomeçasse restauraria os estados fora de ordem.
     */
    private static long nextStepOrder() {
        return stepCounter.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
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

    /**
     * O registro da transação como literal AxonQL. Uma correlação só de dígitos continua sendo chave numérica, como
     * nas versões anteriores; as demais vão entre aspas, que é o que aceita hífen (UUID).
     */
    private static String ledgerRecord(String correlationId) {
        String key = correlationId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return key.matches("\\d{1,18}") ? "saga:" + key : "saga:'" + key + "'";
    }
}
