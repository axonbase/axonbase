package com.axonbase.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.parser.ast.Statement;
import org.junit.jupiter.api.Test;

/** Sintaxe de FUNCTION, PROCEDURE, PACKAGE, CALL e dos delimitadores de dólar. */
class ProgramSyntaxTest {

    private static Statement one(String sql) {
        return AxonQl.parse(sql).statements().get(0);
    }

    private static void roundTrip(String sql) {
        String once = AxonQl.render(AxonQl.parse(sql));
        assertEquals(once, AxonQl.render(AxonQl.parse(once)), "render deve ser estavel");
    }

    @Test
    void funcaoComTiposEPadrao() {
        var f = assertInstanceOf(Statement.CreateFunction.class,
            one("CREATE FUNCTION desconto(valor decimal, pct int = 10) RETURNS decimal AS $$\n    return valor * (100 - pct) / 100\n$$"));
        assertEquals("desconto", f.name());
        assertEquals(2, f.params().size());
        assertEquals("decimal", f.params().get(0).type());
        assertEquals("int", f.params().get(1).type());
        assertTrue(f.params().get(1).defaultExpr() != null);
        assertEquals("decimal", f.returns());
        assertEquals("return valor * (100 - pct) / 100", f.body());
        assertFalse(f.definer());
        assertFalse(f.orReplace());
    }

    @Test
    void orReplaceESecurityDefiner() {
        var p = assertInstanceOf(Statement.CreateProcedure.class,
            one("CREATE OR REPLACE PROCEDURE baixa(id) SECURITY DEFINER AS $$ db.exec('x') $$"));
        assertTrue(p.orReplace());
        assertTrue(p.definer());
        assertEquals("db.exec('x')", p.body());
        roundTrip("CREATE OR REPLACE PROCEDURE baixa(id) SECURITY DEFINER AS $$ db.exec('x') $$");
    }

    @Test
    void pacote() {
        var p = assertInstanceOf(Statement.CreatePackage.class,
            one("CREATE PACKAGE util AS $$\nTAXA = 3\ndef dobro(x):\n    return x * 2\n$$"));
        assertEquals("util", p.name());
        assertEquals("TAXA = 3\ndef dobro(x):\n    return x * 2", p.body());
    }

    @Test
    void corpoCruComAspasTracosEComentario() {
        String body = "s = \"a -- b\"\n# nota 'x'\nreturn s + '$'";
        var f = assertInstanceOf(Statement.CreateFunction.class,
            one("CREATE FUNCTION f() AS $$\n" + body + "\n$$"));
        assertEquals(body, f.body());
        roundTrip("CREATE FUNCTION f() AS $$\n" + body + "\n$$");
    }

    @Test
    void delimitadorComTag() {
        var f = assertInstanceOf(Statement.CreateFunction.class,
            one("CREATE FUNCTION f() AS $axon$ return '$$' $axon$"));
        assertEquals("return '$$'", f.body());
        String rendered = AxonQl.render(AxonQl.parse("CREATE FUNCTION f() AS $axon$ return '$$' $axon$"));
        assertTrue(rendered.contains("$axon$"));
        var again = assertInstanceOf(Statement.CreateFunction.class, one(rendered));
        assertEquals(f.body(), again.body());
    }

    @Test
    void corpoTerminandoEmCifrao() {
        var f = assertInstanceOf(Statement.CreateFunction.class, one("CREATE FUNCTION f() AS $t$ return '$' $t$"));
        var again = assertInstanceOf(Statement.CreateFunction.class, one(AxonQl.render(AxonQl.parse(
            "CREATE FUNCTION f() AS $t$ x = '$$'\nreturn x$ $t$"))));
        assertEquals("return '$'", f.body());
        assertEquals("x = '$$'\nreturn x$", again.body());
    }

    @Test
    void dropInfoECall() {
        assertEquals(new Statement.DropProgram("FUNCTION", "f"), one("DROP FUNCTION f"));
        assertEquals(new Statement.DropProgram("PACKAGE", "p"), one("drop package p"));
        assertEquals(new Statement.Info("procedure", "x"), one("INFO FOR PROCEDURE x"));
        var c = assertInstanceOf(Statement.CallProgram.class, one("CALL util::dobro(1, $x)"));
        assertEquals("util::dobro", c.name());
        assertEquals(2, c.args().size());
        assertEquals("CALL util::dobro(1, $x)", AxonQl.render(AxonQl.parse("CALL util::dobro(1, $x)")));
        assertEquals("CALL baixa()", AxonQl.render(AxonQl.parse("call baixa")));
    }

    @Test
    void parametroContinuaFuncionando() {
        var s = assertInstanceOf(Statement.Select.class, one("SELECT * FROM t WHERE a = $x AND b = $y2"));
        assertEquals("SELECT * FROM t WHERE a = $x AND b = $y2", AxonQl.render(AxonQl.parse(
            "SELECT * FROM t WHERE a = $x AND b = $y2")));
        assertTrue(s.cond() != null);
    }

    @Test
    void tabelaChamadaFunctionNaoEPrograma() {
        assertInstanceOf(Statement.Create.class, one("CREATE function SET a = 1"));
    }

    @Test
    void normalizaIndentacao() {
        assertEquals("a = 1\nif a:\n    return 2", ProgramText.normalizeBody("\n\n    a = 1\n    if a:\n        return 2\n   \n"));
    }
}
