package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SAGA: cancelamento pela API de gestão avisa os participantes")
class SagaAdminCancelTest {

    private static Session scope(String ns, String db) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        return s;
    }

    @Test
    void cancelamentoNoNamespaceSystemUsaAsLigacoesDoNamespaceDeOrigem() throws Exception {
        try (FakeParticipant participant = FakeParticipant.start()) {
            Datastore ds = Datastore.memory();
            Session app = scope("app", "main");
            ds.execute("DEFINE DATABASE LINK \"p1\" CONNECT BY \"" + participant.url()
                + "\" WITH ns = \"app\" db = \"p\" user = \"\" password = \"\"", app, null);
            ds.execute("CREATE SAGA s1 WITH DATABASES 'p1'", app, null);
            ds.execute("BEGIN SAGA s1 WITH CORRELATION 'r1'", app, null);

            // a API de gestão executa o comando em system/saga
            var result = ds.execute("CANCEL SAGA s1 WITH CORRELATION 'r1'", scope("system", "saga"), null);

            assertEquals("FAILED", result.asObject().get("status").asString());
            assertTrue(participant.received().stream().anyMatch(q -> q.startsWith("CANCEL SAGA s1")),
                participant.received().toString());
        }
    }
}
