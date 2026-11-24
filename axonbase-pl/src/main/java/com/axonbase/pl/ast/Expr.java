package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;

/** No de expressao: avalia para um valor do AxonPL. */
public abstract class Expr {
    public int line;
    public int col;

    public abstract Object eval(Frame f);

    /** Define a posicao e devolve o proprio no. */
    public final <T extends Expr> T at(int line, int col) {
        this.line = line;
        this.col = col;
        @SuppressWarnings("unchecked")
        T t = (T) this;
        return t;
    }
}
