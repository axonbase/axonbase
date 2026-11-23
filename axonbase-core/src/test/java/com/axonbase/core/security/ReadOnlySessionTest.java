package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Somente leitura imposta no motor, teto de papel e escopo no USE. */
class ReadOnlySessionTest {

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
        ds.createDatabase("app", "other");
        Session root = Session.create();
        root.namespace("app");
        root.database("main");
        ds.execute("CREATE a:1 SET v = 1", root, null);
        ds.execute("CREATE b:1 SET v = 1", root, null);
        Session o = Session.create();
        o.namespace("app");
        o.database("other");
        ds.execute("CREATE secreto:1 SET v = 'x'", o, null);
        return ds;
    }

    private static long count(Datastore ds, String table) {
        Session root = Session.create();
        root.namespace("app");
        root.database("main");
        return ds.execute("SELECT * FROM " + table, root, null).asArray().size();
    }

    @Test
    void viewerPorPapelNaoEscreve() {
        Datastore ds = ds();
        Session s = as("DATABASE", "app", "main", "viewer");
        assertTrue(s.readOnly());
        assertThrows(AxonError.class, () -> ds.execute("CREATE a:2 SET v = 2", s, null));
        assertThrows(AxonError.class, () -> ds.execute("UPDATE a:1 SET v = 9", s, null));
        assertThrows(AxonError.class, () -> ds.execute("DELETE a:1", s, null));
        assertThrows(AxonError.class, () -> ds.execute("DEFINE TABLE zz SCHEMALESS", s, null));
        assertEquals(1, count(ds, "a"));
        assertEquals(1, ds.execute("SELECT * FROM a", s, null).asArray().size());
    }

    @Test
    void kvSetEDelBloqueadosMesmoEmInstrucaoDeLeitura() {
        Datastore ds = ds();
        Session root = as("ROOT", null, null, null);
        ds.execute("RETURN kv::set('k', 1)", root, null);
        Session s = as("DATABASE", "app", "main", "viewer");
        assertThrows(AxonError.class, () -> ds.execute("RETURN kv::set('k2', 1)", s, null));
        assertThrows(AxonError.class, () -> ds.execute("SELECT kv::del('k') FROM a", s, null));
        assertEquals(1, ds.execute("RETURN kv::get('k')", s, null).asLong());
        assertDoesNotThrow(() -> ds.execute("RETURN kv::scan('')", s, null));
    }

    @Test
    void subconsultaEMultiplasInstrucoesNaoBurlam() {
        Datastore ds = ds();
        Session s = as("DATABASE", "app", "main", "viewer");
        try {
            ds.execute("RETURN (SELECT * FROM a; DELETE b:1)", s, null);
        } catch (RuntimeException e) {
            // parser pode recusar ou o motor bloquear: ambos servem
        }
        try {
            ds.execute("SELECT * FROM a; DELETE b:1", s, null);
        } catch (RuntimeException e) {
            // esperado
        }
        assertEquals(1, count(ds, "b"));
    }

    @Test
    void semPapelReconhecidoNaoFicaSomenteLeitura() {
        assertFalse(as("DATABASE", "app", "main", null).readOnly());
        assertFalse(as("ROOT", null, null, null).readOnly());
        assertFalse(as("DATABASE", "app", "main", "admin").readOnly());
        assertFalse(as("DATABASE", "app", "main", "editor").readOnly());
        assertFalse(Session.create().readOnly());
    }

    @Test
    void tetoViewerImpedeEscritaDeAdmin() {
        Datastore ds = ds();
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        s.roleCap(Authz.Role.VIEWER);
        s.auth(AxonValue.object(Map.of("scope", AxonValue.str("ROOT"))));
        assertTrue(s.readOnly());
        assertThrows(AxonError.class, () -> ds.execute("DELETE a:1", s, null));
    }

    @Test
    void tetoOperatorBloqueiaUsuarioMasMantemEscritaDeDados() {
        Datastore ds = ds();
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        s.roleCap(Authz.Role.OPERATOR);
        s.auth(AxonValue.object(Map.of("scope", AxonValue.str("ROOT"))));
        assertFalse(s.readOnly());
        assertThrows(AxonError.class,
            () -> ds.execute("DEFINE USER x ON DATABASE PASSWORD \"p\"", s, null));
        assertDoesNotThrow(() -> ds.execute("CREATE a:5 SET v = 5", s, null));
    }

    @Test
    void adminNormalSegueFuncionando() {
        Datastore ds = ds();
        Session s = as("ROOT", null, null, "admin");
        assertDoesNotThrow(() -> ds.execute("CREATE a:7 SET v = 7", s, null));
        assertDoesNotThrow(() -> ds.execute("DEFINE USER y ON DATABASE PASSWORD \"p\"", s, null));
    }

    @Test
    void useNaoTrocaParaForaDoEscopoDoToken() {
        Datastore ds = ds();
        Session s = as("DATABASE", "app", "main", "editor");
        assertThrows(AxonError.class, () -> ds.execute("USE app other; SELECT * FROM secreto", s, null));
        assertEquals("main", s.database());
        assertThrows(AxonError.class, () -> ds.execute("USE NS outro", s, null));
        assertEquals("app", s.namespace());
        // mesmo escopo continua valendo
        assertDoesNotThrow(() -> ds.execute("USE app main", s, null));
        // ROOT troca livremente
        Session root = as("ROOT", null, null, "admin");
        assertDoesNotThrow(() -> ds.execute("USE app other", root, null));
        assertEquals("other", root.database());
    }
}
