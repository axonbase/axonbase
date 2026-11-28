package com.axonbase.core.partition.cold;

import com.axonbase.common.AxonError;

/**
 * Marca, como causa de um erro, que ele veio da camada fria (não configurada, fora do ar ou com objeto estragado):
 * a falha é do ambiente, não dos dados, então quem a recebe não deve desfazer nada por causa dela.
 */
public final class ColdFailure extends RuntimeException {

    private ColdFailure(Throwable cause) {
        super("falha da camada fria", cause, false, false);
    }

    /** Um erro novo da camada fria. */
    public static AxonError error(String message) {
        return AxonError.internal(message, new ColdFailure(null));
    }

    /** O mesmo erro, com o mesmo código e a mesma mensagem, marcado como vindo da camada fria. */
    public static AxonError mark(AxonError error) {
        return new AxonError(error.code(), error.getMessage(), new ColdFailure(error));
    }

    /** O erro, ou alguma causa dele, veio da camada fria. */
    public static boolean caused(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ColdFailure) {
                return true;
            }
        }
        return false;
    }
}
