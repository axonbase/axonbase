package com.axonbase.pl.rt;

/** Fatia a:b:c (campos podem ser NoneType). */
public final class PySlice {
    public final Object lo;
    public final Object hi;
    public final Object step;

    public PySlice(Object lo, Object hi, Object step) {
        this.lo = lo;
        this.hi = hi;
        this.step = step;
    }
}
