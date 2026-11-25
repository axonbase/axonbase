package com.axonbase.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Aritmética do AxonQL: inteiro com inteiro continua inteiro, estouro vira decimal exato, NULL e NONE propagam
 * (nunca viram o outro operando), tipo inválido é erro claro e nenhum resultado sai como NaN ou Infinity (que não
 * são JSON válido).
 */
class ArithmeticTest {

    private final Datastore ds = Datastore.memory();
    private final Session s = session();

    private static Session session() {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, s, null);
    }

    private AxonValue val(String expr) {
        return run("RETURN " + expr);
    }

    private void assertInt(long expected, String expr) {
        AxonValue v = val(expr);
        assertTrue(v.isInteger(), expr + " devia ser inteiro, veio " + v);
        assertEquals(expected, v.asLong(), expr);
    }

    private void assertDec(String expected, String expr) {
        AxonValue v = val(expr);
        assertTrue(v.isNumber(), expr + " devia ser número, veio " + v);
        assertEquals(0, new BigDecimal(expected).compareTo(v.asDecimal()), expr + " = " + v);
    }

    private void assertNullish(String expr) {
        AxonValue v = val(expr);
        assertTrue(v.isNull() || v.isNone(), expr + " devia ser nulo, veio " + v);
    }

    private AxonError assertError(String expr) {
        return assertThrows(AxonError.class, () -> val(expr), expr);
    }

    @Test
    void inteiroComInteiroContinuaInteiro() {
        assertInt(8, "10 - 2");
        assertInt(12, "10 + 2");
        assertInt(12, "3 * 4");
        assertInt(1, "7 % 3");
        assertInt(-1, "-7 % 3");
        assertInt(4, "8 / 2");
        assertInt(-5, "-5");
        assertInt(7, "-(-7)");
    }

    @Test
    void divisaoNaoExataEhDecimal() {
        AxonValue v = val("7 / 2");
        assertFalse(v.isInteger());
        assertDec("3.5", "7 / 2");
        assertDec("0.3333333333", "1 / 3");
    }

    @Test
    void estouroDeLongViraDecimalExato() {
        assertDec("9223372036854775808", "9223372036854775807 + 1");
        assertDec("-9223372036854775809", "0 - 9223372036854775807 - 2");
        assertDec("18446744073709551614", "9223372036854775807 * 2");
    }

    @Test
    void potencia() {
        assertInt(8, "2 ** 3");
        assertInt(-512, "(-8) ** 3");
        assertInt(1, "0 ** 0");
        assertInt(4611686018427387904L, "2 ** 62");
        assertDec("9223372036854775808", "2 ** 63");
        assertDec("100000000000000000000", "10 ** 20");
        assertDec("0.5", "2 ** -1");
        assertDec("2.25", "1.5 ** 2");
    }

    @Test
    void potenciaForaDoIntervaloEErroEmVezDeInfinito() {
        assertTrue(assertError("2 ** 10000").getMessage().toLowerCase().contains("intervalo"));
        assertError("0 ** -1");
    }

    @Test
    void decimalEPontoFlutuanteSeguemSomando() {
        assertDec("2.5", "1.5 + 1");
        assertDec("3", "1.5 * 2");
        assertDec("0.5", "1.5 - 1");
    }

    @Test
    void nuloENoneSePropagamEmVezDeVirarOOutroOperando() {
        for (String expr : new String[] {
            "NONE + 5", "5 + NONE", "NULL - 2", "5 - NONE", "5 * NULL", "NONE * 5", "5 / NONE", "NULL / 5",
            "NULL % 2", "5 % NONE", "2 ** NONE", "NONE ** 2", "-NONE", "-NULL"}) {
            assertNullish(expr);
        }
    }

    @Test
    void tipoInvalidoEErroClaro() {
        for (String expr : new String[] {"'a' - 1", "true * 2", "[1] / 2", "{a: 1} % 2", "-'x'", "1 - 'b'", "2 ** 'c'"}) {
            AxonError e = assertError(expr);
            assertTrue(e.getMessage().toLowerCase().contains("número") || e.getMessage().toLowerCase().contains("numero"),
                expr + " -> " + e.getMessage());
        }
    }

    @Test
    void concatenacaoDeTextoContinuaFuncionando() {
        assertEquals("a1", val("'a' + 1").asString());
        assertEquals("ab", val("'a' + 'b'").asString());
    }

    @Test
    void divisaoEModuloPorZeroSaoErroEmPortugues() {
        for (String expr : new String[] {"5 / 0", "5 % 0", "5.0 / 0.0", "0 / 0", "5 % 0.0"}) {
            AxonError e = assertError(expr);
            assertTrue(e.getMessage().toLowerCase().contains("zero"), expr + " -> " + e.getMessage());
            assertFalse(e.getMessage().contains("Division by zero"), "mensagem em inglês: " + e.getMessage());
        }
    }

    @Test
    void campoAusenteNaoVira_ValorFalso() {
        run("CREATE m:1 CONTENT {a: 1}");
        run("CREATE m:2 CONTENT {a: 2, b: 10}");
        AxonValue rows = run("SELECT a, a + b AS e FROM m ORDER BY a");
        assertTrue(rows.asArray().get(0).asObject().get("e").isNull() || rows.asArray().get(0).asObject().get("e").isNone());
        assertEquals(12, rows.asArray().get(1).asObject().get("e").asLong());
        assertTrue(rows.asArray().get(1).asObject().get("e").isInteger());
    }

    @Test
    void updateComAritmeticaMantemInteiro() {
        run("CREATE e:1 CONTENT {n: 10}");
        AxonValue r = run("UPDATE e:1 SET n = n - 2");
        AxonValue n = r.asArray().get(0).asObject().get("n");
        assertTrue(n.isInteger(), "estoque deve continuar inteiro, veio " + n);
        assertEquals(8, n.asLong());
        run("UPDATE e:1 SET n = n * 3 + 1");
        assertEquals(25, run("SELECT n FROM e:1").asObject().get("n").asLong());
    }

    @Test
    void agregacoesEFuncoesDeMatematica() {
        run("CREATE g:1 CONTENT {a: 1}");
        run("CREATE g:2 CONTENT {a: 2}");
        run("CREATE g:3 CONTENT {a: 4}");
        AxonValue sum = run("SELECT sum(a) AS s FROM g GROUP ALL").asArray().get(0).asObject().get("s");
        assertTrue(sum.isInteger(), "sum de inteiros deve ser inteiro, veio " + sum);
        assertEquals(7, sum.asLong());
        run("CREATE g:4 CONTENT {a: 0.5}");
        AxonValue mixed = run("SELECT sum(a) AS s FROM g GROUP ALL").asArray().get(0).asObject().get("s");
        assertEquals(0, new BigDecimal("7.5").compareTo(mixed.asDecimal()));
        assertInt(6, "math::sum([1, 2, 3])");
        assertInt(6, "math::product([2, 3])");
        assertInt(5, "math::abs(-5)");
        assertDec("2.5", "math::abs(-2.5)");
    }

    @Test
    void funcoesDeMatematicaNaoDevolvemNaNNemInfinito() {
        assertError("math::sqrt(-1)");
        assertError("math::ln(0)");
        assertError("math::pow(10, 1000)");
        assertError("math::log(5, 1)");
    }

    @Test
    void resultadosCorretosDeMatematicaContinuamIguais() {
        assertDec("4", "math::sqrt(16)");
        assertDec("8", "math::pow(2, 3)");
        assertDec("2", "math::mean([1, 2, 3])");
        assertInt(5, "math::max([1, 5, 3])");
    }

    @Test
    void somaPeloIndiceColumnarDeInteirosContinuaInteira() {
        run("DEFINE TABLE v SCHEMALESS");
        run("DEFINE INDEX v_a ON TABLE v COLUMNS a COLUMNAR");
        run("DEFINE INDEX v_g ON TABLE v COLUMNS g COLUMNAR");
        run("CREATE v:1 CONTENT {a: 10, g: 'x'}");
        run("CREATE v:2 CONTENT {a: 20, g: 'x'}");
        run("CREATE v:3 CONTENT {a: 5, g: 'y'}");
        AxonValue all = run("SELECT sum(a) AS s, count() AS c FROM v GROUP ALL").asArray().get(0).asObject().get("s");
        assertTrue(all.isInteger(), "sum columnar de inteiros deve ser inteiro, veio " + all);
        assertEquals(35, all.asLong());
        AxonValue grouped = run("SELECT g, sum(a) AS s FROM v GROUP BY g ORDER BY g");
        for (AxonValue row : grouped.asArray()) {
            assertTrue(row.asObject().get("s").isInteger(), "soma por grupo deve ser inteira: " + row);
        }
        assertEquals(30, grouped.asArray().get(0).asObject().get("s").asLong());
    }

    @Test
    void comparacaoComNullENoneNaoLancaExcecao() {
        assertTrue(val("NULL = NULL").asBool());
        assertTrue(val("NONE = NONE").asBool());
        assertFalse(val("NULL = 1").asBool());
        assertFalse(val("1 = NULL").asBool());
        assertFalse(val("'a' = NULL").asBool());
        run("CREATE nulos:1 CONTENT {a: NULL}");
        run("CREATE nulos:2 CONTENT {a: 1}");
        run("CREATE nulos:3 CONTENT {b: 1}");
        AxonValue rows = run("SELECT * FROM nulos WHERE a = NULL");
        assertTrue(rows.asArray().stream().anyMatch(r -> r.asObject().get("id").toString().contains("nulos:1")), rows.toString());
        assertFalse(rows.asArray().stream().anyMatch(r -> r.asObject().get("id").toString().contains("nulos:2")), rows.toString());
        // igualdade e ordenação sem índice, com campos nulos e ausentes, também não podem falhar
        run("SELECT * FROM nulos WHERE a != NULL");
        run("SELECT * FROM nulos ORDER BY a");
        run("SELECT * FROM nulos GROUP BY a");
    }

    @Test
    void valoresNulosSaoIguaisEEntramEmConjuntos() {
        assertEquals(AxonValue.nul(), AxonValue.nul());
        assertEquals(AxonValue.nul().hashCode(), AxonValue.nul().hashCode());
        assertEquals(AxonValue.none(), AxonValue.none());
        assertEquals(1, new java.util.HashSet<>(java.util.List.of(AxonValue.nul(), AxonValue.nul())).size());
    }
}
