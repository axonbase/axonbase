package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Índice UNIQUE criado depois dos dados")
class UniqueIndexBackfillTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static Datastore store() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private static int rows(AxonValue result) {
        return result.isArray() ? result.asArray().size() : 1;
    }

    @Test
    @DisplayName("consulta pelo índice acha registros que já existiam")
    void selectFindsExistingRows() {
        Datastore ds = store();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT {email: 'a@x.com'}", s, null);
        ds.execute("CREATE t:2 CONTENT {email: 'b@x.com'}", s, null);

        ds.execute("CREATE INDEX e ON TABLE t COLUMNS email UNIQUE", s, null);

        assertEquals(1, rows(ds.execute("SELECT * FROM t WHERE email = 'a@x.com'", s, null)));
        assertEquals(0, rows(ds.execute("SELECT * FROM t WHERE email = 'zzz'", s, null)));
    }

    @Test
    @DisplayName("duplicata nova é barrada contra registro anterior ao índice")
    void newDuplicateIsRejected() {
        Datastore ds = store();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT {email: 'a@x.com'}", s, null);
        ds.execute("CREATE INDEX e ON TABLE t COLUMNS email UNIQUE", s, null);

        assertThrows(AxonError.class,
            () -> ds.execute("CREATE t:2 CONTENT {email: 'a@x.com'}", s, null));
    }

    @Test
    @DisplayName("criar o índice falha quando já existem valores duplicados e não registra o índice")
    void existingDuplicatesRejectTheIndex() {
        Datastore ds = store();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT {email: 'a@x.com'}", s, null);
        ds.execute("CREATE t:2 CONTENT {email: 'a@x.com'}", s, null);

        assertThrows(AxonError.class,
            () -> ds.execute("CREATE INDEX e ON TABLE t COLUMNS email UNIQUE", s, null));

        // sem índice registrado, a tabela continua aceitando o valor repetido
        ds.execute("CREATE t:3 CONTENT {email: 'a@x.com'}", s, null);
        assertEquals(3, rows(ds.execute("SELECT * FROM t WHERE email = 'a@x.com'", s, null)));
    }

    @Test
    @DisplayName("registros sem o campo não contam como duplicados")
    void missingValuesAreIgnored() {
        Datastore ds = store();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT {name: 'x'}", s, null);
        ds.execute("CREATE t:2 CONTENT {name: 'y'}", s, null);

        ds.execute("CREATE INDEX e ON TABLE t COLUMNS email UNIQUE", s, null);

        ds.execute("CREATE t:3 CONTENT {email: 'a@x.com'}", s, null);
        assertEquals(1, rows(ds.execute("SELECT * FROM t WHERE email = 'a@x.com'", s, null)));
    }

    @Test
    @DisplayName("índice composto criado depois dos dados também é preenchido e validado")
    void compositeIndex() {
        Datastore ds = store();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT {a: 1, b: 'x'}", s, null);
        ds.execute("CREATE t:2 CONTENT {a: 1, b: 'y'}", s, null);

        ds.execute("CREATE INDEX ab ON TABLE t COLUMNS a, b UNIQUE", s, null);

        assertThrows(AxonError.class,
            () -> ds.execute("CREATE t:3 CONTENT {a: 1, b: 'x'}", s, null));
        ds.execute("CREATE t:4 CONTENT {a: 2, b: 'x'}", s, null);
    }

    @Test
    @DisplayName("UPDATE e DELETE com WHERE no campo do índice tardio acham as linhas")
    void updateAndDeleteUseBackfilledIndex() {
        Datastore ds = store();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT {email: 'a@x.com', n: 1}", s, null);
        ds.execute("CREATE t:2 CONTENT {email: 'b@x.com', n: 2}", s, null);
        ds.execute("CREATE INDEX e ON TABLE t COLUMNS email UNIQUE", s, null);

        AxonValue updated = ds.execute("UPDATE t SET n = 9 WHERE email = 'a@x.com'", s, null);
        assertEquals(1, rows(updated));
        assertEquals(9, ds.execute("SELECT * FROM t:1", s, null).asObject().get("n").asLong());

        ds.execute("DELETE t WHERE email = 'b@x.com'", s, null);
        assertEquals(0, rows(ds.execute("SELECT * FROM t:2", s, null)));
    }

    @Test
    @DisplayName("depois do restart (replay do plano de controle) o índice continua correto")
    void survivesReplay() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        first.createDatabase("test", "dev");
        Session s = session();
        first.execute("CREATE t:1 CONTENT {email: 'a@x.com'}", s, null);
        first.execute("CREATE INDEX e ON TABLE t COLUMNS email UNIQUE", s, null);

        Datastore restarted = new Datastore(backend);
        Session s2 = session();

        assertEquals(1, rows(restarted.execute("SELECT * FROM t WHERE email = 'a@x.com'", s2, null)));
        assertThrows(AxonError.class,
            () -> restarted.execute("CREATE t:2 CONTENT {email: 'a@x.com'}", s2, null));
    }

    @Test
    @DisplayName("o caminho antigo DEFINE INDEX ... UNIQUE segue a mesma regra")
    void legacySyntaxBackfills() {
        Datastore ds = store();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT {email: 'a@x.com'}", s, null);
        ds.execute("DEFINE INDEX e ON TABLE t COLUMNS email UNIQUE", s, null);

        assertEquals(1, rows(ds.execute("SELECT * FROM t WHERE email = 'a@x.com'", s, null)));
    }
}
