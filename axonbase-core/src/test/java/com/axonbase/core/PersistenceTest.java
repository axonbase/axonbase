package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.FileBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void recuperaPlanoDeControleCompleto(@TempDir Path dir) {
        String path = dir.resolve("control.db").toString();
        Datastore first = new Datastore(new FileBackend(path));
        Session s = session();
        first.createDatabase("test", "dev");
        first.execute("DEFINE ANALYZER texto LOWERCASE STOPWORDS \"o\" STEMMING", s, null);
        first.execute("DEFINE TABLE person SCHEMAFULL PERMISSIONS FOR select WHERE age >= 18", s, null);
        first.execute("DEFINE FIELD age ON TABLE person TYPE int DEFAULT 18", s, null);
        first.execute("DEFINE INDEX busca ON TABLE person COLUMNS name SEARCH ANALYZER texto", s, null);
        first.execute("DEFINE EVENT audit ON TABLE person WHEN $event = \"CREATE\" "
            + "THEN (CREATE changes CONTENT { kind: \"person\" })", s, null);
        first.execute("DEFINE USER alice ON DATABASE PASSWORD \"secreta\" ROLES editor", s, null);
        first.execute("DEFINE USER cert ON DATABASE CERTIFICATE clients FINGERPRINT \"SHA256:ABCD\"", s, null);
        first.execute("DEFINE ACCESS login ON DATABASE", s, null);

        Datastore recovered = new Datastore(new FileBackend(path));
        assertTrue(recovered.namespaces().contains("test"));
        assertTrue(recovered.databases("test").contains("dev"));
        assertNotNull(recovered.authCatalog().verify("alice", "secreta", "test", "dev"));
        var certificateUser = recovered.authCatalog().user("cert", "test", "dev");
        assertNotNull(certificateUser);
        assertEquals("clients", certificateUser.certificate());
        assertEquals("SHA256:ABCD", certificateUser.fingerprint());
        assertTrue(certificateUser.certificateBased());
        assertNotNull(recovered.authCatalog().access("login", "test", "dev"));
        AxonValue info = recovered.execute("INFO FOR TABLE person", session(), null);
        assertTrue(info.asObject().get("fields").asObject().containsKey("age"));
        assertTrue(info.asObject().get("indexes").asObject().containsKey("busca"));
        assertTrue(info.asObject().get("events").asObject().containsKey("audit"));

        recovered.execute("CREATE person CONTENT { name: \"Ana\" }", session(), null);
        assertEquals(1, recovered.execute("SELECT * FROM changes", session(), null).asArray().size());
    }
}
