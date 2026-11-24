package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;

/** Operador binario aritmetico ou bit a bit. */
public final class BinOp extends Expr {
    private final int op;
    public final Expr l;
    public final Expr r;

    public BinOp(int op, Expr l, Expr r) {
        this.op = op;
        this.l = l;
        this.r = r;
    }

    @Override
    public Object eval(Frame f) {
        Object a = l.eval(f);
        Object b = r.eval(f);
        if (a instanceof Long x && b instanceof Long y) {
            return Ops.longOp(op, x, y);
        }
        return Ops.binary(f, op, a, b);
    }
}
