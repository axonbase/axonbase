package com.axonbase.pl.rt;

/** O valor None. Existem dois singletons: NONE (null do AxonValue) e ABSENT (none do AxonValue). */
public final class NoneType {
    public static final NoneType NONE = new NoneType(false);
    public static final NoneType ABSENT = new NoneType(true);
    public final boolean absent;

    private NoneType(boolean absent) {
        this.absent = absent;
    }

    @Override
    public String toString() {
        return "None";
    }
}
