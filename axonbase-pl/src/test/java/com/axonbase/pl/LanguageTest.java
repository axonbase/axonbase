package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Mensagens de erro em PT-BR, traceback estruturado e divergencias intencionais do Python. */
class LanguageTest {

    private static PlRuntimeError rt(String corpo) {
        PlError e = Pl.erro("def main():\n    " + corpo.replace("\n", "\n    ") + "\n");
        return assertInstanceOf(PlRuntimeError.class, e);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "return 1 / 0|ZeroDivisionError|divisao por zero",
        "return 1 // 0|ZeroDivisionError|divisao inteira ou modulo por zero",
        "return 5 % 0|ZeroDivisionError|divisao inteira ou modulo por zero",
        "return 1.0 / 0|ZeroDivisionError|divisao de float por zero",
        "return [1][5]|IndexError|indice de lista fora do intervalo",
        "return 'ab'[9]|IndexError|indice de string fora do intervalo",
        "return (1,)[3]|IndexError|indice de tupla fora do intervalo",
        "return {}['x']|KeyError|'x'",
        "return zzz|NameError|o nome 'zzz' nao esta definido",
        "return 1 + 'a'|TypeError|tipos de operando nao suportados para +: 'int' e 'str'",
        "return 'a' * 'b'|TypeError|tipos de operando nao suportados para *",
        "return -'a'|TypeError|operando invalido para - unario",
        "return len(5)|TypeError|nao tem len()",
        "return int('abc')|ValueError|literal invalido para int() na base 10: 'abc'",
        "return float('x')|ValueError|nao foi possivel converter a string em float",
        "return [].pop()|IndexError|pop de lista vazia",
        "return [1].remove(9)|ValueError|nao esta na lista",
        "return None.x|AttributeError|nao tem o atributo 'x'",
        "return 5()|TypeError|nao e chamavel",
        "return 1 < 'a'|TypeError|nao e suportado entre instancias de 'int' e 'str'",
        "return {[1]: 2}|TypeError|nao hashable",
        "a, b = [1]|ValueError|nao ha valores suficientes para desempacotar",
        "a, b = [1, 2, 3]|ValueError|valores demais para desempacotar",
        "return 2 ** 63|OverflowError|64 bits",
        "return 9223372036854775807 + 1|OverflowError|64 bits",
        "return -(-9223372036854775807 - 1)|OverflowError|64 bits",
        "return 3037000500 * 3037000500|OverflowError|64 bits",
        "return 1 << 64|OverflowError|64 bits",
        "return chr(-1)|ValueError|fora do intervalo",
        "return next(iter([]))|StopIteration|",
        "return range(0, 5, 0)|ValueError|nao pode ser zero",
        "return 'abc'.split('')|ValueError|separador vazio",
        "return [1, 2][1.5]|TypeError|indices devem ser inteiros",
        "x = 1\\ndel x\\nreturn x|UnboundLocalError|usada antes",
        "return undefined_fn(1)|NameError|o nome 'undefined_fn'",
        "return min([])|ValueError|sequencia vazia",
        "return sum(['a'])|TypeError|tipos de operando",
        "return f(1)|NameError|o nome 'f' nao esta definido",
    })
    void mensagensPtBr(String corpo, String tipo, String trecho) {
        PlRuntimeError e = rt(corpo.replace("\\n", "\n"));
        assertEquals(tipo, e.tipo());
        assertTrue(e.mensagem().contains(trecho == null ? "" : trecho), "mensagem: " + e.mensagem());
    }

    @Test
    void variavelLocalAntesDeAtribuicao() {
        PlRuntimeError e = rt("print(v)\nv = 1");
        assertEquals("UnboundLocalError", e.tipo());
        assertTrue(e.mensagem().contains("'v'"));
    }

    @Test
    void tracebackEstruturado() {
        String src = String.join("\n",
                "def interna(x):",
                "    return 10 // x",
                "",
                "def meio(x):",
                "    y = x - 1",
                "    return interna(y)",
                "",
                "def main():",
                "    a = 1",
                "    return meio(1)",
                "");
        PlError e = Pl.erro(src);
        assertEquals("ZeroDivisionError", e.tipo());
        List<PlTraceFrame> tb = e.traceback();
        assertEquals(List.of("main", "meio", "interna"), tb.stream().map(PlTraceFrame::funcao).toList());
        assertEquals(List.of(10, 6, 2), tb.stream().map(PlTraceFrame::linha).toList());
        assertEquals("teste.py", tb.get(0).programa());
        assertTrue(tb.get(2).coluna() >= 1);
        assertTrue(e.tracebackTexto().contains("em interna"));
        assertTrue(e.getMessage().startsWith("ZeroDivisionError: "));
    }

    @Test
    void tracebackIncluiCorpoDoModulo() {
        PlError e = Pl.erro("x = 1 / 0\ndef main():\n    return 1\n");
        assertEquals("<modulo>", e.traceback().get(0).funcao());
        assertEquals(1, e.traceback().get(0).linha());
    }

    @Test
    void erroDoUsuarioComMensagemPropria() {
        PlRuntimeError e = rt("raise ValueError('saldo insuficiente: ' + str(10))");
        assertEquals("ValueError", e.tipo());
        assertEquals("saldo insuficiente: 10", e.mensagem());
    }

    @Test
    void erroApelidoDeException() {
        assertEquals("[1, 'boom']", Pl.repr("def main():\n    try:\n        raise Error('boom')\n    except Exception as e:\n        return [1, str(e)]\n"));
        PlRuntimeError e = rt("raise Error('x')");
        assertEquals("Exception", e.tipo());
    }

    @Test
    void contextoEncadeadoDaExcecao() {
        String src = "def main():\n    try:\n        try:\n            1/0\n        except ZeroDivisionError:\n            raise ValueError('segundo')\n    except ValueError as e:\n        return 'ok'\n";
        assertEquals("'ok'", Pl.repr(src));
    }

    @Test
    void inteirosDe64BitsEOverflowClaro() {
        assertEquals("9223372036854775807", Pl.repr("def main():\n    return 9223372036854775807\n"));
        assertEquals("-9223372036854775808", Pl.repr("def main():\n    return -9223372036854775808\n"));
        assertEquals("-9223372036854775808", Pl.repr("def main():\n    return -9223372036854775807 - 1\n"));
        assertEquals("OverflowError", rt("return abs(-9223372036854775807 - 1)").tipo());
    }

    @Test
    void divisaoEModuloComSemanticaDePiso() {
        assertEquals("[-4, 1, 3, -2]", Pl.repr("def main():\n    return [-7 // 2, -7 % 2, 7 // 2, 7 // -4]\n"));
    }

    @Test
    void divergenciaStringsSaoUtf16() {
        // divergencia intencional: caracteres fora do plano basico contam como 2 (documentado)
        assertEquals("2", Pl.repr("def main():\n    return len('\\U0001F600')\n"));
    }

    @Test
    void divergenciaChavesTrueEUmSaoDistintas() {
        assertEquals("2", Pl.repr("def main():\n    return len({1: 'a', True: 'b'})\n"));
    }

    @Test
    void divergenciaComplexoViraErroClaro() {
        assertEquals("ValueError", rt("return (-8) ** (1 / 3)").tipo());
    }

    @Test
    void divergenciaVisoesDeDictSaoListas() {
        assertEquals("[['a'], [1]]", Pl.repr("def main():\n    d = {'a': 1}\n    return [d.keys(), d.values()]\n"));
    }

    @Test
    void divergenciaSetsIteramPorInsercao() {
        assertEquals("[3, 1, 2]", Pl.repr("def main():\n    return list({3, 1, 2})\n"));
    }

    @Test
    void tipoEmProgramaNaoExpoeInternos() {
        assertEquals("True", Pl.repr("def main():\n    return type(1) is int and type('a') is str and type(None) is type(None)\n"));
    }

    @Test
    void forComDictMutadoNaoQuebra() {
        assertEquals("3", Pl.repr("def main():\n    d = {1: 1, 2: 2}\n    n = 0\n    for k in d:\n        d[k + 10] = 0\n        n += 1\n    return n + 1\n"));
    }

    @Test
    void printVaiParaOHost() {
        PlHostMemoria h = new PlHostMemoria();
        Pl.run("def main():\n    print('a', 1, 2.5, None, [1])\n    print('x', end='')\n    print()\n", "main", Pl.limites(), null, h, null);
        assertEquals(List.of("a 1 2.5 None [1]", "x", ""), h.logs());
    }

    @Test
    void globaisDoModuloAvaliadasUmaVezPorCarga() {
        String src = "CONT = [0]\nBASE = len('abc') * 2\ndef main():\n    CONT[0] += 1\n    return CONT[0] + BASE\n";
        PlProgram p = PlCompiler.compile(src, "g.py");
        assertEquals(7, Pl.RT.call(p, "main", List.of(), null, null, null, null).asLong());
        assertEquals(7, Pl.RT.call(p, "main", List.of(), null, null, null, null).asLong());
    }

    @Test
    void chamadaComArgumentosEKwargsDoHost() {
        PlProgram p = PlCompiler.compile("def soma(a, b=10, *, c=0):\n    return a + b + c\n", "k.py");
        var r = Pl.RT.call(p, "soma", List.of(Pl.num(1)), java.util.Map.of("c", Pl.num(5)), null, null, null);
        assertEquals(16, r.asLong());
        PlError e = org.junit.jupiter.api.Assertions.assertThrows(PlError.class,
                () -> Pl.RT.call(p, "naoexiste", List.of(), null, null, null, null));
        assertEquals("NameError", e.tipo());
        PlError e2 = org.junit.jupiter.api.Assertions.assertThrows(PlError.class,
                () -> Pl.RT.call(p, "soma", List.of(), null, null, null, null));
        assertEquals("TypeError", e2.tipo());
    }

    @Test
    void funcaoNaoPodeSairDoPrograma() {
        PlError e = Pl.erro("def main():\n    return lambda: 1\n");
        assertEquals("TypeError", e.tipo());
    }

    @Test
    void whileComElseEClosuresEmLoop() {
        assertEquals("[0, 1, 2]", Pl.repr("def main():\n    fs = []\n    for i in range(3):\n        fs.append(lambda i=i: i)\n    return [f() for f in fs]\n"));
    }
}
