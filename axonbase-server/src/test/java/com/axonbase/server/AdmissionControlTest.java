package com.axonbase.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Controle de admissão HTTP")
class AdmissionControlTest {

    @Test
    @DisplayName("reserva threads para saúde e administração")
    void reservesThreads() {
        assertEquals(2, AdmissionControl.limitFor(4));
        assertEquals(28, AdmissionControl.limitFor(32));
        assertEquals(1, AdmissionControl.limitFor(1));
    }

    @Test
    @DisplayName("recusa além do limite e libera ao sair")
    void rejectsBeyondLimit() {
        AdmissionControl control = new AdmissionControl(2);
        assertTrue(control.tryEnter());
        assertTrue(control.tryEnter());
        assertFalse(control.tryEnter());
        assertEquals(2, control.inFlight());
        control.exit();
        assertTrue(control.tryEnter());
    }

    @Test
    @DisplayName("caminhos operacionais são isentos")
    void operationalPathsAreExempt() {
        for (String path : new String[] {"/health", "/ready", "/status", "/version", "/metrics",
            "/admin/cluster/join", "/rpc/ws/abc"}) {
            assertTrue(AdmissionControl.isExempt(path), path);
        }
        for (String path : new String[] {"/sql", "/sql/x", "/table/person", "/rpc", "/graphql",
            "/mcp/x", "/export", "/import", "/healthz"}) {
            assertFalse(AdmissionControl.isExempt(path), path);
        }
    }
}
