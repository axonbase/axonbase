package com.axonbase.pl.rt;

import com.axonbase.pl.PlError;
import com.axonbase.pl.PlProgram;
import com.axonbase.pl.PlTraceFrame;
import com.axonbase.pl.ast.Stmt;
import java.util.Map;

/** Carrega (executa o corpo de) um programa, produzindo o espaco de nomes global. */
public final class ModuleLoader {
    private ModuleLoader() {
    }

    /** Executa o corpo do programa uma vez. */
    public static ModuleInst load(Ctx ctx, PlProgram p, Frame caller) {
        ModuleInst mi = new ModuleInst(p);
        Frame fr = new Frame(ctx, mi.globals, mi, p.moduleCode(), caller);
        try {
            if (ctx.hook != null) {
                ctx.hook.onCall(fr);
            }
            Stmt.runBlock(p.moduleCode().body, fr);
            if (ctx.hook != null) {
                ctx.hook.onReturn(fr, com.axonbase.value.AxonValue.nul());
            }
        } catch (PlError e) {
            if (!e.marcarNotificado() && ctx.hook != null) {
                ctx.hook.onException(fr, e);
            }
            e.registrarQuadro(new PlTraceFrame(p.moduleCode().name, mi.programa, fr.linha(), fr.coluna()));
            throw e;
        }
        return mi;
    }

    /** Nomes publicos (sem sublinhado inicial) com valor. */
    public static void exportar(ModuleInst mi, Map<String, Object> dest) {
        String[] names = mi.program.nomesGlobais();
        for (int i = 0; i < names.length; i++) {
            Object v = mi.globals[i];
            if (v != null && !names[i].startsWith("_")) {
                dest.put(names[i], v);
            }
        }
    }
}
