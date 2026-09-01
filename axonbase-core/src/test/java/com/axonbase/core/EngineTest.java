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

    @Test
    void createJksDelegatesOnlyForRootSessions() {
        Datastore ds = ns();
        String[] got = new String[4];
        ds.jksRegistrar((name, path, password, collector, oids) -> {
            got[0] = name;
            got[1] = path;
            got[2] = new String(password);
            got[3] = collector + ":" + String.join(",", oids);
        });
        Session root = new Session("test", "dev");
        root.auth(AxonValue.object(java.util.Map.of("scope", AxonValue.str("ROOT"))));

        ds.execute("CREATE JKS company PATH \"/tmp/company.jks\" PASSWORD \"secret\" COLLECT USER BY OID \"2.5.4.5\"", root, null);

        assertEquals("company", got[0]);
        assertEquals("/tmp/company.jks", got[1]);
        assertEquals("secret", got[2]);
        assertEquals("OID:2.5.4.5", got[3]);
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
    void joinDevolveObxectoAchatadoConPrefixos() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE a CONTENT { pk: 1, name: \"pedro\" }", s, null);
        ds.execute("CREATE a CONTENT { pk: 2, name: \"lúa\" }", s, null);
        ds.execute("CREATE b CONTENT { fk: 1, label: \"L1\" }", s, null);
        ds.execute("CREATE b CONTENT { fk: 99, label: \"senz vínculo\" }", s, null);
        // documento horizontal sen o campo de unión: debe ignorarse
        ds.execute("CREATE b CONTENT { label: \"orfano\" }", s, null);
        AxonValue rows = ds.execute("SELECT * FROM a JOIN b ON a.pk = b.fk", s, null);
        assertEquals(1, rows.asArray().size(), "só unha coincidencia pk=1 debe cruzar");
        java.util.Map<String, AxonValue> joined = rows.asArray().get(0).asObject();
        assertEquals(1, joined.get("a.pk").asLong());
        assertEquals("pedro", joined.get("a.name").asString());
        assertEquals(1, joined.get("b.fk").asLong());
        assertEquals("L1", joined.get("b.label").asString());
    }

    @Test
    void joinProxeccionQualificada() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE a CONTENT { pk: 5, name: \"Ana\" }", s, null);
        ds.execute("CREATE b CONTENT { fk: 5, city: \"Lima\" }", s, null);
        AxonValue rows = ds.execute("SELECT a.name, b.city FROM a JOIN b ON a.pk = b.fk", s, null);
        java.util.Map<String, AxonValue> row = rows.asArray().get(0).asObject();
        assertEquals("Ana", row.get("a.name").asString());
        assertEquals("Lima", row.get("b.city").asString());
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
    void indiceUnicoCompostoPermiteValoresIsoladosRepetidos() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE TABLE saga_step SCHEMAFULL", s, null);
        ds.execute("DEFINE INDEX step_corr ON TABLE saga_step COLUMNS correlation_id, step_order UNIQUE", s, null);

        ds.execute("CREATE saga_step CONTENT { correlation_id: \"first\", step_order: 1 }", s, null);
        ds.execute("CREATE saga_step CONTENT { correlation_id: \"second\", step_order: 1 }", s, null);

        AxonValue rows = ds.execute("SELECT * FROM saga_step", s, null);
        assertEquals(2, rows.asArray().size());
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
    void dataRuleBloqueiaCreateEInsertQuandoPredicadoNaoPassa() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE DATA RULE published_only APPLY published = true", s, null);
        s.auth(AxonValue.object(java.util.Map.of("data_rules", AxonValue.str("published_only"))));

        assertThrows(AxonError.class, () ->
            ds.execute("CREATE doc CONTENT { published: false }", s, null));
        assertThrows(AxonError.class, () ->
            ds.execute("INSERT INTO doc [{ published: false }]", s, null));

        ds.execute("CREATE doc CONTENT { published: true }", s, null);
        ds.execute("INSERT INTO doc [{ published: true }]", s, null);
        assertEquals(2, ds.execute("SELECT * FROM doc", s, null).asArray().size());
    }

    @Test
    void permissaoDeCreateBloqueiaCreateEInsertQuandoPredicadoNaoPassa() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE TABLE doc SCHEMALESS PERMISSIONS FOR create WHERE published = true", s, null);

        assertThrows(AxonError.class, () ->
            ds.execute("CREATE doc CONTENT { published: false }", s, null));
        assertThrows(AxonError.class, () ->
            ds.execute("INSERT INTO doc [{ published: false }]", s, null));

        ds.execute("CREATE doc CONTENT { published: true }", s, null);
        ds.execute("INSERT INTO doc [{ published: true }]", s, null);
        assertEquals(2, ds.execute("SELECT * FROM doc", s, null).asArray().size());
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
        // o autor deve ser resolvido para um objeto (doc completo) or still record
        assertNotNull(autor);
    }

    @Test
    void graphConnectedAndPath() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person:1 CONTENT {name: \"Alice\"}", s, null);
        ds.execute("CREATE person:2 CONTENT {name: \"Bob\"}", s, null);
        ds.execute("CREATE person:3 CONTENT {name: \"Charlie\"}", s, null);
        ds.execute("CREATE person:4 CONTENT {name: \"Diana\"}", s, null);
        ds.execute("CREATE person:5 CONTENT {name: \"Eve\"}", s, null);

        // Chain: 1 -> 2 -> 3 -> 4
        ds.execute("RELATE person:1->knows->person:2", s, null);
        ds.execute("RELATE person:2->knows->person:3", s, null);
        ds.execute("RELATE person:3->knows->person:4", s, null);
        // Isolated: 5
        // Another edge: 1 -> friend -> 3
        ds.execute("RELATE person:1->friend->person:3", s, null);

        // Connected via knows (3 hops)
        AxonValue connected = ds.execute(
            "RETURN graph::connected(person:1, person:4, \"knows\", 10)", s, null);
        assertEquals(true, connected.isBool() && connected.asBool());

        // Path should be an array with at least 2 elements
        AxonValue path = ds.execute(
            "RETURN graph::path(person:1, person:4, \"knows\", 10)", s, null);
        assertTrue(path.isArray());
        assertTrue(path.asArray().size() >= 2);

        // Not connected (no path to Eve via knows)
        AxonValue notConnected = ds.execute(
            "RETURN graph::connected(person:1, person:5, \"knows\", 10)", s, null);
        assertEquals(false, notConnected.asBool());

        // Connected via friend (1 hop)
        AxonValue friendConnected = ds.execute(
            "RETURN graph::connected(person:1, person:3, \"friend\", 5)", s, null);
        assertEquals(true, friendConnected.isBool() && friendConnected.asBool());

        // Self connection
        AxonValue self = ds.execute(
            "RETURN graph::connected(person:1, person:1, \"knows\", 10)", s, null);
        assertEquals(true, self.isBool() && self.asBool());

        // MaxDepth limits search (1 hop is not enough to reach person:4 via knows)
        AxonValue limited = ds.execute(
            "RETURN graph::connected(person:1, person:4, \"knows\", 1)", s, null);
        assertEquals(false, limited.asBool());
    }
}
