package com.axonbase.pl;

/** Estouro de um limite de execucao. Nao pode ser capturado por try/except do programa. */
public final class PlLimitError extends PlError {

    /** Qual limite estourou. */
    public enum Tipo { PASSOS, TEMPO, PROFUNDIDADE, MEMORIA, CANCELADO }

    private final Tipo limite;

    public PlLimitError(Tipo limite, String mensagem) {
        super("LimitError", mensagem);
        this.limite = limite;
    }

    /** O limite que estourou. */
    public Tipo limite() {
        return limite;
    }
}
