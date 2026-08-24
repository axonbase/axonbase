package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Motor: INFO e metadados")
class MetadataTest {

    private Datastore datastore() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.createDatabase("app", "analytics");
        return ds;
    }

    private Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    @Test
    void infoRootENamespaceListaBases() {
        Datastore ds = datastore();
        Session s = session();

        AxonValue root = ds.execute("INFO FOR ROOT", s, null);
        assertEquals("app", root.asObject().get("namespaces").asArray().get(0).asString());
        assertTrue(root.asObject().get("functions").asArray().size() >= 100);

        AxonValue ns = ds.execute("INFO FOR NAMESPACE", s, null);
        assertEquals("app", ns.asObject().get("namespace").asString());
        assertEquals(2, ns.asObject().get("databases").asArray().size());
    }

    @Test
    void infoDatabaseETableDescrevemCatalogo() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE TABLE person SCHEMAFULL", s, null);
        ds.execute("DEFINE FIELD email ON TABLE person TYPE string ASSERT $value CONTAINS \"@\"", s, null);
        ds.execute("DEFINE INDEX email ON TABLE person COLUMNS email UNIQUE", s, null);
        ds.execute("DEFINE EVENT audit ON TABLE person WHEN true THEN (RETURN 1)", s, null);

        AxonValue db = ds.execute("INFO FOR DATABASE", s, null);
        assertEquals("person", db.asObject().get("tables").asArray().get(0).asString());

        AxonValue table = ds.execute("INFO FOR TABLE person", s, null);
        assertEquals("SCHEMAFULL", table.asObject().get("schema").asString());
        assertEquals("string", table.asObject().get("fields").asObject()
            .get("email").asObject().get("type").asString());
        assertTrue(table.asObject().get("indexes").asObject().get("email").asObject()
            .get("unique").asBool());
        assertEquals(1, table.asObject().get("events").asObject().get("audit").asObject()
            .get("then").asLong());
    }
}
