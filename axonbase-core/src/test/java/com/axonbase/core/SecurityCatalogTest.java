package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SecurityCatalogTest {

    @Test
    void defineUserEAccessGuardamEscopoESenhaComHash() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        Session s = Session.create();
        s.namespace("app");
        s.database("main");

        ds.execute("DEFINE USER alice ON DATABASE PASSWORD \"secreta\" ROLES editor, writer", s, null);
        ds.execute("DEFINE ACCESS app_login ON DATABASE", s, null);

        var user = ds.authCatalog().verify("alice", "secreta", "app", "main");
        assertNotNull(user);
        assertEquals("DATABASE", user.scope().name());
        assertEquals("app", user.namespace());
        assertEquals("main", user.database());
        assertEquals(2, user.roles().size());
        assertNull(ds.authCatalog().verify("alice", "secreta", "app", "other"));
        assertNull(ds.authCatalog().verify("alice", "errada", "app", "main"));
        assertNotNull(ds.authCatalog().access("app_login", "app", "main"));
    }
}
