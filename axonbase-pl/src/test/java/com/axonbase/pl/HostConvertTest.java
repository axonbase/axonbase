package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Conversao AxonValue/AxonPL, host de teste com db em memoria, imports e espaco de nomes. */
class HostConvertTest {

    private static final String ID = "def ident(x):\n    return x\n";

    private static AxonValue ida(AxonValue v) {
        return Pl.run(ID, "ident", Pl.limites(), null, new PlHostMemoria(), List.of(v));
    }

    @Test
    void idaEVoltaSemPerda() {
        Map<String, AxonValue> obj = new LinkedHashMap<>();
        obj.put("i", AxonValue.num(42L));
        obj.put("f", AxonValue.num(2.5));
        obj.put("d", AxonValue.num(new BigDecimal("10.50")));
        obj.put("s", AxonValue.str("olá"));
        obj.put("b", AxonValue.bool(true));
        obj.put("n", AxonValue.nul());
        obj.put("none", AxonValue.none());
        obj.put("arr", AxonValue.array(List.of(AxonValue.num(1L), AxonValue.str("x"), AxonValue.array(List.of()))));
        obj.put("set", AxonValue.set(List.of(AxonValue.num(1L), AxonValue.num(2L))));
        obj.put("dt", AxonValue.datetime(Instant.parse("2024-05-06T07:08:09.123Z")));
        obj.put("dur", AxonValue.duration(90_000));
        obj.put("rid", AxonValue.record("pedido", AxonValue.str("p1")));
        obj.put("rid2", AxonValue.record("pedido", AxonValue.num(7L)));
        obj.put("bytes", AxonValue.bytes(new byte[] {1, 2, (byte) 255}));
        obj.put("uuid", AxonValue.uuid(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")));
        obj.put("obj", AxonValue.object(Map.of("k", AxonValue.num(BigDecimal.ONE))));
        AxonValue in = AxonValue.object(obj);
        AxonValue out = ida(in);
        assertEquals(in.toString(), out.toString());
        assertTrue(java.util.Arrays.equals((byte[]) obj.get("bytes").raw(), (byte[]) out.asObject().get("bytes").raw()));
        for (String k : obj.keySet()) {
            if (!k.equals("bytes") && !k.equals("none") && !k.equals("n")) {
                assertEquals(obj.get(k), out.asObject().get(k), k);
            }
        }
        for (String k : obj.keySet()) {
            AxonValue a = obj.get(k);
            AxonValue b = out.asObject().get(k);
            assertEquals(a.type(), b.type(), k);
            if (a.isNumber()) {
                assertEquals(a.raw().getClass(), b.raw().getClass(), k);
            }
        }
        assertEquals(new BigDecimal("10.50"), out.asObject().get("d").asDecimal());
        assertEquals("10.50", out.asObject().get("d").asDecimal().toPlainString());
    }

    @Test
    void tiposNumericosNaoSeConfundem() {
        assertTrue(ida(AxonValue.num(1L)).isInteger());
        assertEquals(Double.class, ida(AxonValue.num(1.0)).raw().getClass());
        assertEquals(BigDecimal.class, ida(AxonValue.num(new BigDecimal("1"))).raw().getClass());
        assertTrue(ida(AxonValue.none()).isNone());
        assertTrue(ida(AxonValue.nul()).isNull());
    }

    @Test
    void valoresViramTiposDoPrograma() {
        String src = "def main():\n    return [type(1).__name__ if False else 1]\n";
        assertEquals("[1]", Pl.repr(src));
        assertEquals("[1, 2.5, 'a', True, None]", Pl.repr("def main():\n    return [1, 2.5, 'a', True, None]\n"));
        AxonValue d = Pl.run("def pega(x):\n    return [x['a'] + 1, x['b'][0], x['c']]\n", "pega", Pl.limites(), null,
                new PlHostMemoria(), List.of(AxonJson.parseDocument("{\"a\": 1, \"b\": [\"z\"], \"c\": null}")));
        assertEquals("[2, \"z\", null]", d.toString());
    }

    @Test
    void retornosParaAxonValue() {
        assertEquals(AxonJson.parseDocument("{\"a\": [1, 2], \"b\": 1.5}"),
                Pl.run("def main():\n    return {'a': (1, 2), 'b': 1.5}\n"));
        assertEquals("Decimal('3.00')", Pl.repr("from decimal import Decimal\ndef main():\n    return Decimal('1.50') * 2\n"));
        AxonValue dec = Pl.run("from decimal import Decimal\ndef main():\n    return Decimal('1.50') * 2\n");
        assertEquals(new BigDecimal("3.00"), dec.asDecimal());
        assertEquals(AxonValue.record("pedido", AxonValue.str("p1")), Pl.run("def main():\n    return rid('pedido', 'p1')\n"));
        assertEquals(AxonValue.record("pedido", AxonValue.str("p1")), Pl.run("def main():\n    return rid('pedido:p1')\n"));
        assertEquals(AxonValue.record("pedido", AxonValue.num(5L)), Pl.run("def main():\n    return rid('pedido', 5)\n"));
        assertEquals("pedido:p1", Pl.run("def main():\n    return str(rid('pedido:p1'))\n").asString());
        assertEquals("p1", Pl.run("def main():\n    return rid('pedido:p1').key\n").asString());
        assertEquals("pedido", Pl.run("def main():\n    return rid('pedido:p1').table\n").asString());
    }

    @Test
    void dictComChavesNaoTextoNaoViraObjeto() {
        PlError e = Pl.erro("def main():\n    return {1: 2}\n");
        assertEquals("TypeError", e.tipo());
    }

    @Test
    void datetimeEDuracao() {
        AxonValue r = Pl.run("import time\ndef main():\n    t = time.parse('2024-01-02T03:04:05Z')\n    return [t, t + time.hours(2), (t + time.minutes(30)) - t, time.format(t, '%Y/%m/%d %H:%M:%S'), t.year, t < t + time.seconds(1), time.timestamp(t)]\n");
        List<AxonValue> a = r.asArray();
        assertEquals(Instant.parse("2024-01-02T03:04:05Z"), a.get(0).asInstant());
        assertEquals(Instant.parse("2024-01-02T05:04:05Z"), a.get(1).asInstant());
        assertEquals(1_800_000L, a.get(2).asDuration().millis());
        assertEquals("2024/01/02 03:04:05", a.get(3).asString());
        assertEquals(2024, a.get(4).asLong());
        assertTrue(a.get(5).asBool());
        assertEquals(1704164645.0, a.get(6).asDouble());
        Instant now = Pl.run("import time\ndef main():\n    return time.now()\n").asInstant();
        assertTrue(Math.abs(now.toEpochMilli() - System.currentTimeMillis()) < 5000);
    }

    @Test
    void dbEmMemoria() {
        PlHostMemoria h = new PlHostMemoria();
        h.inserir("pedidos", AxonJson.parseDocument("{\"id\": 1, \"cliente\": \"ana\", \"total\": 10}"));
        h.inserir("pedidos", AxonJson.parseDocument("{\"id\": 2, \"cliente\": \"bia\", \"total\": 20}"));
        h.inserir("pedidos", AxonJson.parseDocument("{\"id\": 3, \"cliente\": \"ana\", \"total\": 5}"));
        String src = String.join("\n",
                "def main():",
                "    mine = db.query('pedidos', {'cliente': 'ana'})",
                "    total = sum(p['total'] for p in mine)",
                "    um = db.one('pedidos', {'id': 2})",
                "    nenhum = db.one('pedidos', {'id': 99})",
                "    db.exec('pedidos', {'id': 4, 'cliente': 'caio', 'total': 1})",
                "    return [len(mine), total, um['cliente'], nenhum, db.count('pedidos')]",
                "");
        AxonValue r = Pl.run(src, "main", Pl.limites(), null, h, null);
        assertEquals("[2, 15, \"bia\", null, 4]", r.toString());
    }

    @Test
    void dbSemHostNaoExiste() {
        PlError e = org.junit.jupiter.api.Assertions.assertThrows(PlError.class,
                () -> Pl.RT.call(PlCompiler.compile("def main():\n    return db.query('x')\n", "t.py"), "main", List.of(), null, null, null, null));
        assertEquals("NameError", e.tipo());
    }

    @Test
    void importDeProgramasDoHost() {
        PlHostMemoria h = new PlHostMemoria();
        h.registrarModulo("util", PlCompiler.compile("K = 5\n_priv = 1\ndef dobra(x):\n    return x * 2\n", "util.py"));
        h.registrarModulo("a", PlCompiler.compile("import b\ndef fa():\n    return 1\n", "a.py"));
        h.registrarModulo("b", PlCompiler.compile("import a\n", "b.py"));
        AxonValue r = Pl.run("import util\nfrom util import dobra, K as kk\ndef main():\n    return [util.dobra(3), dobra(4), kk, util.K]\n",
                "main", Pl.limites(), null, h, null);
        assertEquals("[6, 8, 5, 5]", r.toString());
        PlError e = org.junit.jupiter.api.Assertions.assertThrows(PlError.class, () -> Pl.run(
                "import util\ndef main():\n    return util._priv\n", "main", Pl.limites(), null, h, null));
        assertEquals("AttributeError", e.tipo());
        PlError e2 = org.junit.jupiter.api.Assertions.assertThrows(PlError.class, () -> Pl.run(
                "from util import nada\ndef main():\n    return 1\n", "main", Pl.limites(), null, h, null));
        assertEquals("ImportError", e2.tipo());
        PlError e3 = org.junit.jupiter.api.Assertions.assertThrows(PlError.class, () -> Pl.run(
                "import a\ndef main():\n    return 1\n", "main", Pl.limites(), null, h, null));
        assertEquals("ImportError", e3.tipo());
    }

    @Test
    void semResolvedorSoOsModulosEmbutidos() {
        for (String m : new String[] {"math", "json", "time", "decimal"}) {
            assertEquals(1, Pl.run("import " + m + "\ndef main():\n    return 1\n").asLong());
        }
        assertEquals("ModuleNotFoundError", Pl.erro("import util\ndef main():\n    return 1\n").tipo());
    }

    @Test
    void execDevolveEspacoDeNomes() {
        PlProgram p = PlCompiler.compile(String.join("\n",
                "VERSAO = '1.0'", "CONTADOR = [0]", "_oculta = 9", "TAXAS = {'a': 1}",
                "def inc():", "    CONTADOR[0] += 1", "    return CONTADOR[0]",
                "def versao():", "    return VERSAO", ""), "pkg.py");
        PlNamespace ns = Pl.RT.exec(p, null, null, null);
        assertEquals(List.of("VERSAO", "CONTADOR", "TAXAS", "inc", "versao"), new ArrayList<>(ns.nomes()));
        assertEquals(java.util.Set.of("inc", "versao"), ns.funcoes());
        assertEquals(AxonValue.str("1.0"), ns.constantes().get("VERSAO"));
        assertEquals("[0]", ns.constantes().get("CONTADOR").toString());
        assertEquals(1, ns.call("inc", List.of(), null, null, null, null).asLong());
        assertEquals(2, ns.call("inc", List.of(), null, null, null, null).asLong());
        assertEquals("1.0", ns.call("versao", List.of(), null, null, null, null).asString());
    }

    @Test
    void compilarEExecutarEmVariasThreads() throws Exception {
        PlProgram p = PlCompiler.compile("def fat(n):\n    return 1 if n < 2 else n * fat(n - 1)\ndef main(x):\n    return fat(x)\n", "mt.py");
        assertTrue(p == PlCompiler.compile("def fat(n):\n    return 1 if n < 2 else n * fat(n - 1)\ndef main(x):\n    return fat(x)\n", "mt.py"));
        ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> fs = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final int n = i % 15;
                fs.add(ex.submit(() -> Pl.RT.call(p, "main", List.of(Pl.num(n)), null, null, null, null).asLong()));
            }
            for (int i = 0; i < 64; i++) {
                long esperado = 1;
                for (int k = 2; k <= i % 15; k++) {
                    esperado *= k;
                }
                assertEquals(esperado, fs.get(i).get());
            }
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void argumentoNaoConvertivelNaSaidaDeHost() {
        PlError e = org.junit.jupiter.api.Assertions.assertThrows(PlError.class, () -> Pl.run(
                "def main():\n    return db.query(lambda: 1)\n", "main", Pl.limites(), null, new PlHostMemoria(), null));
        assertEquals("TypeError", e.tipo());
        assertThrows(PlError.class, () -> Pl.run("def main():\n    return db.query('x', {1: 2})\n", "main", Pl.limites(),
                null, new PlHostMemoria(), null));
    }
}
