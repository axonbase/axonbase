package com.axonbase.core;

import com.axonbase.core.catalog.Database;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key-Value público do Datastore: round-trip, expiración preguizosa e funcións
 * {@code kv::} no motor.
 */
@DisplayName("Motor: key-value store público")
class KvStoreTest {

    private static final String NS = "app";
    private static final String DB = "kv";

    private Datastore ns() {
        Datastore ds = Datastore.memory();
        ds.createDatabase(NS, DB);
        return ds;
    }

    private Session session() {
        Session s = Session.create();
        s.namespace(NS);
        s.database(DB);
        return s;
    }

    @Test
    void roundTripSetGetDel() {
        Datastore ds = ns();
        Session s = session();

        assertEquals(AxonValue.num(42),
            ds.kvSet(s, NS, DB, "counter", AxonValue.num(42), null));
        assertEquals(42, ds.kvGet(s, NS, DB, "counter").asLong());

        assertEquals(AxonValue.str("hi"),
            ds.kvSet(s, NS, DB, "greeting", AxonValue.str("hi"), 3600L));
        assertEquals("hi", ds.kvGet(s, NS, DB, "greeting").asString());

        assertTrue(ds.kvDel(s, NS, DB, "counter"));
        assertTrue(ds.kvGet(s, NS, DB, "counter").isNone());
        assertFalse(ds.kvDel(s, NS, DB, "counter"));
    }

    @Test
    void ttlExpiradoEliminasePregueizamenteNoGet() {
        Datastore ds = ns();
        Session s = session();
        ds.kvSet(s, NS, DB, "session", AxonValue.str("alive"), 3600L);

        // Metadado de TTL vencido, escrito á beira da clave para non esperar.
        Database db = ds.ensureDatabase(NS, DB);
        long past = Instant.now().getEpochSecond() - 60;
        db.records().put("KV|" + NS + "|" + DB + "|ttl|k",
            String.valueOf(past).getBytes(StandardCharsets.UTF_8));

        assertTrue(ds.kvGet(s, NS, DB, "k").isNone());
        assertTrue(ds.kvScan(s, NS, DB, "k").asArray().isEmpty());
    }

    @Test
    void scanDevolveEntradasEValores() {
        Datastore ds = ns();
        Session s = session();
        ds.kvSet(s, NS, DB, "a:1", AxonValue.num(1), null);
        ds.kvSet(s, NS, DB, "a:2", AxonValue.str("two"), null);
        ds.kvSet(s, NS, DB, "b:1", AxonValue.num(10), null);

        AxonValue all = ds.kvScan(s, NS, DB, "");
        assertEquals(3, all.asArray().size());
        AxonValue onlyA = ds.kvScan(s, NS, DB, "a:");
        assertEquals(2, onlyA.asArray().size());
        boolean hasA2 = onlyA.asArray().stream()
            .anyMatch(e -> e.asObject().get("key").asString().equals("a:2"));
        assertTrue(hasA2);
    }

    @Test
    void funcaoKvNoMotorRoundTrip() {
        Datastore ds = ns();
        Session s = session();
        assertEquals("v", ds.execute("RETURN kv::set('k', 'v')", s, null).asString());
        assertEquals("v", ds.execute("RETURN kv::get('k')", s, null).asString());
        assertEquals(2, ds.execute(
            "LET $a = kv::set('s1', {x: 1}); LET $b = kv::set('s2', {x: 2}); RETURN kv::scan('s')",
            s, null).asArray().size());
        assertTrue(ds.execute("RETURN kv::del('k')", s, null).asBool());
        assertTrue(ds.execute("RETURN kv::get('k')", s, null).isNone());
    }
}