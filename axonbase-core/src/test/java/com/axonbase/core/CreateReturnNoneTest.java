package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CREATE ... RETURN NONE grava e não devolve o registro")
class CreateReturnNoneTest {

    @Test
    void naoDevolveORegistroCriado() {
        Datastore ds = Datastore.memory();
        Session session = Session.create();
        session.namespace("t");
        session.database("d");

        AxonValue result = ds.execute("CREATE item:1 SET v = 1 RETURN NONE", session, null);

        assertTrue(result.isNull() || result.isNone(), result.toString());
        assertEquals(1, ds.execute("SELECT * FROM item", session, null).asArray().size());
    }
}
