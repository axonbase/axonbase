package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;
import com.axonbase.pl.rt.PyList;
import com.axonbase.pl.rt.PySet;
import com.axonbase.pl.rt.PyTuple;
import java.util.List;

/** Literal de lista, tupla ou conjunto. kind: 'l', 't' ou 's'. */
public final class SeqLit extends Expr {
    public final char kind;
    public final Expr[] items;
    private final boolean hasStar;

    public SeqLit(char kind, Expr[] items) {
        this.kind = kind;
        this.items = items;
        boolean s = false;
        for (Expr e : items) {
            s |= e instanceof Starred;
        }
        this.hasStar = s;
    }

    @Override
    public Object eval(Frame f) {
        Object[] vals;
        if (!hasStar) {
            vals = new Object[items.length];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = items[i].eval(f);
            }
        } else {
            java.util.ArrayList<Object> acc = new java.util.ArrayList<>();
            for (Expr e : items) {
                if (e instanceof Starred s) {
                    List<Object> l = Ops.toList(f, s.e.eval(f));
                    f.ctx.checkElems((long) acc.size() + l.size());
                    acc.addAll(l);
                } else {
                    acc.add(e.eval(f));
                }
            }
            vals = acc.toArray();
        }
        if (kind == 't') {
            return new PyTuple(vals);
        }
        if (kind == 'l') {
            PyList l = new PyList(vals.length);
            for (Object o : vals) {
                l.a.add(o);
            }
            return l;
        }
        PySet s = new PySet(false);
        for (Object o : vals) {
            s.add(o);
        }
        return s;
    }
}
