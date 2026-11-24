package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;

/** and / or com curto-circuito. */
public final class BoolOp extends Expr {
    private final boolean isAnd;
    private final Expr[] parts;

    public BoolOp(boolean isAnd, Expr[] parts) {
        this.isAnd = isAnd;
        this.parts = parts;
    }

    @Override
    public Object eval(Frame f) {
        Object v = null;
        for (Expr p : parts) {
            v = p.eval(f);
            boolean t = Ops.truthy(v);
            if (isAnd ? !t : t) {
                return v;
            }
        }
        return v;
    }
}
