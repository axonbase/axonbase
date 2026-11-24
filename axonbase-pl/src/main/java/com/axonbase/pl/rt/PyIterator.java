package com.axonbase.pl.rt;

import java.util.Iterator;

/** Iterador do AxonPL (iter, map, zip, enumerate...). */
public final class PyIterator implements Iterator<Object> {
    private final Iterator<Object> it;

    public PyIterator(Iterator<Object> it) {
        this.it = it;
    }

    @Override
    public boolean hasNext() {
        return it.hasNext();
    }

    @Override
    public Object next() {
        return it.next();
    }
}
