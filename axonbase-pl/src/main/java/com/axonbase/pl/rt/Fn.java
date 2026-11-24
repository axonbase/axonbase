package com.axonbase.pl.rt;

import java.util.Map;

/** Implementacao de funcao embutida. */
@FunctionalInterface
public interface Fn {
    Object call(Frame f, Object[] a, Map<String, Object> kw);
}
