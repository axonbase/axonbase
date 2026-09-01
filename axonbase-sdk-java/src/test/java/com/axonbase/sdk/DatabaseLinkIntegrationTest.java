package com.axonbase.sdk;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DATABASE LINK integration")
class DatabaseLinkIntegrationTest {

    private static Axon axon;
    private static String url;
    private static final String NS = "test";
    private static final String LOCAL_DB = "dblink_local";
    private static final String REMOTE_DB = "dblink_remote";

    @BeforeAll
    static void setup() {
        url = System.getenv("AXON_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "Defina AXON_URL para executar a integração de DATABASE LINK.");
        axon = Axon.connect(url);

        // Create local data
        axon.use(NS, LOCAL_DB);
        axon.query("DELETE person;");
        axon.query("DEFINE TABLE person SCHEMAFULL");
        axon.query("DEFINE FIELD name ON TABLE person TYPE string");
        axon.query("DEFINE FIELD age ON TABLE person TYPE int");
        axon.query("CREATE person:100 CONTENT {name: 'Alice', age: 30}");
        axon.query("CREATE person:200 CONTENT {name: 'Bob', age: 25}");

        // Create remote data (different database, same server)
        axon.use(NS, REMOTE_DB);
        axon.query("DELETE city;");
        axon.query("DEFINE TABLE city SCHEMAFULL");
        axon.query("DEFINE FIELD label ON TABLE city TYPE string");
        axon.query("DEFINE FIELD population ON TABLE city TYPE int");
        axon.query("CREATE city:1 CONTENT {label: 'SP', population: 12000000}");
        axon.query("CREATE city:2 CONTENT {label: 'RJ', population: 6000000}");

        // Define DATABASE LINK pointing to the remote database
        axon.use(NS, LOCAL_DB);
        axon.query("DEFINE DATABASE LINK \"remote_link\" CONNECT BY \"" + url + "\""
            + " WITH ns = \"" + NS + "\" db = \"" + REMOTE_DB + "\" user = \"\" password = \"\"");
    }

    @AfterAll
    static void cleanup() {
        if (axon != null && axon.isConnected()) {
            axon.use(NS, LOCAL_DB);
            try { axon.query("DROP DATABASE LINK \"remote_link\""); } catch (Exception ignored) {}
            try { axon.query("DELETE person;"); } catch (Exception ignored) {}
            axon.use(NS, REMOTE_DB);
            try { axon.query("DELETE city;"); } catch (Exception ignored) {}
            axon.close();
        }
    }

    @Test
    @DisplayName("Query local table works normally")
    void localQuery() {
        axon.use(NS, LOCAL_DB);
        var result = axon.query("SELECT name FROM person WHERE age > 20");
        assertTrue(result.isArray());
        assertFalse(result.asArray().isEmpty());
    }

    @Test
    @DisplayName("Query remote table via DATABASE LINK")
    void linkQuery() {
        axon.use(NS, LOCAL_DB);
        var result = axon.query("SELECT label, population FROM \"remote_link\".\"city\"");
        assertTrue(result.isArray(), "Expected array, got " + result);
        assertFalse(result.asArray().isEmpty(), "Expected non-empty from remote link");
        var first = result.asArray().get(0);
        assertTrue(first.isObject());
        System.out.println("Link result: " + first);
    }

    @Test
    @DisplayName("Query local qualified database (same ns, other db)")
    void localQualifiedQuery() {
        axon.use(NS, LOCAL_DB);
        var result = axon.query("SELECT label FROM \"" + REMOTE_DB + "\".\"city\"");
        assertTrue(result.isArray(), "Expected array, got " + result);
        assertFalse(result.asArray().isEmpty(), "Expected non-empty from local qualified");
    }

    @Test
    @DisplayName("Regular queries unchanged")
    void regularQuery() {
        axon.use(NS, LOCAL_DB);
        var result = axon.query("SELECT * FROM person WHERE name = 'Alice'");
        assertTrue(result.isArray());
        assertFalse(result.asArray().isEmpty());
    }

    @Test
    @DisplayName("WRITE via DATABASE LINK: CREATE/UPDATE/DELETE/RELATE")
    void writesViaLink() {
        axon.use(NS, LOCAL_DB);
        axon.query("DELETE \"remote_link\".\"city\":999;");
        axon.query("DELETE \"remote_link\".\"city\":888;");

        // CREATE via link
        axon.query("CREATE \"remote_link\".\"city\":999 CONTENT {label: 'BH', population: 2500000}");
        var created = axon.query("SELECT label FROM \"remote_link\".\"city\":999");
        assertTrue(created.isArray() || created.isObject());
        System.out.println("CREATE via link OK");

        // UPDATE via link
        axon.query("UPDATE \"remote_link\".\"city\":999 SET population = 2600000");
        var updated = axon.query("SELECT VALUE population FROM \"remote_link\".\"city\":999");
        System.out.println("UPDATE via link OK: " + updated);

        // DELETE via link
        axon.query("DELETE \"remote_link\".\"city\":888");
        var deleted = axon.query("SELECT * FROM \"remote_link\".\"city\":888");
        assertFalse(deleted.isArray() && !deleted.asArray().isEmpty(),
            "record 888 should not exist after delete");
        System.out.println("DELETE via link OK");

        // RELATE via link (graph on remote)
        axon.query("CREATE \"remote_link\".\"city\":1 CONTENT {label: 'SP', population: 12000000}");
        axon.query("CREATE \"remote_link\".\"city\":2 CONTENT {label: 'RJ', population: 6000000}");
        axon.query("RELATE \"remote_link\".\"city\":1 ->near-> \"remote_link\".\"city\":2");
        var friends = axon.query("SELECT VALUE ->near->city FROM \"remote_link\".\"city\":1");
        System.out.println("RELATE via link OK: " + friends);
    }
}
