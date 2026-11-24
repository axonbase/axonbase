package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Erros de sintaxe amigaveis em PT-BR e introspeccao estatica. */
class SyntaxTest {

    private static PlSyntaxError sintaxe(String src) {
        return assertThrows(PlSyntaxError.class, () -> PlCompiler.compile(src, "t.py"));
    }

    @Test
    void tabulacaoNaIndentacaoEErroClaro() {
        PlSyntaxError e = sintaxe("def f():\n\treturn 1\n");
        assertTrue(e.mensagemSimples().contains("tabulacao"), e.getMessage());
        assertEquals(2, e.linha());
    }

    @Test
    void indentacaoInconsistente() {
        PlSyntaxError e = sintaxe("def f():\n    a = 1\n  b = 2\n");
        assertTrue(e.mensagemSimples().contains("indentacao inconsistente"), e.getMessage());
        assertEquals(3, e.linha());
    }

    @Test
    void indentacaoInesperada() {
        PlSyntaxError e = sintaxe("x = 1\n    y = 2\n");
        assertTrue(e.mensagemSimples().contains("indentacao inesperada"));
    }

    @Test
    void blocoSemCorpo() {
        PlSyntaxError e = sintaxe("def f():\nx = 1\n");
        assertTrue(e.mensagemSimples().contains("bloco indentado"), e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "class A:\\n    pass|classes",
        "def f():\\n    yield 1|yield",
        "async def f():\\n    pass|async",
        "with open('x') as f:\\n    pass|with",
        "match x:\\n    case 1:\\n        pass|match",
        "@dec\\ndef f():\\n    pass|decoradores",
        "x = (y := 5)|walrus",
        "a = b @ c|matriz",
        "from os import *|import",
        "x = 3j|complexos",
        "async with a:\\n    pass|async",
        "def f():\\n    await g()|async",
    })
    void recursosForaDaVersao1(String src, String palavra) {
        PlSyntaxError e = sintaxe(src.replace("\\n", "\n"));
        assertTrue(e.mensagemSimples().contains("AxonPL 1.0") || e.mensagemSimples().contains("complexos"), e.getMessage());
        assertTrue(e.mensagemSimples().contains(palavra), e.getMessage());
    }

    @Test
    void posicaoELinhaDoErro() {
        PlSyntaxError e = sintaxe("a = 1\nb = (2 +\n");
        assertTrue(e.linha() >= 2);
        assertTrue(e.getMessage().contains("Erro de sintaxe na linha"));
        PlSyntaxError e2 = sintaxe("x = 1\ny = = 2\n");
        assertEquals(2, e2.linha());
        assertEquals("y = = 2", e2.trecho());
    }

    @Test
    void stringNaoTerminada() {
        PlSyntaxError e = sintaxe("x = 'abc\n");
        assertTrue(e.mensagemSimples().contains("string nao terminada"), e.getMessage());
    }

    @Test
    void parentesesNaoFechados() {
        assertTrue(sintaxe("x = (1, 2\n").mensagemSimples().contains("nao fechados"));
        assertTrue(sintaxe("x = 1)\n").mensagemSimples().contains("sem abertura"));
    }

    @Test
    void caractereInvalido() {
        assertTrue(sintaxe("x = 1 $ 2\n").mensagemSimples().contains("caractere invalido"));
        assertTrue(sintaxe("x = 1 ? 2\n").mensagemSimples().contains("caractere invalido"));
    }

    @Test
    void inteiroLiteralGrandeDemais() {
        PlSyntaxError e = sintaxe("x = 99999999999999999999\n");
        assertTrue(e.mensagemSimples().contains("64 bits"), e.getMessage());
    }

    @Test
    void atribuicaoInvalida() {
        assertTrue(sintaxe("1 = x\n").mensagemSimples().contains("atribuir"));
        assertTrue(sintaxe("f() = 3\n").mensagemSimples().contains("atribuir"));
    }

    @Test
    void tryPrecisaDeHandler() {
        assertTrue(sintaxe("try:\n    pass\n").mensagemSimples().contains("except"));
    }

    @Test
    void elseSolto() {
        assertTrue(sintaxe("else:\n    pass\n").mensagemSimples().contains("sem o comando de abertura"));
    }

    @Test
    void palavraReservadaComoNome() {
        assertTrue(sintaxe("def if():\n    pass\n").mensagemSimples().contains("nome"));
        assertTrue(sintaxe("x = lambda: for\n").mensagemSimples().length() > 0);
    }

    @Test
    void nonlocalSemLigacao() {
        assertThrows(PlSyntaxError.class, () -> PlCompiler.compile("def f():\n    nonlocal x\n    x = 1\n", "t.py"));
    }

    @Test
    void parametroSemPadraoDepoisDePadrao() {
        assertTrue(sintaxe("def f(a=1, b):\n    pass\n").mensagemSimples().contains("valor padrao"));
    }

    @Test
    void quebraDeLinhaWindowsEAceita() {
        assertEquals(1L, Pl.run("def main():\r\n    return 1\r\n").asLong());
    }

    @Test
    void programaVazioECompilaSemErro() {
        PlProgram p = PlCompiler.compile("", "vazio.py");
        assertTrue(p.functions().isEmpty());
        PlProgram q = PlCompiler.compile("# so comentario\n\n", "c.py");
        assertTrue(q.constants().isEmpty());
    }

    @Test
    void introspeccaoDeFuncoes() {
        PlProgram p = PlCompiler.compile(String.join("\n",
                "import math",
                "from json import dumps as d",
                "LIMITE = 10",
                "NOME: str = 'x'",
                "_privada = 1",
                "def soma(a: int, b=2, *args, c: float = 1.5, **kw) -> int:",
                "    \"\"\"Soma dois numeros.\"\"\"",
                "    return a + b",
                "def outra():",
                "    pass",
                ""), "intro.py");
        assertEquals(List.of("soma", "outra"), p.functions().stream().map(PlFunctionInfo::nome).toList());
        PlFunctionInfo f = p.functions().get(0);
        assertEquals("Soma dois numeros.", f.doc());
        assertEquals("int", f.retorno());
        assertEquals(6, f.parametros().get(0).nome().length() + 5);
        assertEquals(List.of("a", "b", "args", "c", "kw"), f.parametros().stream().map(PlParamInfo::nome).toList());
        assertEquals("int", f.parametros().get(0).anotacao());
        assertEquals("2", f.parametros().get(1).padrao());
        assertEquals(PlParamInfo.Tipo.ARGS, f.parametros().get(2).tipo());
        assertEquals(PlParamInfo.Tipo.SOMENTE_NOMEADO, f.parametros().get(3).tipo());
        assertEquals("1.5", f.parametros().get(3).padrao());
        assertEquals("float", f.parametros().get(3).anotacao());
        assertEquals(PlParamInfo.Tipo.KWARGS, f.parametros().get(4).tipo());
        assertEquals(6, f.linha());
        assertEquals(List.of("LIMITE", "NOME"), p.constants().stream().map(PlConstantInfo::nome).toList());
        assertEquals("10", p.constants().get(0).valor());
        assertEquals(2, p.imports().size());
        assertEquals("math", p.imports().get(0).modulo());
        assertEquals(List.of("dumps"), p.imports().get(1).nomes());
    }
}
