package com.axonbase.pl.rt;

import java.util.LinkedHashMap;
import java.util.Map;

/** Modulo (embutido ou carregado pelo host). */
public final class PyModule {
    public final String nome;
    public final Map<String, Object> attrs = new LinkedHashMap<>();

    public PyModule(String nome) {
        this.nome = nome;
    }

    /** Define um atributo e devolve o modulo. */
    public PyModule def(String k, Object v) {
        attrs.put(k, v);
        return this;
    }
}
