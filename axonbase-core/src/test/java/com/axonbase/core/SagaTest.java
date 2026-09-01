package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.SagaLedger;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SagaTest {

    @Test
    void cancelRestoresLocalUpdateAndRecordsStep() {
        Datastore datastore = Datastore.memory();
        datastore.createDatabase("test", "bank1");
        Session session = Session.create();
        session.namespace("test");
        session.database("bank1");

        datastore.execute("CREATE orders:o1 CONTENT { total: 150 }", session, null);
        datastore.execute("BEGIN SAGA pedido WITH CORRELATION 'local_update'", session, null);
        datastore.execute("LET $saga_corr = 'local_update'", session, null);
        datastore.execute("UPDATE orders:o1 SET total = 200", session, null);

        AxonValue transaction = datastore.execute(
            "SHOW SAGA TRANSACTION pedido 'local_update'", session, null);
        assertEquals(1, transaction.asObject().get("steps").asArray().size());

        datastore.execute("CANCEL SAGA pedido WITH CORRELATION 'local_update'", session, null);
        AxonValue row = datastore.execute("SELECT * FROM orders:o1", session, null);
        assertEquals(150, row.asObject().get("total").asLong());
    }

    @Test
    void cancelRemovesLocallyCreatedRecord() {
        Datastore datastore = Datastore.memory();
        datastore.createDatabase("test", "bank1");
        Session session = Session.create();
        session.namespace("test");
        session.database("bank1");

        datastore.execute("BEGIN SAGA pedido WITH CORRELATION 'local_create'", session, null);
        datastore.execute("LET $saga_corr = 'local_create'", session, null);
        datastore.execute("CREATE orders:o1 CONTENT { total: 200 }", session, null);
        datastore.execute("CANCEL SAGA pedido WITH CORRELATION 'local_create'", session, null);

        AxonValue rows = datastore.execute("SELECT * FROM orders:o1", session, null);
        assertEquals(0, rows.asArray().size());
    }

    @Test
    void reportedParticipantStepsAreIdempotentAndNeverCompensatedLocally() {
        Datastore datastore = Datastore.memory();
        SagaLedger ledger = new SagaLedger(datastore);
        ledger.ensureTables();
        ledger.beginSaga("pedido", "participant_report");

        AxonValue participantStep = AxonValue.object(Map.of(
            "step_order", AxonValue.num(1),
            "step_ns", AxonValue.str("participant"),
            "step_db", AxonValue.str("orders"),
            "link_name", AxonValue.str("local"),
            "table_name", AxonValue.str("orders"),
            "record_key", AxonValue.str("o1"),
            "before", AxonValue.object(Map.of()),
            "operation", AxonValue.str("CREATE"),
            "status", AxonValue.str("PREPARED")
        ));

        ledger.reportParticipantSteps("participant_report", "orchestrator", "orders_link", List.of(participantStep));
        ledger.reportParticipantSteps("participant_report", "orchestrator", "orders_link", List.of(participantStep));

        Session session = Session.create();
        session.namespace("system");
        session.database("saga");
        AxonValue steps = datastore.execute("SELECT * FROM saga_step", session, null);
        assertEquals(1, steps.asArray().size());
        assertEquals("REPORTED", steps.asArray().get(0).asObject().get("status").asString());
        assertEquals("PREPARED", steps.asArray().get(0).asObject().get("participant_status").asString());

        // No DATABASE LINK is configured. CANCEL succeeds only when the reported step is skipped.
        ledger.cancelSaga("pedido", "participant_report");
    }
}
