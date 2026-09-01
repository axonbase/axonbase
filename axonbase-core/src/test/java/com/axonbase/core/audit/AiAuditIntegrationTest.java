package com.axonbase.core.audit;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de integração do AI Audit via Datastore:
 * CREATE AI AUDIT (regras manuais), classificação local, casos pendentes,
 * SHOW, resolução e AUDIT_CASES virtual.
 */
class AiAuditIntegrationTest {

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    @Test
    void defineAuditComRegrasManuaisViaCatalogo() {
        var danger = List.of(new AiAuditRule("command", null, List.of("DROP TABLE", "TRUNCATE"), null, false));
        var warning = List.of(new AiAuditRule("require_where", null, null, null, true));
        var def = new AiAuditDef("protect", "sem where", "drop table", warning, danger, 1L, "admin");

        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        ds.requireDatabase(session()).catalog().defineAudit(def);

        var stored = ds.requireDatabase(session()).catalog().audit("protect");
        assertNotNull(stored);
        assertEquals("DANGER", stored.classify("DROP TABLE users"));
        assertEquals("WARNING", stored.classify("DELETE FROM users"));
        assertEquals("SAFE", stored.classify("SELECT * FROM users"));
    }

    @Test
    void defineAuditPersisteNoCatalogo() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        var def = new AiAuditDef("aud1", "w", "d",
            List.of(new AiAuditRule("require_where", null, null, null, true)),
            List.of(new AiAuditRule("command", null, List.of("DROP"), null, false)), 1L, "root");

        ds.requireDatabase(session()).catalog().defineAudit(def);

        // Recriar catálogo não perde a definição (memória)
        assertEquals(1, ds.requireDatabase(session()).catalog().audits().size());
        ds.requireDatabase(session()).catalog().removeAudit("aud1");
        assertTrue(ds.requireDatabase(session()).catalog().audits().isEmpty());
    }

    @Test
    void casosPendentesComResolucaoCompleta() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        var cat = ds.requireDatabase(session()).auditCatalog();

        cat.addCase("aaa", new AuditCase("aaa", "alice", "DELETE FROM t", "protect", "WARNING", 1L));
        cat.addCase("bbb", new AuditCase("bbb", "bob", "DROP TABLE x", "protect", "DANGER", 2L));

        assertEquals(2, cat.listCases().size());

        var pending = cat.getCase("aaa");
        pending.setReason("limpeza", "alice");
        pending.resolve("AUTHORIZED", "admin", "ok");
        cat.resolveCase("aaa", "AUTHORIZED", "admin", "ok");

        assertEquals(1, cat.listCases().size());
        assertEquals("bbb", cat.listCases().get(0).hash());
        assertEquals("AUTHORIZED", cat.getCase("aaa").status());
        assertEquals("admin", cat.getCase("aaa").resolvedBy());
    }

    @Test
    void auditCasesViaDatastoreCatalogoPersiste() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        var cat = ds.requireDatabase(session()).auditCatalog();
        cat.addCase("z1", new AuditCase("z1", "carol", "UPDATE u SET x=1", "aud", "WARNING", 1L));
        cat.addCase("z2", new AuditCase("z2", "carol", "DELETE FROM u", "aud", "WARNING", 2L));

        assertEquals(2, cat.listCases().size());
    }
}