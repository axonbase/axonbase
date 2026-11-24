package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tentativas de escapar do interpretador: nada de reflexao, arquivos, rede, System. */
class SecurityTest {

    private static PlError tenta(String expr) {
        PlError e = Pl.erro("def main():\n    " + expr.replace("\n", "\n    ") + "\n");
        return e;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "return (1).__class__",
        "return ''.__class__.__bases__",
        "return [].__class__.__mro__",
        "return {}.__dict__",
        "return main.__globals__",
        "return main.__code__",
        "return main.__closure__",
        "return print.__self__",
        "return type(1).__subclasses__()",
        "return ().__class__.__base__.__subclasses__()",
        "return 'x'.__doc__",
        "return (lambda: 1).__call__()",
        "return None.__class__",
        "return int.__dict__",
        "return __builtins__",
        "return math_missing.__loader__",
        "x = 'abc'\nreturn x.__len__()",
        "return '{0.__class__}'.format(1)",
        "return '{0.__class__.__mro__}'.format('a')",
        "return f'{(1).__class__}'",
        "return Exception('x').__traceback__",
        "return Exception('x').__cause__",
        "return Exception('x').__class__.__name__",
    })
    void atributosEspeciaisNaoExistem(String corpo) {
        PlError e = tenta(corpo);
        assertTrue(e.tipo().equals("AttributeError") || e.tipo().equals("NameError"), e.tipo() + ": " + e.mensagem());
    }

    @Test
    void atributoDunderTemMensagemClara() {
        PlError e = tenta("return (1).__class__");
        assertEquals("AttributeError", e.tipo());
        assertTrue(e.mensagem().contains("__class__") && e.mensagem().contains("nao esta disponivel"), e.mensagem());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "__import__", "eval", "exec", "compile", "open", "globals", "locals", "vars", "dir", "getattr", "setattr",
        "delattr", "hasattr", "input", "breakpoint", "exit", "quit", "help", "memoryview", "bytearray", "id",
        "super", "object", "property", "staticmethod", "classmethod", "System", "Runtime", "os", "sys", "subprocess",
        "socket", "java", "Class", "__name__", "__file__", "__builtins__", "__loader__", "__spec__", "__doc__"
    })
    void nomesPerigososNaoExistem(String nome) {
        PlError e = tenta("return " + nome);
        assertEquals("NameError", e.tipo(), nome);
        assertTrue(e.mensagem().contains("'" + nome + "'"), e.mensagem());
    }

    @ParameterizedTest
    @ValueSource(strings = {"os", "sys", "subprocess", "socket", "java.lang", "builtins", "importlib", "ctypes", "io",
        "pathlib", "shutil", "urllib", "http", "threading", "multiprocessing", "reflect", "random", "datetime", "re"})
    void importDeModulosDoSistemaFalha(String modulo) {
        String m = modulo.contains(".") ? modulo.replace(".", "_") : modulo;
        PlError e = Pl.erro("def main():\n    import " + m + "\n    return 1\n");
        assertEquals("ModuleNotFoundError", e.tipo());
        PlError e2 = Pl.erro("from " + modulo + " import x\ndef main():\n    return 1\n");
        assertEquals("ModuleNotFoundError", e2.tipo());
    }

    @Test
    void modulosEmbutidosNaoVazamAcessoAoSistema() {
        for (String expr : new String[] {"math.sys", "math.os", "json.__dict__", "math.__name__", "time.sleep(1)",
            "time.system", "json.load", "json.dump", "json.JSONDecoder", "decimal.getcontext()", "math.exec"}) {
            PlError e = tenta("import math, json, time, decimal\nreturn " + expr);
            assertTrue(e.tipo().equals("AttributeError"), expr + " -> " + e.tipo() + " " + e.mensagem());
        }
    }

    @Test
    void embutidosNaoDaoAcessoAClassesJava() {
        for (String expr : new String[] {"print.getClass()", "len.getClass()", "str.getClass()", "'a'.getClass()",
            "[].getClass()", "[1].hashCode()", "'a'.toString()", "{}.get_class()", "(1).getClass()", "print.nome()",
            "print.call(1)", "str.ctor", "int.name", "int.parent"}) {
            PlError e = tenta("return " + expr);
            assertEquals("AttributeError", e.tipo(), expr);
        }
    }

    @Test
    void nomesDoInterpretadorNaoSaoAcessiveisPeloPrograma() {
        // nomes internos com ponto (.0 e .r) nao sao identificadores validos
        assertThrows(() -> PlCompiler.compile("x = .0r\n", "t.py"));
        assertThrows(() -> PlCompiler.compile("def f(.r):\n    pass\n", "t.py"));
    }

    private static void assertThrows(Runnable r) {
        try {
            r.run();
        } catch (PlSyntaxError e) {
            return;
        }
        throw new AssertionError("era esperado erro de sintaxe");
    }

    @Test
    void hostNaoExpoeReflexaoPeloAttr() {
        PlHost host = new PlHost() {
            @Override
            public Set<String> modulosNativos() {
                return Set.of("db");
            }

            @Override
            public Object attr(String modulo, String nome) {
                return nome.equals("ok") ? (PlCallable) (a, k) -> com.axonbase.value.AxonValue.num(1L) : new Object();
            }
        };
        PlError e = assertInstanceOf(PlError.class, org.junit.jupiter.api.Assertions.assertThrows(PlError.class,
                () -> Pl.run("def main():\n    return db.qualquer\n", "main", Pl.limites(), null, host, null)));
        assertEquals("TypeError", e.tipo());
        PlError e2 = org.junit.jupiter.api.Assertions.assertThrows(PlError.class,
                () -> Pl.run("def main():\n    return db.ok.getClass()\n", "main", Pl.limites(), null, host, null));
        assertEquals("AttributeError", e2.tipo());
        assertEquals(1, Pl.run("def main():\n    return db.ok()\n", "main", Pl.limites(), null, host, null).asLong());
    }

    @Test
    void erroDoHostViraRuntimeErrorSemVazarClasse() {
        PlHost host = new PlHost() {
            @Override
            public Set<String> modulosNativos() {
                return Set.of("db");
            }

            @Override
            public Object attr(String modulo, String nome) {
                return (PlCallable) (a, k) -> {
                    throw new IllegalStateException("falha interna");
                };
            }
        };
        PlError e = org.junit.jupiter.api.Assertions.assertThrows(PlError.class,
                () -> Pl.run("def main():\n    return db.query('x')\n", "main", Pl.limites(), null, host, null));
        assertEquals("RuntimeError", e.tipo());
        assertTrue(e.mensagem().contains("falha interna"));
    }

    @Test
    void naoHaComoCriarArquivosNemRede() {
        for (String n : new String[] {"open", "file", "socket", "urlopen", "system", "popen", "fork", "remove", "unlink", "write"}) {
            assertEquals("NameError", tenta("return " + n + "('x')").tipo(), n);
        }
    }
}
