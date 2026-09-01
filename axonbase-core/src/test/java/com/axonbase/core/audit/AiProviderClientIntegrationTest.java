package com.axonbase.core.audit;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderClientIntegrationTest {

    @Test
    void openAiGeraRegrasJsonValidas() {
        Assumptions.assumeTrue(Boolean.getBoolean("axon.integration.ai"),
            "Defina -Daxon.integration.ai=true para executar chamadas reais a provedores de IA.");
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY não configurada.");

        String model = System.getenv("OPENAI_MODEL");
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini";
        }
        var datastore = Datastore.memory();
        var client = new AiProviderClient("openai", model, apiKey,
            System.getenv("OPENAI_BASE_URL"));

        String rules = client.generateRules(
            "DELETE sem WHERE deve exigir aprovação.",
            "DROP TABLE e TRUNCATE devem ser bloqueados.",
            datastore.ensureDatabase("test", "dev"));

        var parsed = AxonJson.parseDocument(rules);
        assertTrue(parsed.isObject(), rules);
        assertTrue(parsed.asObject().containsKey("warning"), rules);
        assertTrue(parsed.asObject().containsKey("danger"), rules);
    }

    @Test
    void openAiCriaAuditEAutorizaOperacaoPendente() {
        Assumptions.assumeTrue(Boolean.getBoolean("axon.integration.ai"),
            "Defina -Daxon.integration.ai=true para executar chamadas reais a provedores de IA.");
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY não configurada.");

        String model = System.getenv("OPENAI_MODEL");
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini";
        }
        var datastore = Datastore.memory();
        datastore.aiProvider(new AiProviderClient("openai", model, apiKey,
            System.getenv("OPENAI_BASE_URL")));
        var session = Session.create();
        session.namespace("test");
        session.database("dev");
        datastore.createDatabase("test", "dev");

        datastore.execute("CREATE AI AUDIT protect SET WARNING WHEN 'Na lista warning, retorne a regra "
            + "{\"type\":\"require_where\"}. Ela classifica DELETE ou UPDATE do AxonQL sem WHERE.' "
            + "SET DANGER WHEN 'Na lista danger, retorne a regra {\"type\":\"command\",\"commands\":[\"DROP TABLE\"]}. '",
            session, null);

        var audit = datastore.requireDatabase(session).catalog().audit("protect");
        assertEquals("WARNING", audit.classify("DELETE people"));
        assertEquals("DANGER", audit.classify("DROP TABLE people"));

        session.auth(AxonValue.object(java.util.Map.of(
            "user", AxonValue.str("alice"),
            "audit", AxonValue.str("protect"))));
        datastore.execute("CREATE people:1 CONTENT {name: 'Alice'}", session, null);

        AxonError blocked = assertThrows(AxonError.class,
            () -> datastore.execute("DELETE people", session, null));
        assertEquals(-32003, blocked.code());

        var auditCase = datastore.requireDatabase(session).auditCatalog().listCases().getFirst();
        datastore.execute("SET REASON AUDIT CASE '" + auditCase.hash() + "' 'limpeza aprovada'", session, null);
        datastore.execute("SET AUDIT CASE '" + auditCase.hash()
            + "' AUTHORIZED REASON 'manutencao autorizada'", session, null);

        assertEquals("AUTHORIZED", datastore.requireDatabase(session).auditCatalog()
            .getCase(auditCase.hash()).status());
    }
}
