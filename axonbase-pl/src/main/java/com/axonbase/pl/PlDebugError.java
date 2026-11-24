package com.axonbase.pl;

/** Erro de uso do depurador (comando fora de hora, expressao invalida). Nao e um erro do programa depurado. */
public final class PlDebugError extends PlError {

    public PlDebugError(String mensagem) {
        super("DebugError", mensagem);
    }
}
