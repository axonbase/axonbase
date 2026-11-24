package com.axonbase.pl.rt;

import java.util.ArrayList;
import java.util.Collection;

/** Lista do AxonPL. */
public final class PyList {
    public final ArrayList<Object> a;

    public PyList() {
        a = new ArrayList<>();
    }

    public PyList(int cap) {
        a = new ArrayList<>(Math.max(cap, 4));
    }

    public PyList(Collection<?> c) {
        a = new ArrayList<>(c);
    }
}
