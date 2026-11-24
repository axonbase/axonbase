package com.axonbase.pl.rt;

import java.util.Map;

/** Qualquer valor chamavel. */
public abstract class PyCallable {
    public abstract Object call(Frame caller, Object[] args, Map<String, Object> kw);

    public abstract String nome();
}
