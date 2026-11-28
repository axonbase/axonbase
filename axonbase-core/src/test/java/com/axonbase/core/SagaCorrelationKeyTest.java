package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SAGA: a correlação aceita hífen, como em um UUID")
class SagaCorrelationKeyTest {

    private final Datastore ds = Datastore.memory();
    private final Session session = scope("t", "d");

    private static Session scope(String ns, String db) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private String status(String correlation) {
        AxonValue shown = run("SHOW SAGA TRANSACTION pedido '" + correlation + "'");
        return shown.asObject().get("saga").asObject().get("status").asString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"53c07c2a-0020-4a1e-9f3b-7d2f0c9a1b11", "abc-123", "1abc", "pedido_7", "42"})
    void cancelCompensaEMostraOEstado(String correlation) {
        run("BEGIN SAGA pedido WITH CORRELATION '" + correlation + "'");
        run("JOIN SAGA pedido WITH CORRELATION '" + correlation + "'");
        run("CREATE b:1 SET v = 1");

        run("CANCEL SAGA pedido WITH CORRELATION '" + correlation + "'");

        assertEquals("FAILED", status(correlation));
        assertEquals(0, run("SELECT * FROM b").asArray().size());
    }

    @Test
    void commitEncerraSomenteACorrelacaoPedida() {
        run("BEGIN SAGA pedido WITH CORRELATION 'lote-1'");
        run("BEGIN SAGA pedido WITH CORRELATION 'lote-2'");

        run("COMMIT SAGA pedido WITH CORRELATION 'lote-1'");

        assertEquals("COMMITTED", status("lote-1"));
        assertEquals("RUNNING", status("lote-2"));
    }

    @Test
    void cancelamentoNoEscopoDaApiDeGestaoAceitaUuid() {
        String correlation = "0b9d2f4e-1c3a-4f7b-8a21-5e6d7c8b9a01";
        run("BEGIN SAGA pedido WITH CORRELATION '" + correlation + "'");

        AxonValue result = ds.execute("CANCEL SAGA pedido WITH CORRELATION '" + correlation + "'",
            scope("system", "saga"), null);

        assertEquals("FAILED", result.asObject().get("status").asString());
    }
}
