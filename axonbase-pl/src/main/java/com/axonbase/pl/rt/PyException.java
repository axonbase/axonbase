package com.axonbase.pl.rt;

/** Valor de excecao do AxonPL (instancia de um tipo de excecao). */
public final class PyException {
    public final PyType type;
    public final Object[] args;
    public PyException cause;
    public PyException context;

    public PyException(PyType type, Object[] args) {
        this.type = type;
        this.args = args;
    }

    /** Texto equivalente a str(e). */
    public String mensagem() {
        if (args.length == 0) {
            return "";
        }
        if (args.length == 1) {
            if (type.isSubtypeOf(Types.KEY_ERROR)) {
                return Repr.repr(args[0]);
            }
            return Repr.str(args[0]);
        }
        return Repr.repr(new PyTuple(args));
    }
}
