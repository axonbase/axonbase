package com.axonbase.pl.rt;

import java.util.Arrays;

/** bytes imutavel. */
public final class PyBytes {
    public final byte[] b;

    public PyBytes(byte[] b) {
        this.b = b;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PyBytes p && Arrays.equals(b, p.b);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(b);
    }
}
