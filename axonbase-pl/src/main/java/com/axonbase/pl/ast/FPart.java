package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Fmt;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Repr;

/** Campo de uma f-string: {expr!conv:spec}. */
public final class FPart extends Expr {
    private final Expr e;
    private final char conv;
    private final Expr spec;

    public FPart(Expr e, char conv, Expr spec) {
        this.e = e;
        this.conv = conv;
        this.spec = spec;
    }

    @Override
    public Object eval(Frame f) {
        Object v = e.eval(f);
        if (conv == 'r' || conv == 'a') {
            v = Repr.repr(v);
        } else if (conv == 's') {
            v = Repr.str(v);
        }
        String sp = spec == null ? "" : (String) spec.eval(f);
        return Fmt.format(v, sp);
    }
}
