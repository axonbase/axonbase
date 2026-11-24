package com.axonbase.pl;

import com.axonbase.pl.rt.PyException;

/** Excecao do programa AxonPL que nao foi tratada (ou que esta em transito). */
public final class PlRuntimeError extends PlError {

    private final transient PyException excecao;

    /** Uso interno: cria o erro a partir da excecao do interpretador. */
    public PlRuntimeError(PyException e) {
        super(e.type.name, e.mensagem());
        this.excecao = e;
    }

    /** Uso interno: excecao do interpretador. */
    public PyException excecao() {
        return excecao;
    }
}
