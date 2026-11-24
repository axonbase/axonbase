package com.axonbase.pl.rt;

import java.util.LinkedHashSet;

/** Set ou frozenset do AxonPL. */
public final class PySet {
    public final LinkedHashSet<Object> s;
    public final boolean frozen;

    public PySet(boolean frozen) {
        this.s = new LinkedHashSet<>();
        this.frozen = frozen;
    }

    /** Adiciona validando que o elemento e hashable. */
    public void add(Object o) {
        Ops.checkHashable(o);
        s.add(o);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PySet p && frozen && p.frozen && s.equals(p.s);
    }

    @Override
    public int hashCode() {
        return frozen ? s.hashCode() : System.identityHashCode(this);
    }
}
