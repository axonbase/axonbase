package com.axonbase.pl.rt;

import java.util.Arrays;

/** Tupla do AxonPL (imutavel; usavel como chave de dict). */
public final class PyTuple {
    public static final PyTuple VAZIA = new PyTuple(new Object[0]);
    public final Object[] a;

    public PyTuple(Object[] a) {
        this.a = a;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PyTuple t && Arrays.equals(a, t.a);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(a);
    }
}
