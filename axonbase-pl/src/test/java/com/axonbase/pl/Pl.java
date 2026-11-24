package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.List;
import java.util.Map;

/** Utilitarios de teste: compila e executa trechos AxonPL. */
final class Pl {
    private Pl() {
    }

    static final PlRuntime RT = new PlRuntime();

    static PlLimits limites() {
        return PlLimits.padrao().maxPassos(20_000_000L).maxProfundidade(200);
    }

    /** Executa main() do trecho e devolve o valor. */
    static AxonValue run(String src) {
        return run(src, "main", limites(), null, new PlHostMemoria(), null);
    }

    static AxonValue run(String src, String fn, PlLimits lim, PlDebugHook hook, PlHost host, List<AxonValue> args) {
        PlProgram p = PlCompiler.compile(src, "teste.py");
        return RT.call(p, fn, args == null ? List.of() : args, null, host, lim, hook);
    }

    /** Executa main() e devolve repr do resultado como texto. */
    static String repr(String src) {
        return run(src + "\ndef __r__():\n    return repr(main())\n", "__r__", limites(), null,
                new PlHostMemoria(), null).asString();
    }

    /** Executa e espera um erro de execucao. */
    static PlError erro(String src) {
        try {
            run(src);
        } catch (PlError e) {
            return e;
        }
        throw new AssertionError("era esperado um erro, mas o programa terminou normalmente");
    }

    static PlError erro(String src, PlLimits lim) {
        try {
            run(src, "main", lim, null, new PlHostMemoria(), null);
        } catch (PlError e) {
            return e;
        }
        throw new AssertionError("era esperado um erro, mas o programa terminou normalmente");
    }

    static AxonValue num(long n) {
        return AxonValue.num(n);
    }

    static Map<String, AxonValue> vazio() {
        return Map.of();
    }
}
