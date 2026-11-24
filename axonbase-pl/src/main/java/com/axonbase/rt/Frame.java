package com.axonbase.pl.rt;

import com.axonbase.pl.PlFrame;
import com.axonbase.pl.ast.FunctionCode;
import com.axonbase.pl.ast.Stmt;
import com.axonbase.value.AxonValue;
import java.util.LinkedHashMap;
import java.util.Map;

/** Quadro de execucao de uma funcao. */
public final class Frame implements PlFrame {
    public final Ctx ctx;
    public final Object[] slots;
    public final Object[] globals;
    public final FunctionCode code;
    public final ModuleInst mod;
    public final Frame caller;
    public final int depth;
    public Stmt cur;
    public Object ret;

    public Frame(Ctx ctx, Object[] slots, ModuleInst mod, FunctionCode code, Frame caller) {
        this.ctx = ctx;
        this.slots = slots;
        this.mod = mod;
        this.globals = mod.globals;
        this.code = code;
        this.caller = caller;
        this.depth = caller == null ? 0 : caller.depth + 1;
    }

    @Override
    public String funcao() {
        return code.name;
    }

    @Override
    public String programa() {
        return mod.programa;
    }

    @Override
    public int linha() {
        return cur == null ? code.line : cur.line;
    }

    public int coluna() {
        return cur == null ? 1 : cur.col;
    }

    @Override
    public Map<String, AxonValue> locais() {
        Map<String, AxonValue> r = new LinkedHashMap<>();
        String[] names = code.slotNames;
        Object[] src = slots;
        if (code.isModule) {
            names = mod.program.nomesGlobais();
            src = globals;
        }
        for (int i = 0; i < names.length && i < src.length; i++) {
            Object v = src[i];
            if (v instanceof Cell c) {
                v = c.v;
            }
            if (v != null && !names[i].startsWith(".")) {
                r.put(names[i], Convert.paraAxonSolto(v));
            }
        }
        return r;
    }

    @Override
    public PlFrame chamador() {
        return caller;
    }

    @Override
    public int profundidade() {
        return depth;
    }
}
