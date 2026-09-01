package com.axonbase.core.audit;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testa a intercepção real do SQL no executor:
 * usuário com AUDITED BY dispara classificação local em cada instrução.
 */
class AiAuditInterceptorTest {

    private Datastore newDs() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private void defineAudit(Datastore ds, String name) {
        ds.requireDatabase(session()).catalog().defineAudit(new AiAuditDef(
            name, "sem where", "danger",
            List.of(new AiAuditRule("require_where", null, null, null, true)),
            List.of(new AiAuditRule("command", null, List.of("DROP TABLE"), null, false)),
            System.currentTimeMillis(), "admin"));
    }

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private Session sessionAudited(String user, String auditName) {
        Session s = session();
        s.auth(AxonValue.object(java.util.Map.of(
            "user", AxonValue.str(user),
            "audit", AxonValue.str(auditName))));
        return s;
    }

    @Test
    void usuarioAuditadoBloqueiaDanger() {
        Datastore ds = newDs();
        // Danger rule que casa com "DELETE users" (command DELETE)
        ds.requireDatabase(session()).catalog().defineAudit(new AiAuditDef(
            "protect", "sem where", "danger",
            List.of(new AiAuditRule("require_where", null, null, null, true)),
            List.of(new AiAuditRule("command", null, List.of("DELETE"), null, false)),
            System.currentTimeMillis(), "admin"));
        Session s = sessionAudited("alice", "protect");

        // DELETE sem WHERE cai no DANGER (command DELETE) -> bloqueia
        Exception ex = assertThrows(RuntimeException.class, () ->
            ds.execute("DELETE users", s, null));
        assertTrue(ex.getMessage().contains("DANGER"), ex.getMessage());
        assertTrue(ds.requireDatabase(session()).auditCatalog().isBlocked("alice"));
    }

    @Test
    void usuarioAuditadoGeraWarningPendente() {
        Datastore ds = newDs();
        defineAudit(ds, "protect");
        Session s = sessionAudited("bob", "protect");

        // UPDATE sem WHERE: require_where warning (DANGER é só DROP TABLE)
        Exception ex = assertThrows(RuntimeException.class, () ->
            ds.execute("UPDATE users SET age = 1", s, null));
        assertTrue(ex.getMessage().contains("WARNING"), ex.getMessage());
        assertFalse(ds.requireDatabase(session()).auditCatalog().isBlocked("bob"));
        assertEquals(1, ds.requireDatabase(session()).auditCatalog().listCases().size());
        assertEquals("UPDATE users SET age = 1",
            ds.requireDatabase(session()).auditCatalog().listCases().get(0).sql());
    }

    @Test
    void usuarioNaoAutenticadoExecutaNormal() {
        Datastore ds = newDs();
        defineAudit(ds, "protect");
        Session s = session();
        // Sem auth — intercepção passa direto (sessionUserId = anonymous, sem audit)
        AxonValue result = ds.execute("DELETE users", s, null);
        assertNotNull(result);
    }

    @Test
    void usuarioComAuditInexistenteNaoIntercepta() {
        Datastore ds = newDs();
        Session s = sessionAudited("carol", "nao_existe");
        AxonValue result = ds.execute("DELETE users", s, null);
        assertNotNull(result);
    }

    @Test
    void resolverCasoAutorizadoExecutaSql() {
        Datastore ds = newDs();
        defineAudit(ds, "protect");
        Session s = sessionAudited("bob", "protect");

        assertThrows(RuntimeException.class, () -> ds.execute("UPDATE users SET age = 1", s, null));
        String hash = ds.requireDatabase(session()).auditCatalog().listCases().get(0).hash();

        var cat = ds.requireDatabase(session()).auditCatalog();
        cat.resolveCase(hash, "AUTHORIZED", "admin", "aprovado");

        assertTrue(cat.listCases().isEmpty());
        assertEquals("AUTHORIZED", cat.getCase(hash).status());
        assertEquals("admin", cat.getCase(hash).resolvedBy());
        assertEquals("aprovado", cat.getCase(hash).resolutionNote());
        assertEquals(1, cat.listHistory().size());
    }

    @Test
    void selectAuditCasesVirtualRetornaDados() {
        Datastore ds = newDs();
        defineAudit(ds, "protect");
        var cat = ds.requireDatabase(session()).auditCatalog();
        cat.addCase("h1", new AuditCase("h1", "alice", "DELETE FROM t",
            "protect", "WARNING", System.currentTimeMillis()));

        AxonValue result = ds.execute("SELECT * FROM AUDIT_CASES", session(), null);
        assertTrue(result.isArray());
        assertFalse(result.asArray().isEmpty(), "AUDIT_CASES deveria retornar o caso pendente");
    }
}