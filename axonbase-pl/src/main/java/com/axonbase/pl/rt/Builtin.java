package com.axonbase.pl.rt;

import java.util.Map;

/** Funcao embutida. */
public final class Builtin extends PyCallable {
    private final String nome;
    private final Fn fn;

    public Builtin(String nome, Fn fn) {
        this.nome = nome;
        this.fn = fn;
    }

    @Override
    public Object call(Frame caller, Object[] args, Map<String, Object> kw) {
        return fn.call(caller, args, kw);
    }

    @Override
    public String nome() {
        return nome;
    }
}
