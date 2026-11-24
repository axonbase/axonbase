package com.axonbase.pl.rt;

import java.util.Map;
import java.util.function.Predicate;

/** Tipo embutido (int, str, ...) ou tipo de excecao. Tambem e chamavel (construtor). */
public final class PyType extends PyCallable {
    public final String name;
    public final PyType parent;
    public final boolean exc;
    private final Predicate<Object> test;
    public Fn ctor;

    public PyType(String name, PyType parent, boolean exc, Predicate<Object> test) {
        this.name = name;
        this.parent = parent;
        this.exc = exc;
        this.test = test;
    }

    public boolean isSubtypeOf(PyType t) {
        for (PyType p = this; p != null; p = p.parent) {
            if (p == t) {
                return true;
            }
        }
        return false;
    }

    /** isinstance para este tipo. */
    public boolean isInstance(Object o) {
        if (exc) {
            return o instanceof PyException e && e.type.isSubtypeOf(this);
        }
        return test.test(o);
    }

    @Override
    public Object call(Frame caller, Object[] args, Map<String, Object> kw) {
        if (exc) {
            if (kw != null && !kw.isEmpty()) {
                throw Errors.type(name + "() nao aceita argumentos nomeados");
            }
            return new PyException(this, args);
        }
        if (ctor == null) {
            throw Errors.type("o tipo '" + name + "' nao pode ser instanciado");
        }
        return ctor.call(caller, args, kw);
    }

    @Override
    public String nome() {
        return name;
    }
}
