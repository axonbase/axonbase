package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;

/** Literal constante. */
public final class Const extends Expr {
    public final Object value;

    public Const(Object value) {
        this.value = value;
    }

    @Override
    public Object eval(Frame f) {
        return value;
    }
}
