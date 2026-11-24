package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;
import com.axonbase.pl.rt.PyList;

/** Indexacao ou fatiamento obj[idx]. */
public final class Subscript extends Expr implements Target {
    public final Expr obj;
    public final Expr idx;

    public Subscript(Expr obj, Expr idx) {
        this.obj = obj;
        this.idx = idx;
    }

    @Override
    public Object eval(Frame f) {
        Object o = obj.eval(f);
        Object k = idx.eval(f);
        if (o instanceof PyList l && k instanceof Long i) {
            long n = l.a.size();
            long j = i < 0 ? i + n : i;
            if (j >= 0 && j < n) {
                return l.a.get((int) j);
            }
        }
        return Ops.getitem(f, o, k);
    }

    @Override
    public void assign(Frame f, Object v) {
        Ops.setitem(f, obj.eval(f), idx.eval(f), v);
    }

    @Override
    public void delete(Frame f) {
        Ops.delitem(f, obj.eval(f), idx.eval(f));
    }
}
