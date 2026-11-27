package com.axonbase.core.security;

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

/**
 * Uma expressão de esquema ou de política é escrita por quem define e avaliada na sessão de quem grava ou consulta.
 * Por isso ela só lê, e só o banco da tabela. O mesmo vale para o corpo de uma função e para o filtro de um LIVE.
 */
class DefinitionExpressionScopeTest {

    private static final String ROOT_USER = "CREATE USER intruso ON ROOT PASSWORD 'Test-pw-1' ROLES admin";

    /** Subconsulta que, depois de ler, cria um usuário root. */
    private static final String PAYLOAD = "(SELECT VALUE v FROM semente; " + ROOT_USER + ")";

    /** Numa Data Rule a leitura é de uma tabela vazia: sobre linhas, a regra se aplicaria à própria subconsulta. */
    private static final String RULE_PAYLOAD = "(SELECT VALUE v FROM vazia; " + ROOT_USER + ")";

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session session(String id, String scope, String roles, String... dataRules) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str(scope));
        if (!"ROOT".equals(scope)) {
            claims.put("ns", AxonValue.str("app"));
            claims.put("db", AxonValue.str("main"));
        }
        claims.put("roles", AxonValue.str(roles));
        if (dataRules.length > 0) {
            claims.put("data_rules", AxonValue.array(List.of(dataRules).stream().map(AxonValue::str).toList()));
        }
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Session root() {
        return session("root", "ROOT", "admin");
    }

    private static Session databaseAdmin() {
        return session("chefe", "DATABASE", "admin");
    }

    private static Session editor() {
        return session("editor", "DATABASE", "editor");
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.createDatabase("app", "outro");
        Session other = local();
        other.database("outro");
        ds.execute("CREATE segredo:1 CONTENT {v: 'alheio'}", other, null);
        ds.execute("DEFINE TABLE livre SCHEMALESS; CREATE semente:1 CONTENT {v: 1}", local(), null);
        return ds;
    }

    private static void assertNoIntruder(Datastore ds) {
        assertNull(ds.authCatalog().user("intruso", null, null));
        assertNull(ds.authCatalog().user("intruso", "app", null));
        assertNull(ds.authCatalog().user("intruso", "app", "main"));
    }

    private static void assertScopeRefusal(Datastore ds, String statement, Session s) {
        RuntimeException refused = assertThrows(RuntimeException.class, () -> ds.execute(statement, s, null));
        assertTrue(refused.getMessage().contains("app/main"), refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DEFINE FIELD nota ON TABLE livre DEFAULT " + PAYLOAD,
        "DEFINE FIELD v ON TABLE livre ASSERT " + PAYLOAD + " != 1",
        "CREATE TABLE isca (v NUMBER, CHECK (v != " + PAYLOAD + "))"})
    void esquemaEscritoPeloEditorNaoAgeComOPoderDoRoot(String definition) {
        Datastore ds = ds();
        ds.execute(definition, editor(), null);
        String table = definition.contains("isca") ? "isca" : "livre";
        Session writer = root();

        assertScopeRefusal(ds, "CREATE " + table + ":1 CONTENT {v: 5}", writer);

        assertNoIntruder(ds);
        // a recusa não deixa a sessão de quem grava presa à leitura
        ds.execute("CREATE copia:1", writer, null);
    }

    @Test
    void permissionsDoAdministradorDoBancoSoLeem() {
        Datastore ds = ds();
        ds.execute("DEFINE TABLE isca SCHEMALESS PERMISSIONS FOR create WHERE " + PAYLOAD + " != 1",
            databaseAdmin(), null);

        assertScopeRefusal(ds, "CREATE isca:1", root());

        assertNoIntruder(ds);
    }

    @Test
    void dataRuleSoLe() {
        Datastore ds = ds();
        ds.execute("CREATE DATA RULE arapuca APPLY v != " + RULE_PAYLOAD, databaseAdmin(), null);

        assertScopeRefusal(ds, "SELECT * FROM semente", session("root", "ROOT", "admin", "arapuca"));

        assertNoIntruder(ds);
    }

    @Test
    void valorInjetadoPelaDataRuleSoLe() {
        Datastore ds = ds();
        ds.execute("CREATE DATA RULE injeta APPLY dono = " + RULE_PAYLOAD, databaseAdmin(), null);

        assertScopeRefusal(ds, "CREATE livre:1", session("root", "ROOT", "admin", "injeta"));

        assertNoIntruder(ds);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "(SELECT VALUE v FROM outro.segredo)",
        "(SELECT VALUE v FROM semente; CREATE copia:9)",
        "kv::set('chave', 'x')",
        "(SELECT VALUE kv::del('chave') FROM semente)"})
    void defaultNaoEscreveNemSaiDoBanco(String expression) {
        Datastore ds = ds();
        ds.execute("DEFINE FIELD nota ON TABLE livre DEFAULT " + expression, editor(), null);

        assertScopeRefusal(ds, "CREATE livre:1", root());

        assertEquals(0, ds.execute("SELECT * FROM livre", local(), null).asArray().size());
        assertEquals(0, ds.execute("SELECT * FROM copia", local(), null).asArray().size());
    }

    @Test
    void funcaoChamadaPeloDefaultContinuaSoLendo() {
        Datastore ds = ds();
        createTrapFunction(ds);
        ds.execute("DEFINE FIELD nota ON TABLE livre DEFAULT isca_fn()", editor(), null);

        assertThrows(RuntimeException.class, () -> ds.execute("CREATE livre:1", root(), null));

        assertNoIntruder(ds);
    }

    @Test
    void funcaoChamadaNumaConsultaNaoEscrevePorSubconsulta() {
        Datastore ds = ds();
        createTrapFunction(ds);

        assertThrows(AxonError.class, () -> ds.execute("SELECT isca_fn() AS r FROM semente", root(), null));

        assertNoIntruder(ds);
    }

    private static void createTrapFunction(Datastore ds) {
        ds.execute("CREATE FUNCTION isca_fn() AS $$ return db.query('SELECT (SELECT VALUE v FROM semente; "
            + "CREATE USER intruso ON ROOT PASSWORD \"Test-pw-1\" ROLES admin) AS a FROM semente') $$",
            editor(), null);
    }

    @Test
    void filtroDeLiveNaoAgeComOPoderDeQuemGrava() {
        Datastore ds = ds();
        ds.execute("LIVE SELECT * FROM livre WHERE " + PAYLOAD + " != 1", session("leitor", "DATABASE", "viewer"),
            null);

        // a escrita vale: um assinante não derruba as gravações dos outros
        ds.execute("CREATE livre:1", root(), null);

        assertNoIntruder(ds);
        assertEquals(1, ds.execute("SELECT * FROM livre", local(), null).asArray().size());
    }

    @Test
    void expressoesQueSoLeemOProprioBancoContinuamValendo() {
        Datastore ds = ds();
        ds.execute("DEFINE FIELD nota ON TABLE livre DEFAULT (SELECT VALUE v FROM semente)", editor(), null);
        ds.execute("DEFINE TABLE restrita SCHEMALESS PERMISSIONS FOR create WHERE 1 IN "
            + "(SELECT VALUE v FROM main.semente)", databaseAdmin(), null);

        ds.execute("CREATE livre:1; CREATE restrita:1", editor(), null);

        assertNotNull(ds.execute("SELECT VALUE nota FROM livre", local(), null));
        assertEquals(1, ds.execute("SELECT * FROM restrita", local(), null).asArray().size());
    }

    @Test
    void subconsultaDeVariasSentencasNaConsultaDoProprioUsuarioContinuaValendo() {
        Datastore ds = ds();

        ds.execute("RETURN (SELECT * FROM semente; CREATE copia:1)", editor(), null);

        assertEquals(1, ds.execute("SELECT * FROM copia", local(), null).asArray().size());
    }
}
