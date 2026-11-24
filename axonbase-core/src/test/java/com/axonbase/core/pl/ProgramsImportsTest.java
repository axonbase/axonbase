package com.axonbase.core.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

/** Validação de import entre packages no CREATE e no DROP. */
class ProgramsImportsTest {

    private final Datastore ds = Datastore.memory();
    private final Session s = ProgramsTest.session(ds);

    private AxonValue run(String sql) {
        return ds.execute(sql, s, null);
    }

    private AxonError error(String sql) {
        return assertThrows(AxonError.class, () -> run(sql));
    }

    @Test
    void importDePackageInexistenteEhRecusado() {
        AxonError e = error("CREATE PACKAGE loja AS $$\nimport fantasma\ndef f():\n    return 1\n$$");
        assertTrue(e.getMessage().contains("'fantasma'") && e.getMessage().contains("nao existe"), e.getMessage());
        assertEquals(0, run("INFO FOR DATABASE").asObject().get("packages").asObject().size());
    }

    @Test
    void importDeFuncaoNaoEhPackage() {
        run("CREATE PACKAGE util AS $$\ndef f():\n    return 1\n$$");
        run("CREATE FUNCTION unica(x) AS $$ return x $$");
        AxonError e = error("CREATE PACKAGE g AS $$\nimport unica\ndef f():\n    return 1\n$$");
        assertTrue(e.getMessage().contains("nao um package"), e.getMessage());
    }

    @Test
    void modulosEmbutidosENativosSaoAceitos() {
        run("CREATE PACKAGE ok AS $$\nimport math\nimport json\nimport db\nimport log\ndef f():\n"
            + "    return math.floor(2.5)\n$$");
        assertEquals(2, run("RETURN ok::f()").asLong());
    }

    @Test
    void importDePackageExistenteFunciona() {
        run("CREATE PACKAGE base AS $$\ndef a():\n    return 1\n$$");
        run("CREATE PACKAGE topo AS $$\nimport base\ndef b():\n    return base.a() + 1\n$$");
        assertEquals(2, run("RETURN topo::b()").asLong());
    }

    @Test
    void autoImportEhCiclo() {
        AxonError e = error("CREATE PACKAGE eu AS $$\nimport eu\ndef f():\n    return 1\n$$");
        assertTrue(e.getMessage().contains("circular"), e.getMessage());
    }

    @Test
    void cicloIndiretoNoReplaceEhRecusado() {
        run("CREATE PACKAGE a AS $$\ndef f():\n    return 1\n$$");
        run("CREATE PACKAGE b AS $$\nimport a\ndef g():\n    return a.f()\n$$");
        run("CREATE PACKAGE c AS $$\nimport b\ndef h():\n    return b.g()\n$$");
        AxonError e = error("CREATE OR REPLACE PACKAGE a AS $$\nimport c\ndef f():\n    return 1\n$$");
        assertTrue(e.getMessage().contains("circular") && e.getMessage().contains("a -> c -> b -> a"),
            e.getMessage());
        // o original continua intacto
        assertEquals(1, run("RETURN a::f()").asLong());
        assertEquals(1, run("RETURN c::h()").asLong());
    }

    @Test
    void dropDePackageImportadoListaQuemDepende() {
        run("CREATE PACKAGE base AS $$\ndef a():\n    return 1\n$$");
        run("CREATE PACKAGE p1 AS $$\nimport base\ndef b():\n    return 1\n$$");
        run("CREATE PACKAGE p2 AS $$\nimport base\ndef b():\n    return 2\n$$");
        AxonError e = error("DROP PACKAGE base");
        assertTrue(e.getMessage().contains("package p1") && e.getMessage().contains("package p2"), e.getMessage());
        run("DROP PACKAGE p1");
        run("DROP PACKAGE p2");
        run("DROP PACKAGE base");
    }
}
