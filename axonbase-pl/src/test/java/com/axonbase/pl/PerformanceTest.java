package com.axonbase.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Desempenho basico: fib(27) e 5 milhoes de somas inteiras. Imprime os tempos medidos. */
class PerformanceTest {

    private static long medirMs(String src, String fn, long esperado) {
        PlProgram p = PlCompiler.compile(src, "perf.py");
        PlLimits lim = PlLimits.padrao().maxPassos(1_000_000_000L).maxProfundidade(100);
        long melhor = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            long r = new PlRuntime().call(p, fn, List.of(), null, null, lim, null).asLong();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(esperado, r);
            melhor = Math.min(melhor, ms);
        }
        return melhor;
    }

    @Test
    void fibonacci27() {
        long ms = medirMs("def fib(n):\n    if n < 2:\n        return n\n    return fib(n - 1) + fib(n - 2)\ndef main():\n    return fib(27)\n",
                "main", 196418L);
        System.out.println("[desempenho] fib(27) recursivo: " + ms + " ms (melhor de 3)");
        assertTrue(ms < 3000, "fib(27) levou " + ms + " ms");
    }

    @Test
    void cincoMilhoesDeSomas() {
        long ms = medirMs("def main():\n    s = 0\n    for i in range(5000000):\n        s += i\n    return s\n", "main",
                12499997500000L);
        System.out.println("[desempenho] 5M somas em for/range: " + ms + " ms (melhor de 3)");
        assertTrue(ms < 2000, "5M somas levaram " + ms + " ms");
    }

    @Test
    void cincoMilhoesDeOperacoesEmWhile() {
        long ms = medirMs("def main():\n    s = 0\n    i = 0\n    while i < 5000000:\n        s = s + i * 2 % 7\n        i += 1\n    return s\n",
                "main", 14999998L);
        System.out.println("[desempenho] 5M iteracoes de while com 4 operacoes: " + ms + " ms (melhor de 3)");
        assertTrue(ms < 4000, "while levou " + ms + " ms");
    }
}
