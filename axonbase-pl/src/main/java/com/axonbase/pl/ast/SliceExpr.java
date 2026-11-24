package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.NoneType;
import com.axonbase.pl.rt.PySlice;

/** Fatia a:b:c dentro de colchetes. */
public final class SliceExpr extends Expr {
    private final Expr lo;
    private final Expr hi;
    private final Expr step;

    public SliceExpr(Expr lo, Expr hi, Expr step) {
        this.lo = lo;
        this.hi = hi;
        this.step = step;
    }

    @Override
    public Object eval(Frame f) {
        return new PySlice(lo == null ? NoneType.NONE : lo.eval(f), hi == null ? NoneType.NONE : hi.eval(f),
                step == null ? NoneType.NONE : step.eval(f));
    }
}
