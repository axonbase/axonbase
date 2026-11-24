package com.axonbase.core.pl;

/** Erro de uso da depuração, já classificado para a API HTTP escolher o status. */
public final class DebugException extends RuntimeException {

    /** Natureza do erro. */
    public enum Kind { BAD_REQUEST, NOT_FOUND, CONFLICT, FORBIDDEN, LIMIT }

    private final Kind kind;

    public DebugException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
