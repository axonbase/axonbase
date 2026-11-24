package com.axonbase.pl.rt;

import com.axonbase.pl.PlLimitError;
import com.axonbase.pl.PlRuntimeError;

/** Fabrica de erros de execucao em portugues. */
public final class Errors {
    private Errors() {
    }

    public static PlRuntimeError make(PyType t, String msg) {
        return new PlRuntimeError(new PyException(t, new Object[] {msg}));
    }

    public static PlRuntimeError type(String msg) {
        return make(Types.TYPE_ERROR, msg);
    }

    public static PlRuntimeError value(String msg) {
        return make(Types.VALUE_ERROR, msg);
    }

    public static PlRuntimeError index(String msg) {
        return make(Types.INDEX_ERROR, msg);
    }

    public static PlRuntimeError attr(String msg) {
        return make(Types.ATTRIBUTE_ERROR, msg);
    }

    public static PlRuntimeError name(String msg) {
        return make(Types.NAME_ERROR, msg);
    }

    public static PlRuntimeError overflow(String msg) {
        return make(Types.OVERFLOW, msg);
    }

    public static PlRuntimeError zero(String msg) {
        return make(Types.ZERO_DIV, msg);
    }

    public static PlRuntimeError runtime(String msg) {
        return make(Types.RUNTIME_ERROR, msg);
    }

    public static PlRuntimeError key(Object k) {
        return new PlRuntimeError(new PyException(Types.KEY_ERROR, new Object[] {k}));
    }

    public static PlRuntimeError overflow64() {
        return overflow("o resultado nao cabe em inteiro de 64 bits (limite do AxonPL); use decimal(...) para numeros maiores");
    }

    public static PlLimitError limite(PlLimitError.Tipo t, String msg) {
        return new PlLimitError(t, msg);
    }

    /** Erro de numero de argumentos. */
    public static PlRuntimeError argc(String fn, int esperado, int recebido) {
        return type(fn + "() espera " + esperado + " argumento(s), mas recebeu " + recebido);
    }
}
