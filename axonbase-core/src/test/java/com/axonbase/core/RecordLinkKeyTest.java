package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Um record link é um id de registro na memória e volta do disco como texto. PRIMARY KEY e UNIQUE precisam tratar as
 * duas formas como o mesmo valor, em nó único.
 */
@DisplayName("Chave primária e UNIQUE com record link")
class RecordLinkKeyTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static void run(Datastore ds, String sql) {
        ds.execute(sql, session(), null);
    }

    private static void refused(Datastore ds, String sql) {
        assertThrows(RuntimeException.class, () -> run(ds, sql), sql);
    }

    private static void accepted(Datastore ds, String sql) {
        assertDoesNotThrow(() -> run(ds, sql), sql);
    }

    @Test
    @DisplayName("depois de uma restauração a chave primária continua recusando o link repetido")
    void restoredPrimaryKeyRefusesRepeatedLink() {
        Datastore source = Datastore.memory();
        source.createDatabase("n", "d");
        run(source, "CREATE TABLE p (owner record PRIMARY KEY)");
        run(source, "CREATE p:1 SET owner = person:1");

        Datastore target = Datastore.memory();
        target.restoreCluster(source.backupCluster());

        refused(target, "CREATE p:2 SET owner = person:1");
        accepted(target, "CREATE p:3 SET owner = person:2");
    }

    @Test
    @DisplayName("entrada de chave primária na forma antiga continua recusando e é solta quando o valor muda")
    void legacyPrimaryKeyEntryStillCounts() {
        MemoryBackend backend = new MemoryBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("n", "d");
        run(ds, "CREATE TABLE p (owner record PRIMARY KEY)");
        run(ds, "CREATE p:1 SET owner = person:1");
        // versões anteriores gravavam o link recém-escrito com o prefixo x
        String prefix = "n\u0000d\u0000p\u0000!uidx\u0000";
        assertFalse(backend.keysWithPrefix(prefix).isEmpty());
        for (String key : backend.keysWithPrefix(prefix)) {
            String old = key.replace("sperson:1", "xperson:1");
            if (!old.equals(key)) {
                backend.put(old, new byte[0]);
                backend.delete(key);
            }
        }

        refused(ds, "CREATE p:2 SET owner = person:1");
        refused(ds, "CREATE p:3 SET owner = 'person:1'");

        run(ds, "UPDATE p:1 SET owner = person:2");
        accepted(ds, "CREATE p:4 SET owner = person:1");
        refused(ds, "CREATE p:5 SET owner = person:2");
    }

    @Test
    @DisplayName("UNIQUE construído sobre links existentes recusa o link repetido")
    void uniqueBuiltOverLinksRefusesRepeatedLink() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        run(ds, "DEFINE TABLE u SCHEMALESS");
        run(ds, "CREATE u:1 SET friend = person:1");
        run(ds, "CREATE u:2 SET friend = [person:2]");
        run(ds, "DEFINE INDEX friend ON TABLE u COLUMNS friend UNIQUE");

        refused(ds, "CREATE u:3 SET friend = person:1");
        refused(ds, "CREATE u:4 SET friend = [person:2]");
    }

    @Test
    @DisplayName("UNIQUE solta o link antigo depois de uma regravação e uma troca")
    void uniqueReleasesReplacedLink() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        run(ds, "DEFINE TABLE u SCHEMALESS");
        run(ds, "DEFINE INDEX friend ON TABLE u COLUMNS friend UNIQUE");
        run(ds, "CREATE u:1 SET friend = person:1");
        run(ds, "UPDATE u:1 SET name = 'x'");
        refused(ds, "CREATE u:2 SET friend = person:1");
        refused(ds, "CREATE u:3 SET friend = 'person:1'");

        run(ds, "UPDATE u:1 SET friend = person:2");
        accepted(ds, "CREATE u:4 SET friend = person:1");

        run(ds, "DELETE u:4");
        accepted(ds, "CREATE u:5 SET friend = person:1");
    }
}
