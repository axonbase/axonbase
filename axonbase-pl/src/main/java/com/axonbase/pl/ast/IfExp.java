package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;

/** Expressao ternaria. */
public final class IfExp extends Expr {
    private final Expr cond;
    private final Expr a;
    private final Expr b;

    public IfExp(Expr cond, Expr a, Expr b) {
        this.cond = cond;
        this.a = a;
        this.b = b;
    }

    @Override
    public Object eval(Frame f) {
        return Ops.truthy(cond.eval(f)) ? a.eval(f) : b.eval(f);
    }
}
