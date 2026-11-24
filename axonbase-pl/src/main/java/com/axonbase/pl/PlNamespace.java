package com.axonbase.pl;

import com.axonbase.pl.rt.Convert;
import com.axonbase.pl.rt.ModuleInst;
import com.axonbase.pl.rt.PyCallable;
import com.axonbase.value.AxonValue;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Espaco de nomes exportado por um programa executado (base do futuro PACKAGE). */
public final class PlNamespace {
    private final ModuleInst mi;

    PlNamespace(ModuleInst mi) {
        this.mi = mi;
    }

    /** Nomes publicos (sem sublinhado inicial) que tem valor. */
    public Set<String> nomes() {
        Set<String> r = new LinkedHashSet<>();
        String[] g = mi.program.nomesGlobais();
        for (int i = 0; i < g.length; i++) {
            if (mi.globals[i] != null && !g[i].startsWith("_")) {
                r.add(g[i]);
            }
        }
        return r;
    }

    /** Nomes das funcoes publicas. */
    public Set<String> funcoes() {
        Set<String> r = new LinkedHashSet<>();
        for (String n : nomes()) {
            if (mi.globals[mi.program.indiceGlobal(n)] instanceof PyCallable) {
                r.add(n);
            }
        }
        return r;
    }

    /** Constantes publicas convertidas para AxonValue (valores sem equivalente viram texto). */
    public Map<String, AxonValue> constantes() {
        Map<String, AxonValue> r = new LinkedHashMap<>();
        for (String n : nomes()) {
            Object v = mi.globals[mi.program.indiceGlobal(n)];
            if (!(v instanceof PyCallable) && !(v instanceof com.axonbase.pl.rt.PyModule)) {
                r.put(n, Convert.paraAxonSolto(v));
            }
        }
        return r;
    }

    /** Chama uma funcao do espaco de nomes (compartilhando as variaveis globais). */
    public AxonValue call(String function, List<AxonValue> args, Map<String, AxonValue> kwargs, PlHost host,
                          PlLimits limits, PlDebugHook hook) {
        return PlRuntime.executar(host, limits, hook, ctx -> PlRuntime.invocar(ctx, mi, function, args, kwargs));
    }
}
