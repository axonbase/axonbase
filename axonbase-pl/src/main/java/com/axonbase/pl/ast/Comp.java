package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Cell;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.PyDict;
import com.axonbase.pl.rt.PyList;
import com.axonbase.pl.rt.PySet;

/** Compreensao (lista, dict, set ou gerador avaliado ansiosamente). Roda num quadro proprio. */
public final class Comp extends Expr {
    private final FunctionCode code;
    private final Expr firstIter;
    private final char kind;
    private final Cell[] closure;

    public Comp(FunctionCode code, Expr firstIter, char kind) {
        this.code = code;
        this.firstIter = firstIter;
        this.kind = kind;
        this.closure = null;
    }

    @Override
    public Object eval(Frame f) {
        Object it = firstIter.eval(f);
        Object[] slots = new Object[code.nslots];
        slots[0] = it;
        Object res = kind == 'd' ? new PyDict() : kind == 's' ? new PySet(false) : new PyList();
        slots[1] = res;
        for (int s : code.cellLocals) {
            slots[s] = new Cell();
        }
        for (int i = 0; i < code.freeSlots.length; i++) {
            slots[code.freeSlots[i]] = f.slots[code.freeCapture[i]];
        }
        Frame fr = new Frame(f.ctx, slots, f.mod, code, f);
        Stmt.runBlock(code.body, fr);
        return res;
    }
}
