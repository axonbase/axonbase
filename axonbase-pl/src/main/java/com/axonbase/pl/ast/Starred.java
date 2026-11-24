package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;

/** Marca de desempacotamento *expr (so valido em chamadas, literais e alvos). */
public final class Starred extends Expr {
    public final Expr e;

    public Starred(Expr e) {
        this.e = e;
    }

    @Override
    public Object eval(Frame f) {
        throw Errors.type("desempacotamento * so e permitido em chamadas, listas, tuplas e conjuntos");
    }
}
