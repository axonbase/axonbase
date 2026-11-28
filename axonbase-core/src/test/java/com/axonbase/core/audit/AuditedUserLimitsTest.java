package com.axonbase.core.audit;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Um usuário auditado, mesmo administrador, não decide o próprio caso nem altera a auditoria. */
class AuditedUserLimitsTest {

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Map<String, AxonValue> claims(String id, String audit) {
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("app"));
        claims.put("db", AxonValue.str("main"));
        claims.put("roles", AxonValue.str("admin"));
        if (audit != null) {
            claims.put("audit", AxonValue.str(audit));
        }
        return claims;
    }

    private static Session as(String id, String audit) {
        Session s = local();
        s.auth(AxonValue.object(claims(id, audit)));
        return s;
    }

    private static Session audited() {
        return as("ana", "prot");
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.requireDatabase(local()).catalog().defineAudit(new AiAuditDef("prot", "w", "d",
            List.of(), List.of(), 1L, "root"));
        ds.execute("CREATE USER bia ON DATABASE PASSWORD 'Test-pw-1' ROLES editor AUDITED BY prot", local(), null);
        return ds;
    }

    private static AiAuditCatalog cases(Datastore ds) {
        return ds.requireDatabase(local()).auditCatalog();
    }

    private static void pending(Datastore ds, String hash, String user, String classification) {
        cases(ds).addCase(hash, new AuditCase(hash, user, "RETURN 1", "prot", classification, 1L,
            new AuditAuthor(AxonValue.object(claims(user, "prot")), Map.of(), false)));
    }

    private static void assertAuditedRefusal(Datastore ds, String query, Session s) {
        AxonError refused = assertThrows(AxonError.class, () -> ds.execute(query, s, null), query);
        assertTrue(refused.getMessage().contains("auditad") || refused.getMessage().contains("audited"),
            refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHORIZED", "DENIED"})
    void naoDecideOProprioCaso(String decision) {
        Datastore ds = ds();
        pending(ds, "h1", "ana", "WARNING");

        assertAuditedRefusal(ds, "SET AUDIT CASE \"h1\" " + decision + " REASON \"eu mesma\"", audited());

        assertEquals("PENDING", cases(ds).getCase("h1").status());
    }

    @Test
    void naoDesbloqueiaOutroUsuario() {
        Datastore ds = ds();
        pending(ds, "h2", "bia", "DANGER");
        cases(ds).blockUser("bia", "prot", "RETURN 1", "perigo", "sys");

        assertAuditedRefusal(ds, "SET AUDIT CASE \"h2\" AUTHORIZED REASON \"ok\"", audited());

        assertTrue(cases(ds).isBlocked("bia"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DROP AI AUDIT prot",
        "CREATE AI AUDIT outra SET WARNING WHEN 'a' SET DANGER WHEN 'b'",
        "CREATE USER bia ON DATABASE PASSWORD 'Test-pw-1' ROLES editor",
        "CREATE USER livre ON DATABASE PASSWORD 'Test-pw-1' ROLES admin",
        "DROP USER bia"})
    void naoAlteraAuditoriaNemUsuarios(String statement) {
        Datastore ds = ds();

        assertAuditedRefusal(ds, statement, audited());

        assertNotNull(ds.requireDatabase(local()).catalog().audit("prot"));
        assertEquals("prot", ds.authCatalog().user("bia", "app", "main").auditName());
        assertNull(ds.authCatalog().user("livre", "app", "main"));
    }

    @Test
    void justificaSoOProprioCaso() {
        Datastore ds = ds();
        pending(ds, "meu", "ana", "WARNING");
        pending(ds, "alheio", "bia", "WARNING");

        ds.execute("SET REASON AUDIT CASE \"meu\" \"manutenção\"", audited(), null);

        assertAuditedRefusal(ds, "SET REASON AUDIT CASE \"alheio\" \"outra\"", audited());
        assertEquals("manutenção", cases(ds).getCase("meu").reason());
    }

    @Test
    void administradorNaoAuditadoContinuaDecidindoEAlterando() {
        Datastore ds = ds();
        pending(ds, "h3", "bia", "DANGER");
        cases(ds).blockUser("bia", "prot", "RETURN 1", "perigo", "sys");

        ds.execute("SET AUDIT CASE \"h3\" AUTHORIZED REASON \"ok\"", as("chefe", null), null);
        ds.execute("DROP AI AUDIT prot", as("chefe", null), null);

        assertTrue(!cases(ds).isBlocked("bia"));
        assertNull(ds.requireDatabase(local()).catalog().audit("prot"));
    }
}
