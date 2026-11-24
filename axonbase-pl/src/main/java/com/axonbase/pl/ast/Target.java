package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Frame;

/** Alvo de atribuicao ou del. */
public interface Target {
    void assign(Frame f, Object v);

    void delete(Frame f);
}
