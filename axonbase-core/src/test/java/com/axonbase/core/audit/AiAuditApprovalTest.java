package com.axonbase.core.audit;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.FileBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** O comando aprovado roda com a identidade do autor, não com a do aprovador. */
class AiAuditApprovalTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session as(String id, String scope, String roles, String audit) {
        Session s = session();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str(scope));
        if (!"ROOT".equals(scope)) {
            claims.put("ns", AxonValue.str("app"));
            claims.put("db", AxonValue.str("main"));
        }
        claims.put("roles", AxonValue.str(roles));
        if (audit != null) {
            claims.put("audit", AxonValue.str(audit));
        }
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static void defineAudit(Datastore ds) {
        ds.requireDatabase(session()).catalog().defineAudit(new AiAuditDef("prot", "w", "d",
            List.of(new AiAuditRule("keyword", null, null, List.of("CREATE USER", "DELETE"), null)),
            List.of(), 1L, "root"));
    }

    private static String pendingHash(Datastore ds) {
        return ds.requireDatabase(session()).auditCatalog().listCases().get(0).hash();
    }

    private static String approval(String hash) {
        return "SET AUDIT CASE \"" + hash + "\" AUTHORIZED REASON \"ok\"";
    }

    @Test
    void aprovacaoDepoisDoReinicioUsaOsClaimsDoAutor(@TempDir Path dir) {
        String path = dir.resolve("audit.db").toString();
        Datastore first = new Datastore(new FileBackend(path));
        first.createDatabase("app", "main");
        defineAudit(first);
        assertThrows(AxonError.class, () -> first.execute(
            "CREATE USER intruso ON ROOT PASSWORD 'Test-pw-1' ROLES admin",
            as("autor", "DATABASE", "editor", "prot"), null));
        String hash = pendingHash(first);

        Datastore recovered = new Datastore(new FileBackend(path));
        assertThrows(AxonError.class,
            () -> recovered.execute(approval(hash), as("chefe", "ROOT", "admin", null), null));
        assertNull(recovered.authCatalog().user("intruso", null, null));
    }

    @Test
    void aprovacaoDentroDeTransacaoCanceladaNaoGrava() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.execute("CREATE t:1 CONTENT {v: 1}", session(), null);
        defineAudit(ds);
        assertThrows(AxonError.class,
            () -> ds.execute("DELETE t", as("autor", "DATABASE", "editor", "prot"), null));
        Session approver = as("chefe", "ROOT", "admin", null);
        ds.execute("BEGIN", approver, null);
        ds.execute(approval(pendingHash(ds)), approver, null);
        ds.execute("CANCEL", approver, null);
        assertEquals(1, ds.execute("SELECT * FROM t", session(), null).asArray().size());
    }
}
