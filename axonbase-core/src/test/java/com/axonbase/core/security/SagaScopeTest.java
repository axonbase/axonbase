package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SAGA: só quem alcança o namespace e o banco dela a lê ou a conclui")
class SagaScopeTest {

    private static final String SHOW = "SHOW SAGA TRANSACTION pedido 'pedido-42'";
    private static final String CANCEL = "CANCEL SAGA pedido WITH CORRELATION 'pedido-42'";

    private final Datastore ds = Datastore.memory();

    private static Session session(String ns, String db) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        return s;
    }

    private static Session as(String scope, String ns, String db, String role) {
        Session s = session(ns, db);
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("tester"));
        claims.put("scope", AxonValue.str(scope));
        if (ns != null) claims.put("ns", AxonValue.str(ns));
        if (db != null) claims.put("db", AxonValue.str(db));
        claims.put("roles", AxonValue.str(role));
        s.auth(AxonValue.object(claims));
        return s;
    }

    /** Todo comando por texto exige um banco selecionado; o alcance continua vindo só dos claims. */
    private static Session selecting(Session who, String ns, String db) {
        who.namespace(ns);
        who.database(db);
        return who;
    }

    private static Session owner() {
        return as("DATABASE", "app", "main", "operator");
    }

    private AxonValue run(Session who, String sql) {
        return ds.execute(sql, who, null);
    }

    /** O dono abre a SAGA e grava um registro nela. */
    private void openSaga() {
        Session owner = owner();
        run(owner, "BEGIN SAGA pedido WITH CORRELATION 'pedido-42'");
        run(owner, "LET $saga_corr = 'pedido-42'");
        run(owner, "CREATE segredo:1 SET v = 1");
    }

    private void assertRefused(Session who, String sql) {
        AxonError refused = assertThrows(AxonError.class, () -> run(who, sql), sql);
        assertEquals(-32002, refused.code(), refused.getMessage());
    }

    private int records() {
        return run(session("app", "main"), "SELECT * FROM segredo").asArray().size();
    }

    @Test
    void outroBancoNaoLeNemCancela() {
        openSaga();

        assertRefused(as("DATABASE", "app", "outro", "viewer"), SHOW);
        assertRefused(as("DATABASE", "app", "outro", "operator"), CANCEL);

        assertEquals(1, records());
    }

    @Test
    void outroNamespaceNaoLeNemCancela() {
        openSaga();

        assertRefused(selecting(as("NAMESPACE", "loja", null, "admin"), "loja", "main"), SHOW);
        assertRefused(as("DATABASE", "loja", "main", "operator"), CANCEL);

        assertEquals(1, records());
    }

    @Test
    void outroBancoNaoConfirmaNaoEntraENaoTomaACorrelacao() {
        openSaga();
        Session other = as("DATABASE", "app", "outro", "operator");

        assertRefused(other, "COMMIT SAGA pedido WITH CORRELATION 'pedido-42'");
        assertRefused(other, "JOIN SAGA pedido WITH CORRELATION 'pedido-42'");
        assertRefused(other, "BEGIN SAGA pedido WITH CORRELATION 'pedido-42'");

        assertEquals("FAILED", run(owner(), CANCEL).asObject().get("status").asString());
    }

    @Test
    void passoEmOutroBancoExigeEscopoQueAlcanceOsDois() {
        Session namespace = as("NAMESPACE", "app", null, "operator");
        namespace.database("main");
        run(namespace, "BEGIN SAGA pedido WITH CORRELATION 'pedido-42'");
        run(namespace, "LET $saga_corr = 'pedido-42'");
        namespace.database("estoque");
        run(namespace, "CREATE segredo:1 SET v = 1");

        assertRefused(owner(), SHOW);
        assertRefused(owner(), CANCEL);

        assertEquals("FAILED", run(namespace, CANCEL).asObject().get("status").asString());
    }

    @Test
    void donoNamespaceRootEModoSemAutenticacaoContinuamAlcancando() {
        openSaga();

        AxonValue seen = run(as("DATABASE", "app", "main", "viewer"), SHOW);
        assertTrue(seen.asObject().get("saga").isObject());
        assertDoesNotThrow(() -> run(selecting(as("NAMESPACE", "app", null, "viewer"), "app", "outro"), SHOW));
        assertDoesNotThrow(() -> run(selecting(as("ROOT", null, null, "admin"), "loja", "main"), SHOW));
        assertDoesNotThrow(() -> run(session("system", "saga"), SHOW));
        assertEquals("FAILED", run(owner(), CANCEL).asObject().get("status").asString());
        assertEquals(0, records());
    }

    @Test
    void rootENamespaceComOutroBancoSelecionadoCancelam() {
        openSaga();
        Session root = selecting(as("ROOT", null, null, "admin"), "loja", "main");

        assertEquals("FAILED", run(root, CANCEL).asObject().get("status").asString());
        assertEquals(0, records());

        openSecondSaga();
        Session namespace = selecting(as("NAMESPACE", "app", null, "operator"), "app", "outro");

        assertEquals("FAILED", run(namespace, "CANCEL SAGA pedido WITH CORRELATION 'pedido-43'")
            .asObject().get("status").asString());
        assertEquals(0, records());
    }

    private void openSecondSaga() {
        Session owner = owner();
        run(owner, "BEGIN SAGA pedido WITH CORRELATION 'pedido-43'");
        run(owner, "LET $saga_corr = 'pedido-43'");
        run(owner, "CREATE segredo:2 SET v = 2");
    }

    @Test
    void correlacaoDesconhecidaRespondeVazio() {
        openSaga();

        AxonValue reply = run(as("DATABASE", "loja", "main", "viewer"), "SHOW SAGA TRANSACTION pedido 'nao-existe'");

        assertTrue(reply.asObject().get("saga").isNull());
    }
}
