package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Papéis e escopo aplicados no motor (E0, segurança do núcleo). */
class RbacEnforcementTest {

    private static Session as(String scope, String ns, String db, String roles) {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("tester"));
        claims.put("scope", AxonValue.str(scope));
        if (ns != null) claims.put("ns", AxonValue.str(ns));
        if (db != null) claims.put("db", AxonValue.str(db));
        if (roles != null) claims.put("roles", AxonValue.str(roles));
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        return ds;
    }

    @Test
    void sessaoSemAuthContinuaLivreModoEmbutido() {
        Datastore ds = ds();
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        assertDoesNotThrow(() -> ds.execute("DEFINE USER a ON DATABASE PASSWORD \"x\"", s, null));
    }

    @Test
    void usuarioDatabaseSemPapelAdminNaoCriaUsuario() {
        Datastore ds = ds();
        Session s = as("DATABASE", "app", "main", "editor");
        assertThrows(AxonError.class,
            () -> ds.execute("DEFINE USER b ON DATABASE PASSWORD \"x\"", s, null));
    }

    @Test
    void adminDeBancoCriaUsuarioDeBancoMasNaoDeEscopoMaior() {
        Datastore ds = ds();
        Session s = as("DATABASE", "app", "main", "admin");
        assertDoesNotThrow(() -> ds.execute("DEFINE USER b ON DATABASE PASSWORD \"x\"", s, null));
        assertThrows(AxonError.class,
            () -> ds.execute("DEFINE USER c ON NAMESPACE PASSWORD \"x\"", s, null));
        assertThrows(AxonError.class,
            () -> ds.execute("DEFINE USER d ON ROOT PASSWORD \"x\"", s, null));
    }

    @Test
    void adminDeNamespaceNaoCriaRoot() {
        Datastore ds = ds();
        Session s = as("NAMESPACE", "app", null, "admin");
        assertDoesNotThrow(() -> ds.execute("DEFINE USER b ON NAMESPACE PASSWORD \"x\"", s, null));
        assertThrows(AxonError.class,
            () -> ds.execute("DEFINE USER d ON ROOT PASSWORD \"x\"", s, null));
    }

    @Test
    void rootCriaQualquerEscopo() {
        Datastore ds = ds();
        Session s = as("ROOT", null, null, "OWNER");
        assertDoesNotThrow(() -> ds.execute("DEFINE USER d ON ROOT PASSWORD \"x\"", s, null));
    }

    @Test
    void rootComPapelViewerNaoFazDdlDeAcesso() {
        Datastore ds = ds();
        Session s = as("ROOT", null, null, "viewer");
        assertThrows(AxonError.class,
            () -> ds.execute("DEFINE USER d ON ROOT PASSWORD \"x\"", s, null));
        assertThrows(AxonError.class,
            () -> ds.execute("DEFINE ACCESS acc ON DATABASE", s, null));
    }

    @Test
    void ddlDeAcessoEUsuarioExigemAdmin() {
        Datastore ds = ds();
        Session s = as("DATABASE", "app", "main", "editor");
        assertThrows(AxonError.class, () -> ds.execute("DEFINE ACCESS acc ON DATABASE", s, null));
        assertThrows(AxonError.class, () -> ds.execute("DROP USER x", s, null));
    }

    @Test
    void usuarioComumAindaLeEEscreveDados() {
        Datastore ds = ds();
        Session s = as("DATABASE", "app", "main", "editor");
        assertDoesNotThrow(() -> ds.execute("CREATE t:1 CONTENT {a: 1}", s, null));
        assertDoesNotThrow(() -> ds.execute("SELECT * FROM t", s, null));
    }

    @Test
    void decidirCasoDeAuditoriaExigeOperatorOuAdmin() {
        Datastore ds = ds();
        Session s = as("DATABASE", "app", "main", "editor");
        AxonError e = assertThrows(AxonError.class,
            () -> ds.execute("SET AUDIT CASE \"abc\" AUTHORIZED REASON \"ok\"", s, null));
        assertTrue(e.getMessage().contains("operator"), e.getMessage());
    }

    @Test
    void usuarioBloqueadoNaoDecideOProprioCaso() {
        Datastore ds = ds();
        var cat = ds.requireDatabase(as("ROOT", null, null, "admin")).auditCatalog();
        cat.addCase("h1", new com.axonbase.core.audit.AuditCase("h1", "tester", "DELETE t", "aud", "DANGER",
            System.currentTimeMillis()));
        cat.blockUser("tester", "aud", "DELETE t", "perigo", "sys");
        Session s = as("DATABASE", "app", "main", "admin");
        AxonError e = assertThrows(AxonError.class,
            () -> ds.execute("SET AUDIT CASE \"h1\" AUTHORIZED REASON \"eu mesmo\"", s, null));
        assertTrue(e.getMessage().contains("bloqueado") || e.getMessage().contains("blocked"), e.getMessage());
    }

    @Test
    void removerUsuarioExatoPorEscopo() {
        Datastore ds = ds();
        ds.authCatalog().defineUser("u", AuthCatalog.Scope.ROOT, null, null, "x", java.util.List.of(), java.util.List.of());
        ds.authCatalog().defineUser("u", AuthCatalog.Scope.DATABASE, "app", "main", "x", java.util.List.of(), java.util.List.of());
        assertTrue(ds.dropUser(null, "u", AuthCatalog.Scope.ROOT, null, null));
        org.junit.jupiter.api.Assertions.assertNotNull(ds.authCatalog().user("u", "app", "main"));
        org.junit.jupiter.api.Assertions.assertEquals(AuthCatalog.Scope.DATABASE,
            ds.authCatalog().user("u", "app", "main").scope());
        org.junit.jupiter.api.Assertions.assertFalse(ds.dropUser(null, "u", AuthCatalog.Scope.ROOT, null, null));
    }

    @Test
    void cancelSagaNegaViewerMasPermiteTokenSemPapelDeSdk() {
        Datastore ds = ds();
        Session setup = as("ROOT", null, null, "admin");
        ds.execute("BEGIN SAGA pedido WITH CORRELATION 'c1'", setup, null);
        Session viewer = as("DATABASE", "app", "main", "viewer");
        assertThrows(AxonError.class,
            () -> ds.execute("CANCEL SAGA pedido WITH CORRELATION 'c1'", viewer, null));
        Session sdk = as("DATABASE", "app", "main", null);
        assertDoesNotThrow(() -> ds.execute("CANCEL SAGA pedido WITH CORRELATION 'c1'", sdk, null));
    }
}
