package com.axonbase.pl.rt;

import java.util.LinkedHashMap;

/** Dicionario do AxonPL (ordem de insercao). */
public final class PyDict {
    public final LinkedHashMap<Object, Object> m;

    public PyDict() {
        m = new LinkedHashMap<>();
    }

    /** Insere validando que a chave e hashable. */
    public void put(Object k, Object v) {
        Ops.checkHashable(k);
        m.put(k, v);
    }
}
