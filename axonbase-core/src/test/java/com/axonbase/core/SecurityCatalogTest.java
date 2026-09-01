package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void usuarioComCertificadoNaoTemSenhaUtilizavelEPersisteOBinding() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        Session s = Session.create();
        s.namespace("app");
        s.database("main");

        ds.execute("DEFINE USER alice ON DATABASE CERTIFICATE clients FINGERPRINT \"SHA256:ABCD\" "
            + "ROLES client", s, null);

        var user = ds.authCatalog().user("alice", "app", "main");
        assertNotNull(user);
        assertTrue(user.certificateBased());
        assertEquals("clients", user.certificate());
        assertEquals("SHA256:ABCD", user.fingerprint());
        assertNull(user.saltHex());
        assertNull(user.hashHex());
        assertNull(ds.authCatalog().verify("alice", "", "app", "main"));
        assertNull(ds.authCatalog().verify("alice", "qualquer-senha", "app", "main"));

        String definition = ds.controlSnapshot().commands().stream()
            .filter(command -> command.kind() == com.axonbase.core.control.ControlCommand.Kind.USER)
            .findFirst().orElseThrow().definition();
        assertTrue(definition.contains("CERTIFICATE clients FINGERPRINT \"SHA256:ABCD\""));
        assertFalse(definition.contains("PASSHASH"));
    }
}
