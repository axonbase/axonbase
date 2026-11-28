package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SAGA: LEAVE SAGA desvincula a sessão sem encerrar a transação")
class SagaLeaveTest {

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

    @Test
    void escritaDepoisDeSairNaoEhCompensada() {
        run("BEGIN SAGA pedido WITH CORRELATION 'l1'");
        run("JOIN SAGA pedido WITH CORRELATION 'l1'");
        run("CREATE b:dentro SET v = 1");
        run("LEAVE SAGA");
        run("CREATE b:fora SET v = 2");

        run("CANCEL SAGA pedido WITH CORRELATION 'l1'");

        List<AxonValue> rows = run("SELECT * FROM b").asArray();
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(2, rows.get(0).asObject().get("v").asLong());
    }

    @Test
    void leaveContinuaValendoComoNomeDeTabelaECampo() {
        run("CREATE leave:1 SET leave = 7");

        List<AxonValue> rows = run("SELECT leave FROM leave WHERE leave = 7").asArray();

        assertEquals(7, rows.get(0).asObject().get("leave").asLong());
    }
}
