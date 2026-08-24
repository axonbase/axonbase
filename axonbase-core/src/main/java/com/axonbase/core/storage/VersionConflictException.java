package com.axonbase.core.storage;

/** A versão lida por uma transação mudou antes do commit otimista. */
public final class VersionConflictException extends RuntimeException {

    public VersionConflictException(String message) {
        super(message);
    }
}
