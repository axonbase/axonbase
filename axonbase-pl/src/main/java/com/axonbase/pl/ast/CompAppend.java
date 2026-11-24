package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.PyDict;
import com.axonbase.pl.rt.PyList;
import com.axonbase.pl.rt.PySet;

/** Acrescenta um elemento ao resultado da compreensao (slot 1 do quadro). */
public final class CompAppend extends Stmt {
    private final char kind;
    private final Expr key;
    private final Expr val;

    public CompAppend(char kind, Expr key, Expr val) {
        this.kind = kind;
        this.key = key;
        this.val = val;
    }

    @Override
    protected int exec(Frame f) {
        Object r = f.slots[1];
        if (kind == 'd') {
            Object k = key.eval(f);
            ((PyDict) r).put(k, val.eval(f));
            f.ctx.checkElems(((PyDict) r).m.size());
        } else if (kind == 's') {
            ((PySet) r).add(val.eval(f));
            f.ctx.checkElems(((PySet) r).s.size());
        } else {
            PyList l = (PyList) r;
            l.a.add(val.eval(f));
            f.ctx.checkElems(l.a.size());
        }
        return NORMAL;
    }
}
