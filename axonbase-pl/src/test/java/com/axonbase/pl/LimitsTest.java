package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Limites de execucao: passos, tempo, profundidade, memoria e cancelamento. */
class LimitsTest {

    private static PlLimitError limite(String src, PlLimits lim) {
        return assertInstanceOf(PlLimitError.class, Pl.erro(src, lim));
    }

    @Test
    void laçoInfinitoEstouraPassos() {
        PlLimitError e = limite("def main():\n    while True:\n        pass\n", PlLimits.padrao());
        assertEquals(PlLimitError.Tipo.PASSOS, e.limite());
        assertTrue(e.getMessage().contains("limite de passos"));
        assertFalse(e.traceback().isEmpty());
        assertEquals("main", e.traceback().get(0).funcao());
    }

    @Test
    void padraoDePassosEUmMilhao() {
        assertEquals(1_000_000, PlLimits.padrao().maxPassos());
        assertEquals(64, PlLimits.padrao().maxProfundidade());
        assertEquals(64L * 1024 * 1024, PlLimits.padrao().maxMemoria());
    }

    @Test
    void laçoEmListaGrandeEPassoAPasso() {
        PlLimitError e = limite("def main():\n    s = 0\n    for i in range(10**9):\n        s += i\n    return s\n",
                PlLimits.padrao().maxPassos(50_000));
        assertEquals(PlLimitError.Tipo.PASSOS, e.limite());
    }

    @Test
    void prazoEmNanossegundos() {
        long t0 = System.nanoTime();
        PlLimitError e = limite("def main():\n    while True:\n        pass\n",
                PlLimits.padrao().maxPassos(Long.MAX_VALUE / 2).prazoNanos(100_000_000L));
        assertEquals(PlLimitError.Tipo.TEMPO, e.limite());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000);
    }

    @Test
    void prazoAbsoluto() {
        PlLimitError e = limite("def main():\n    while True:\n        pass\n",
                PlLimits.padrao().maxPassos(Long.MAX_VALUE / 2).prazoAbsolutoNanos(System.nanoTime() + 50_000_000L));
        assertEquals(PlLimitError.Tipo.TEMPO, e.limite());
    }

    @Test
    void recursaoInfinitaEstouraProfundidade() {
        PlLimitError e = limite("def f(n):\n    return f(n + 1)\ndef main():\n    return f(0)\n", PlLimits.padrao());
        assertEquals(PlLimitError.Tipo.PROFUNDIDADE, e.limite());
        assertTrue(e.traceback().size() >= 60);
    }

    @Test
    void profundidadeConfiguravel() {
        String src = "def f(n):\n    return 0 if n == 0 else 1 + f(n - 1)\ndef main():\n    return f(100)\n";
        assertEquals(PlLimitError.Tipo.PROFUNDIDADE, limite(src, PlLimits.padrao().maxProfundidade(50)).limite());
        assertEquals(100, Pl.run(src, "main", PlLimits.padrao().maxProfundidade(150), null, new PlHostMemoria(), null).asLong());
    }

    @Test
    void recursaoMuitoFundaNaoDerrubaAJvm() {
        // 1 milhao de quadros permitidos, mas a pilha Java e menor: vira PlLimitError(profundidade)
        PlLimitError e = limite("def f(n):\n    return f(n + 1)\ndef main():\n    return f(0)\n",
                PlLimits.padrao().maxProfundidade(10_000_000).maxPassos(100_000_000L));
        assertEquals(PlLimitError.Tipo.PROFUNDIDADE, e.limite());
    }

    @Test
    void stringGiganteEPorRepeticao() {
        assertEquals(PlLimitError.Tipo.MEMORIA, limite("def main():\n    return 'x' * 10**9\n", PlLimits.padrao()).limite());
        assertEquals(PlLimitError.Tipo.MEMORIA,
                limite("def main():\n    s = 'ab'\n    for i in range(40):\n        s = s + s\n    return len(s)\n", PlLimits.padrao()).limite());
        assertEquals(PlLimitError.Tipo.MEMORIA, limite("def main():\n    return 'a'.center(10**10)\n", PlLimits.padrao()).limite());
        assertEquals(PlLimitError.Tipo.MEMORIA, limite("def main():\n    return ','.join(['x' * 9000000] * 5)\n", PlLimits.padrao()).limite());
    }

    @Test
    void maxTamanhoStringConfiguravel() {
        assertEquals(PlLimitError.Tipo.MEMORIA,
                limite("def main():\n    return 'x' * 2000\n", PlLimits.padrao().maxTamanhoString(1000)).limite());
        assertEquals(2000, Pl.run("def main():\n    return len('x' * 2000)\n", "main", PlLimits.padrao().maxTamanhoString(5000),
                null, new PlHostMemoria(), null).asLong());
    }

    @Test
    void listaGiganteELimiteDeElementos() {
        assertEquals(PlLimitError.Tipo.MEMORIA, limite("def main():\n    return [0] * 10**8\n", PlLimits.padrao()).limite());
        assertEquals(PlLimitError.Tipo.MEMORIA, limite("def main():\n    return list(range(10**9))\n", PlLimits.padrao()).limite());
        assertEquals(PlLimitError.Tipo.MEMORIA,
                limite("def main():\n    xs = []\n    while True:\n        xs.append(1)\n", PlLimits.padrao().maxPassos(100_000_000L)).limite());
        assertEquals(PlLimitError.Tipo.MEMORIA,
                limite("def main():\n    return [x for x in range(100)]\n", PlLimits.padrao().maxElementos(10)).limite());
        assertEquals(PlLimitError.Tipo.MEMORIA,
                limite("def main():\n    d = {}\n    for i in range(100):\n        d[i] = i\n    return d\n", PlLimits.padrao().maxElementos(10)).limite());
    }

    @Test
    void estimativaDeMemoriaLogica() {
        String src = "def main():\n    xs = []\n    for i in range(20):\n        xs = [0] * 400000\n    return len(xs)\n";
        PlLimitError e = limite(src, PlLimits.padrao().maxMemoria(16L * 1024 * 1024));
        assertEquals(PlLimitError.Tipo.MEMORIA, e.limite());
    }

    @Test
    void cancelamentoPorOutraThread() throws Exception {
        PlLimits lim = PlLimits.padrao().maxPassos(Long.MAX_VALUE / 2);
        AtomicReference<Throwable> res = new AtomicReference<>();
        CountDownLatch iniciou = new CountDownLatch(1);
        Thread exec = new Thread(() -> {
            try {
                iniciou.countDown();
                Pl.run("def main():\n    while True:\n        pass\n", "main", lim, null, new PlHostMemoria(), null);
            } catch (Throwable t) {
                res.set(t);
            }
        }, "pl-exec");
        exec.start();
        iniciou.await();
        Thread.sleep(100);
        lim.cancelar();
        exec.join(5000);
        assertFalse(exec.isAlive(), "a thread de execucao deve terminar apos o cancelamento");
        PlLimitError e = assertInstanceOf(PlLimitError.class, res.get());
        assertEquals(PlLimitError.Tipo.CANCELADO, e.limite());
    }

    @Test
    void cancelamentoJaAcionadoImpedeInicio() {
        PlLimits lim = PlLimits.padrao();
        lim.cancelar();
        assertEquals(PlLimitError.Tipo.CANCELADO, limite("def main():\n    return 1\n", lim).limite());
        lim.limparCancelamento();
        assertEquals(1, Pl.run("def main():\n    return 1\n", "main", lim, null, new PlHostMemoria(), null).asLong());
    }

    @Test
    void limiteNaoPodeSerCapturadoPeloPrograma() {
        PlLimitError e = limite("def main():\n    try:\n        while True:\n            pass\n    except Exception:\n        return 'capturou'\n    finally:\n        x = 1\n",
                PlLimits.padrao().maxPassos(10_000));
        assertEquals(PlLimitError.Tipo.PASSOS, e.limite());
        PlLimitError e2 = limite("def f(n):\n    try:\n        return f(n + 1)\n    except BaseException:\n        return 0\ndef main():\n    return f(0)\n",
                PlLimits.padrao());
        assertEquals(PlLimitError.Tipo.PROFUNDIDADE, e2.limite());
    }

    @Test
    void passosDentroDeBuiltinsTambemContam() {
        PlLimitError e = limite("def main():\n    return sum(range(10**12))\n", PlLimits.padrao().maxPassos(10_000));
        assertEquals(PlLimitError.Tipo.PASSOS, e.limite());
        PlLimitError e2 = limite("def main():\n    return sum(x for x in range(10**12))\n", PlLimits.padrao().maxPassos(10_000));
        assertTrue(e2.limite() == PlLimitError.Tipo.PASSOS || e2.limite() == PlLimitError.Tipo.MEMORIA);
    }

    @Test
    void jsonProfundoNaoEstoura() {
        PlError e = Pl.erro("import json\ndef main():\n    return json.loads('[' * 100000)\n");
        assertTrue(e instanceof PlRuntimeError || e instanceof PlLimitError);
    }

    @Test
    void naoDeixaThreadsVivas() throws Exception {
        long antes = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("pl-")).count();
        Pl.run("def main():\n    return 1\n");
        long depois = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("pl-")).count();
        assertEquals(antes, depois);
    }
}
