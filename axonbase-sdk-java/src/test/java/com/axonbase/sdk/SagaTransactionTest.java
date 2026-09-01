package com.axonbase.sdk;

import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SagaTransaction SDK")
class SagaTransactionTest {

    private static Axon axon;
    private static final String NS = "test";
    private static final String DB = "saga_test";

    @BeforeAll
    static void setup() {
        String url = System.getenv("AXON_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "Defina AXON_URL para executar a integração de SagaTransaction.");
        axon = Axon.connect(url);
        axon.use(NS, DB);

        axon.query("DEFINE TABLE inventory SCHEMAFULL");
        axon.query("DEFINE FIELD product ON TABLE inventory TYPE string");
        axon.query("DEFINE FIELD qty ON TABLE inventory TYPE int");
        axon.query("DELETE inventory;");
        axon.query("CREATE inventory:sku1 CONTENT {product: 'Laptop', qty: 10}");

        axon.query("DEFINE TABLE ledger SCHEMAFULL");
        axon.query("DEFINE FIELD amount ON TABLE ledger TYPE float");
        axon.query("DELETE ledger;");
        axon.query("CREATE ledger:l1 CONTENT {amount: 1000.0}");
    }

    @AfterAll
    static void cleanup() {
        if (axon != null && axon.isConnected()) {
            try { axon.query("DELETE inventory;"); } catch (Exception ignored) {}
            try { axon.query("DELETE ledger;"); } catch (Exception ignored) {}
            axon.close();
        }
    }

    @Test
    @DisplayName("Manual saga via raw queries")
    void manualSaga() {
        String corr = "saga_manual_" + System.nanoTime();

        // BEGIN
        AxonValue r1 = axon.query("BEGIN SAGA pedido WITH CORRELATION '" + corr + "'");
        assertTrue(r1.isObject());
        assertEquals("RUNNING", r1.asObject().get("status").asString());
        System.out.println("BEGIN OK");

        // LET + UPDATE
        axon.query("LET $saga_corr = '" + corr + "'");
        AxonValue r2 = axon.query("UPDATE inventory:sku1 SET qty = 5;");
        assertTrue(r2.isArray());
        System.out.println("UPDATE OK: " + r2);

        // COMMIT
        AxonValue r3 = axon.query("COMMIT SAGA pedido WITH CORRELATION '" + corr + "'");
        assertTrue(r3.isObject());
        assertEquals("COMMITTED", r3.asObject().get("status").asString());
        System.out.println("COMMIT OK");

        // Verify
        AxonValue qty = axon.query("SELECT VALUE qty FROM inventory:sku1");
        System.out.println("Final qty: " + qty);
    }

    @Test
    @DisplayName("Saga rollback compensation")
    void manualRollback() {
        String corr = "saga_roll_" + System.nanoTime();

        // Reset
        axon.query("UPDATE inventory:sku1 SET qty = 10;");
        axon.query("UPDATE ledger:l1 SET amount = 1000.0;");

        // BEGIN
        AxonValue r1 = axon.query("BEGIN SAGA pedido WITH CORRELATION '" + corr + "'");
        assertTrue(r1.isObject());
        assertEquals("RUNNING", r1.asObject().get("status").asString());
        System.out.println("BEGIN OK");

        // LET + UPDATE (step 1)
        axon.query("LET $saga_corr = '" + corr + "'");
        axon.query("UPDATE inventory:sku1 SET qty = 2;");
        System.out.println("Step 1: inventory updated");

        // LET + UPDATE (step 2)
        axon.query("LET $saga_corr = '" + corr + "'");
        axon.query("UPDATE ledger:l1 SET amount = 200.0;");
        System.out.println("Step 2: ledger updated");

        // CANCEL (compensation)
        AxonValue r4 = axon.query("CANCEL SAGA pedido WITH CORRELATION '" + corr + "'");
        assertTrue(r4.isObject());
        assertEquals("FAILED", r4.asObject().get("status").asString());
        System.out.println("CANCEL OK");

        // Verify rollback
        AxonValue qty = axon.query("SELECT VALUE qty FROM inventory:sku1");
        AxonValue amt = axon.query("SELECT VALUE amount FROM ledger:l1");
        System.out.println("After cancel - qty: " + qty + ", amount: " + amt);

        String qtyStr = qty.toString();
        String amtStr = amt.toString();
        assertTrue(qtyStr.contains("10"),
            "Expected qty restored to 10, got: " + qtyStr);
        assertTrue(amtStr.contains("1000"),
            "Expected amount restored to 1000, got: " + amtStr);
    }

    @Test
    @DisplayName("Saga participant commits only its local transaction")
    void participantUsesLocalTransaction() {
        String corr = "saga_participant_" + System.nanoTime();

        try (SagaParticipantTransaction participant = axon.sagaParticipant(corr)) {
            participant.begin();
            participant.step("UPDATE inventory:sku1 SET qty = 4");
            participant.commit();
            assertTrue(participant.isFinished());
        }
    }
}
