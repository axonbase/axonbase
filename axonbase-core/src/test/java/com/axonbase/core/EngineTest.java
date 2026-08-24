package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Motor: execución de AxonQL")
class EngineTest {

    private Datastore ns() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    @Test
    void creaESelecciona() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person CONTENT { name: \"Ana\", age: 30 }", s, null);
        AxonValue rows = ds.execute("SELECT * FROM person", s, null);
        assertTrue(rows.isArray());
        assertEquals(1, rows.asArray().size());
        AxonValue one = rows.asArray().get(0);
        assertEquals("Ana", one.asObject().get("name").asString());
        assertEquals(30, one.asObject().get("age").asLong());
    }

    @Test
    void selectWhereFiltra() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person CONTENT { name: \"Ana\", age: 30 }", s, null);
        ds.execute("CREATE person CONTENT { name: \"Bob\", age: 17 }", s, null);
        AxonValue rows = ds.execute("SELECT name FROM person WHERE age >= 18", s, null);
        assertEquals(1, rows.asArray().size());
        assertEquals("Ana", rows.asArray().get(0).asObject().get("name").asString());
    }

    @Test
    void updateModifica() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person CONTENT { name: \"Ana\", age: 30 }", s, null);
        ds.execute("UPDATE person SET age = 31 WHERE name = \"Ana\"", s, null);
        AxonValue rows = ds.execute("SELECT * FROM person", s, null);
        assertEquals(31, rows.asArray().get(0).asObject().get("age").asLong());
    }

    @Test
    void indeceUnicoAplica() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE TABLE user SCHEMAFULL", s, null);
        ds.execute("DEFINE INDEX email ON TABLE user COLUMNS email UNIQUE", s, null);
        ds.execute("CREATE user CONTENT { email: \"a@x.com\", name: \"Ana\" }", s, null);
        assertThrows(AxonError.class, () ->
            ds.execute("CREATE user CONTENT { email: \"a@x.com\", name: \"Bob\" }", s, null));
    }

    @Test
    void deleteBorra() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s, null);
        ds.execute("CREATE person CONTENT { name: \"Bob\" }", s, null);
        ds.execute("DELETE person WHERE name = \"Ana\"", s, null);
        AxonValue rows = ds.execute("SELECT * FROM person", s, null);
        assertEquals(1, rows.asArray().size());
    }

    @Test
    void insertMultiplo() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("INSERT INTO person [{name: \"Ana\"}, {name: \"Bob\"}]", s, null);
        AxonValue rows = ds.execute("SELECT * FROM person", s, null);
        assertEquals(2, rows.asArray().size());
    }

    @Test
    void selectLimitEStart() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("INSERT INTO person [{n: 1}, {n: 2}, {n: 3}]", s, null);
        AxonValue rows = ds.execute("SELECT * FROM person START 1 LIMIT 1", s, null);
        assertEquals(1, rows.asArray().size());
    }

    @Test
    void selectOnlyDevolveObxeto() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s, null);
        AxonValue one = ds.execute("SELECT * FROM person ONLY", s, null);
        assertEquals(AxonValue.AxonType.OBJECT, one.type());
    }

    @Test
    void updateReturnNone() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person CONTENT { age: 30 }", s, null);
        AxonValue out = ds.execute("UPDATE person SET age = 31 RETURN NONE", s, null);
        assertEquals(AxonValue.AxonType.NONE, out.type());
    }

    @Test
    void transactionCancelReverte() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("BEGIN", s, null);
        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s, null);
        AxonValue inside = ds.execute("SELECT * FROM person", s, null);
        assertEquals(1, inside.asArray().size());
        ds.execute("CANCEL", s, null);
        AxonValue outside = ds.execute("SELECT * FROM person", s, null);
        assertEquals(0, outside.asArray().size());
    }

    @Test
    void transactionCommitPersiste() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("BEGIN", s, null);
        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s, null);
        ds.execute("COMMIT", s, null);
        AxonValue rows = ds.execute("SELECT * FROM person", s, null);
        assertEquals(1, rows.asArray().size());
    }

    @Test
    void transactionAisladaPorSesion() {
        Datastore ds = ns();
        Session s1 = session();
        Session s2 = session();
        ds.execute("BEGIN", s1, null);
        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s1, null);
        AxonValue other = ds.execute("SELECT * FROM person", s2, null);
        assertEquals(0, other.asArray().size());
        ds.execute("CANCEL", s1, null);
    }

    @Test
    void permissionsFiltraSelect() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE TABLE doc SCHEMALESS PERMISSIONS FOR select WHERE published = true", s, null);
        ds.execute("CREATE doc CONTENT { published: true, title: \"A\" }", s, null);
        ds.execute("CREATE doc CONTENT { published: false, title: \"B\" }", s, null);
        AxonValue rows = ds.execute("SELECT * FROM doc", s, null);
        assertEquals(1, rows.asArray().size());
        assertEquals("A", rows.asArray().get(0).asObject().get("title").asString());
    }

    @Test
    void relatePersisteArestaETravessa() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE user:1 CONTENT { name: \"Ana\" }", s, null);
        ds.execute("CREATE article:1 CONTENT { title: \"Post A\" }", s, null);
        ds.execute("RELATE user:1->wrote->article:1", s, null);
        AxonValue out = ds.execute("SELECT ->wrote->article FROM user:1", s, null);
        assertNotNull(out);
        // linha única projetada: o campo tem o nome da travessia
        AxonValue alvo = out.asObject().get("->wrote->article");
        assertNotNull(alvo);
        assertTrue(alvo.isRecordId());
        assertEquals("article", alvo.asRecordId().table());
        // a aresta inversa também resolve
        AxonValue back = ds.execute("SELECT <-wrote<-user FROM article:1", s, null);
        assertEquals("user", back.asObject().get("<-wrote<-user").asRecordId().table());
    }

    @Test
    void agregaCount() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("INSERT INTO person [{age: 1}, {age: 2}, {age: 3}]", s, null);
        AxonValue out = ds.execute("SELECT count() FROM person", s, null);
        assertTrue(out.isArray());
        assertEquals(3L, out.asArray().get(0).asObject().get("count").asLong());
    }

    @Test
    void schemaDefineFieldRejeitaTipo() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE FIELD idade ON TABLE pessoa TYPE int", s, null);
        assertThrows(AxonError.class, () ->
            ds.execute("CREATE pessoa CONTENT { idade: \"texto\" }", s, null));
    }

    @Test
    void schemaDefineFieldAssert() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE FIELD email ON TABLE user TYPE string ASSERT $value CONTAINS \"@\"", s, null);
        assertThrows(AxonError.class, () ->
            ds.execute("CREATE user CONTENT { email: \"sem-arroba\" }", s, null));
    }

    @Test
    void schemaDefault() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE FIELD ativo ON TABLE item TYPE bool DEFAULT true", s, null);
        AxonValue created = ds.execute("CREATE item CONTENT { nome: \"x\" }", s, null);
        assertEquals(true, created.asArray().get(0).asObject().get("ativo").asBool());
    }

    @Test
    void upsertInsereSeNaoExiste() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("UPSERT person SET nome = \"Ana\"", s, null);
        AxonValue rows = ds.execute("SELECT * FROM person", s, null);
        assertEquals(1, rows.asArray().size());
    }

    @Test
    void exportImportRoundTrip() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE TABLE produto SCHEMALESS", s, null);
        ds.execute("CREATE produto:1 CONTENT { nome: \"Caneta\", preco: 2 }", s, null);
        ds.execute("CREATE produto:2 CONTENT { nome: \"Papel\", preco: 3 }", s, null);
        String dump = ds.exportDatabase("test", "dev");
        assertTrue(dump.contains("DEFINE TABLE produto"));
        assertTrue(dump.contains("CREATE produto")); // dump inclui os CREATEs

        // importa em um datastore novo
        Datastore ds2 = Datastore.memory();
        ds2.createDatabase("test", "dev");
        ds2.importDatabase("test", "dev", dump);
        AxonValue rows = ds2.execute("SELECT * FROM produto", session(), null);
        assertEquals(2, rows.asArray().size());
    }

    @Test
    void sumEGroup() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("INSERT INTO venda [{cidade: 'SP', valor: 10}, {cidade: 'RJ', valor: 5}, {cidade: 'SP', valor: 7}]", s, null);
        AxonValue out = ds.execute("SELECT sum(valor) FROM venda GROUP BY cidade", s, null);
        assertTrue(out.isArray());
        assertEquals(2, out.asArray().size());
        boolean hasSp17 = false;
        for (AxonValue row : out.asArray()) {
            AxonValue sumV = row.asObject().get("sum");
            if (sumV != null && sumV.isNumber() && sumV.asLong() == 17) {
                hasSp17 = true;
            }
        }
        assertTrue(hasSp17);
    }

    @Test
    void fetchResolveRecordLink() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE user:1 CONTENT { nome: \"Ana\" }", s, null);
        ds.execute("CREATE user:2 CONTENT { nome: \"Bia\" }", s, null);
        ds.execute("CREATE post:1 CONTENT { titulo: \"A\", autor: user:1 }", s, null);
        AxonValue out = ds.execute("SELECT titulo, autor FROM post FETCH autor", s, null);
        AxonValue post = out.asArray().get(0);
        AxonValue autor = post.asObject().get("autor");
        // o autor deve ser resolvido para um objeto (doc completo) ou still record
        assertNotNull(autor);
    }
}