package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Ganchos de depuracao: registro de linhas, quadros e bloqueio com retomada. */
class HookTest {

    static final String SRC = String.join("\n",
            "def f(x):",
            "    y = x + 1",
            "    return y",
            "def main():",
            "    a = f(1)",
            "    return a",
            "");

    @Test
    void registraSequenciaDeLinhas() {
        List<Integer> linhas = new ArrayList<>();
        List<String> eventos = new ArrayList<>();
        PlDebugHook hook = new PlDebugHook() {
            @Override
            public void onLine(PlFrame frame, int line) {
                linhas.add(line);
            }

            @Override
            public void onCall(PlFrame frame) {
                eventos.add("call " + frame.funcao());
            }

            @Override
            public void onReturn(PlFrame frame, AxonValue valor) {
                eventos.add("ret " + frame.funcao() + " " + valor);
            }
        };
        AxonValue r = Pl.run(SRC, "main", Pl.limites(), hook, new PlHostMemoria(), null);
        assertEquals(2, r.asLong());
        assertEquals(List.of(1, 4, 5, 2, 3, 6), linhas);
        assertEquals(List.of("call <modulo>", "ret <modulo> null", "call main", "call f", "ret f 2", "ret main 2"), eventos);
    }

    @Test
    void semHookNaoMudaOResultado() {
        AxonValue a = Pl.run(SRC, "main", Pl.limites(), null, new PlHostMemoria(), null);
        AxonValue b = Pl.run(SRC, "main", Pl.limites(), new PlDebugHook() {
        }, new PlHostMemoria(), null);
        assertEquals(a, b);
    }

    @Test
    void quadroExpoeLocaisELinhaEChamador() {
        List<Map<String, AxonValue>> locais = new ArrayList<>();
        List<String> pilha = new ArrayList<>();
        PlDebugHook hook = new PlDebugHook() {
            @Override
            public void onLine(PlFrame frame, int line) {
                if (line == 3) {
                    locais.add(frame.locais());
                    StringBuilder sb = new StringBuilder();
                    for (PlFrame q = frame; q != null; q = q.chamador()) {
                        sb.append(q.funcao()).append(':').append(q.linha()).append('/').append(q.profundidade()).append(' ');
                    }
                    pilha.add(sb.toString().trim());
                }
            }
        };
        Pl.run(SRC, "main", Pl.limites(), hook, new PlHostMemoria(), null);
        assertEquals(Map.of("x", Pl.num(1), "y", Pl.num(2)), locais.get(0));
        assertEquals("f:3/2 main:5/1 <modulo>:1/0", pilha.get(0));
    }

    @Test
    void ganchoDeExcecaoERetornoPorLinhaDoErro() {
        List<String> ev = new ArrayList<>();
        PlDebugHook hook = new PlDebugHook() {
            @Override
            public void onException(PlFrame frame, PlError erro) {
                ev.add(frame.funcao() + ":" + frame.linha() + ":" + erro.tipo());
            }
        };
        PlError e = org.junit.jupiter.api.Assertions.assertThrows(PlError.class, () -> Pl.run(
                "def g():\n    return 1 // 0\ndef main():\n    return g()\n", "main", Pl.limites(), hook, new PlHostMemoria(), null));
        assertEquals("ZeroDivisionError", e.tipo());
        assertEquals(List.of("g:2:ZeroDivisionError"), ev);
    }

    @Test
    void ganchoDeChamadaAoHost() {
        List<String> ev = new ArrayList<>();
        PlHostMemoria host = new PlHostMemoria();
        host.inserir("t", com.axonbase.value.AxonJson.parseDocument("{\"id\": 1}"));
        PlDebugHook hook = new PlDebugHook() {
            @Override
            public void onHostCall(PlFrame frame, String modulo, String nome, List<AxonValue> args,
                                   Map<String, AxonValue> kwargs) {
                ev.add(modulo + "." + nome + " " + args + " " + frame.funcao() + ":" + frame.linha());
            }
        };
        AxonValue r = Pl.run("def main():\n    rows = db.query('t')\n    return len(rows)\n", "main", Pl.limites(), hook, host, null);
        assertEquals(1, r.asLong());
        assertEquals(List.of("db.query [\"t\"] main:2"), ev);
    }

    @Test
    void ganchoPodeBloquearERetomar() throws Exception {
        CountDownLatch chegou = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        AtomicReference<Map<String, AxonValue>> vistoLocais = new AtomicReference<>();
        PlDebugHook hook = new PlDebugHook() {
            @Override
            public void onLine(PlFrame frame, int line) {
                if (line == 3) {
                    vistoLocais.set(frame.locais());
                    chegou.countDown();
                    try {
                        assertTrue(liberar.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        AtomicReference<Object> resultado = new AtomicReference<>();
        Thread t = new Thread(() -> resultado.set(
                Pl.run(SRC, "main", Pl.limites(), hook, new PlHostMemoria(), null)), "pl-hook");
        t.start();
        assertTrue(chegou.await(10, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertTrue(t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TIMED_WAITING);
        assertNull(resultado.get());
        assertEquals(Pl.num(2), vistoLocais.get().get("y"));
        liberar.countDown();
        t.join(10_000);
        assertEquals(Pl.num(2), resultado.get());
        assertTrue(!t.isAlive());
    }

    @Test
    void passosSaoContadosEmHookTambem() {
        PlLimitError e = org.junit.jupiter.api.Assertions.assertThrows(PlLimitError.class, () -> Pl.run(
                "def main():\n    while True:\n        pass\n", "main", PlLimits.padrao().maxPassos(500), new PlDebugHook() {
                }, new PlHostMemoria(), null));
        assertEquals(PlLimitError.Tipo.PASSOS, e.limite());
    }
}
