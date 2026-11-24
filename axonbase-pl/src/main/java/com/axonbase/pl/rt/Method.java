package com.axonbase.pl.rt;

import java.util.Map;

/** Implementacao de metodo embutido. */
@FunctionalInterface
public interface Method {
    Object call(Frame f, Object self, Object[] a, Map<String, Object> kw);
}
