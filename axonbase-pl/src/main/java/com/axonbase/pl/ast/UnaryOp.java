package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;

/** Operador unario: - + ~ not. */
public final class UnaryOp extends Expr {
    private final char op;
    private final Expr e;

    public UnaryOp(char op, Expr e) {
        this.op = op;
        this.e = e;
    }

    @Override
    public Object eval(Frame f) {
        Object v = e.eval(f);
        return switch (op) {
            case '-' -> Ops.negate(v);
            case '+' -> Ops.positive(v);
            case '~' -> Ops.invert(v);
            default -> Ops.truthy(v) ? Boolean.FALSE : Boolean.TRUE;
        };
    }
}
