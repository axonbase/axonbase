package com.axonbase.pl;

import com.axonbase.pl.rt.Convert;
import com.axonbase.pl.rt.Ctx;
import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.ModuleInst;
import com.axonbase.pl.rt.ModuleLoader;
import com.axonbase.pl.rt.PyCallable;
import com.axonbase.pl.rt.PyException;
import com.axonbase.pl.rt.Types;
import com.axonbase.value.AxonValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Executor de programas AxonPL. Sem estado: pode ser compartilhado entre threads. */
public final class PlRuntime {

    /**
     * Carrega o programa (executa o corpo uma vez) e chama a funcao.
     *
     * @param program programa compilado
     * @param function nome da funcao de nivel superior
     * @param args argumentos posicionais
     * @param kwargs argumentos nomeados (pode ser null)
     * @param host ponte com o ambiente (pode ser null)
     * @param limits limites (null usa o padrao)
     * @param hook gancho de depuracao (pode ser null)
     * @return valor devolvido pela funcao
     * @throws PlError em erro do programa ou estouro de limite
     */
    public AxonValue call(PlProgram program, String function, List<AxonValue> args, Map<String, AxonValue> kwargs,
                          PlHost host, PlLimits limits, PlDebugHook hook) {
        return executar(host, limits, hook, ctx -> {
            ModuleInst mi = ModuleLoader.load(ctx, program, null);
            return invocar(ctx, mi, function, args, kwargs);
        });
    }

    /**
     * Executa o corpo do programa e devolve o espaco de nomes exportado.
     *
     * @param program programa compilado
     * @param host ponte com o ambiente (pode ser null)
     * @param limits limites (null usa o padrao)
     * @param hook gancho de depuracao (pode ser null)
     * @return funcoes e constantes publicas
     */
    public PlNamespace exec(PlProgram program, PlHost host, PlLimits limits, PlDebugHook hook) {
        ModuleInst[] box = new ModuleInst[1];
        executar(host, limits, hook, ctx -> {
            box[0] = ModuleLoader.load(ctx, program, null);
            return null;
        });
        return new PlNamespace(box[0]);
    }

    static AxonValue executar(PlHost host, PlLimits limits, PlDebugHook hook, Function<Ctx, AxonValue> body) {
        Ctx ctx = new Ctx(host, limits == null ? PlLimits.padrao() : limits, hook);
        try {
            ctx.checkTempo();
            return body.apply(ctx);
        } catch (PlError e) {
            throw e;
        } catch (StackOverflowError so) {
            throw Errors.limite(PlLimitError.Tipo.PROFUNDIDADE, "pilha de execucao esgotada (recursao funda demais)");
        } catch (RuntimeException e) {
            throw Errors.runtime("erro interno do interpretador AxonPL: " + e);
        }
    }

    static AxonValue invocar(Ctx ctx, ModuleInst mi, String fn, List<AxonValue> args, Map<String, AxonValue> kwargs) {
        int idx = mi.program.indiceGlobal(fn);
        Object v = idx < 0 ? null : mi.globals[idx];
        if (v == null) {
            throw Errors.name("a funcao '" + fn + "' nao existe no programa '" + mi.programa + "'");
        }
        if (!(v instanceof PyCallable c)) {
            throw Errors.type("'" + fn + "' nao e uma funcao");
        }
        Object[] a = new Object[args == null ? 0 : args.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = Convert.deAxon(args.get(i));
        }
        Map<String, Object> kw = null;
        if (kwargs != null && !kwargs.isEmpty()) {
            kw = new LinkedHashMap<>();
            for (Map.Entry<String, AxonValue> e : kwargs.entrySet()) {
                kw.put(e.getKey(), Convert.deAxon(e.getValue()));
            }
        }
        Frame root = new Frame(ctx, mi.globals, mi, mi.program.moduleCode(), null);
        Object r = c.call(root, a, kw);
        return Convert.paraAxon(r);
    }

    static boolean unused(PyException e) {
        return e.type == Types.EXCEPTION;
    }
}
