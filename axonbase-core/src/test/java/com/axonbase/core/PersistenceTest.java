package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.FileBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Storage: persistencia en arquivo")
class PersistenceTest {

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    @Test
    void persisteRecargabile(@TempDir Path dir) {
        Path file = dir.resolve("axon.db");
        String path = file.toString();

        Datastore ds1 = new Datastore(new FileBackend(path));
        ds1.createDatabase("test", "dev");
        ds1.execute("CREATE person CONTENT { name: \"Ana\", age: 30 }", session(), null);

        // novo datastore sobre o mesmo ficheiro
        Datastore ds2 = new Datastore(new FileBackend(path));
        ds2.createDatabase("test", "dev");
        AxonValue rows = ds2.execute("SELECT * FROM person", session(), null);
        assertTrue(rows.isArray());
        assertEquals(1, rows.asArray().size());
        assertEquals("Ana", rows.asArray().get(0).asObject().get("name").asString());
    }

    @Test
    void walPersisteRecargabile(@TempDir Path dir) {
        String base = dir.resolve("wal").toString();
        Datastore ds1 = new Datastore(new com.axonbase.core.storage.WalBackend(base));
        ds1.createDatabase("test", "dev");
        ds1.execute("CREATE person CONTENT { name: \"Ana\", age: 30 }", session(), null);

        Datastore ds2 = new Datastore(new com.axonbase.core.storage.WalBackend(base));
        ds2.createDatabase("test", "dev");
        AxonValue rows = ds2.execute("SELECT * FROM person", session(), null);
        assertTrue(rows.isArray());
        assertEquals(1, rows.asArray().size());
        assertEquals("Ana", rows.asArray().get(0).asObject().get("name").asString());
    }
}