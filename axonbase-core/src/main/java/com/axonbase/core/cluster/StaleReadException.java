package com.axonbase.core.cluster;

/** Este nó não consegue atender a leitura com a consistência pedida; tente o líder ou outro nó. */
public final class StaleReadException extends RuntimeException {

    public StaleReadException(String message) {
        super(message);
    }
}
