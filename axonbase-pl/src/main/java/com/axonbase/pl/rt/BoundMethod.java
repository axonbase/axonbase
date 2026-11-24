package com.axonbase.pl.rt;

import java.util.Map;

/** Metodo ligado a um objeto (por exemplo lst.append sem chamar). */
public final class BoundMethod extends PyCallable {
    private final Object self;
    private final Method m;
    private final String nome;

    public BoundMethod(Object self, Method m, String nome) {
        this.self = self;
        this.m = m;
        this.nome = nome;
    }

    @Override
    public Object call(Frame caller, Object[] args, Map<String, Object> kw) {
        return m.call(caller, self, args, kw);
    }

    @Override
    public String nome() {
        return nome;
    }
}
