package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BackupRestoreTest {

    @Test
    void backupRestoreClusterPreservaDadosCatalogoEKV() {
        Datastore source = Datastore.memory();
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        source.createDatabase("app", "main");
        source.execute("DEFINE TABLE person SCHEMAFULL", s, null);
        source.execute("DEFINE FIELD email ON TABLE person TYPE string", s, null);
        source.execute("DEFINE USER alice ON DATABASE PASSWORD \"s3cr3t\"", s, null);
        source.execute("CREATE person:1 CONTENT {email: \"a@b.c\"}", s, null);
        source.kvSet(s, "app", "main", "flag", AxonValue.str("v"), null);
        source.execute("RELATE person:1->knows->person:1", s, null);

        String dump = source.backupCluster();

        Datastore target = Datastore.memory();
        target.restoreCluster(dump);

        Session t = Session.create();
        t.namespace("app");
        t.database("main");
        AxonValue rows = target.execute("SELECT * FROM person", t, null);
        assertEquals(1, rows.asArray().size());
        assertEquals("a@b.c", rows.asArray().get(0).asObject().get("email").asString());
        assertEquals("v", target.kvGet(t, "app", "main", "flag").isString()
            ? target.kvGet(t, "app", "main", "flag").asString()
            : target.kvGet(t, "app", "main", "flag").toString());
        assertNotNull(target.authCatalog().verify("alice", "s3cr3t", "app", "main"));
        AxonValue back = target.execute("SELECT <-knows<-person FROM person:1", t, null);
        assertNotNull(back);
    }
}