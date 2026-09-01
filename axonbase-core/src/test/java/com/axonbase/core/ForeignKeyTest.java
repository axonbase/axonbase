package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForeignKeyTest {

    private Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private Session s() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    @Test
    void defineFieldReferencesValidaIntegridade() {
        Datastore ds = ds();
        Session s = s();
        ds.execute("DEFINE FIELD autor ON TABLE post REFERENCES user", s, null);
        ds.execute("CREATE user:1 CONTENT {name: \"Ana\"}", s, null);

        ds.execute("CREATE post:p1 CONTENT {autor: user:1}", s, null);
        assertEquals(1, ds.execute("SELECT * FROM post", s, null).asArray().size());

        assertThrows(com.axonbase.common.AxonError.class,
            () -> ds.execute("CREATE post:p2 CONTENT {autor: user:999}", s, null));
    }
}