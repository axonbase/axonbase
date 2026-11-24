package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Cell;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.PyFunction;

/** Cria um objeto funcao (def e lambda). */
public final class MakeFunction extends Expr {
    public final FunctionCode code;

    public MakeFunction(FunctionCode code) {
        this.code = code;
    }

    @Override
    public Object eval(Frame f) {
        Object[] defs = new Object[code.defaults.length];
        for (int i = 0; i < defs.length; i++) {
            defs[i] = code.defaults[i].eval(f);
        }
        Object[] kwd = new Object[code.kwDefaults.length];
        for (int i = 0; i < kwd.length; i++) {
            kwd[i] = code.kwDefaults[i] == null ? null : code.kwDefaults[i].eval(f);
        }
        Cell[] clo = new Cell[code.freeCapture.length];
        for (int i = 0; i < clo.length; i++) {
            clo[i] = (Cell) f.slots[code.freeCapture[i]];
        }
        return new PyFunction(code, defs, kwd, clo, f.mod);
    }
}
