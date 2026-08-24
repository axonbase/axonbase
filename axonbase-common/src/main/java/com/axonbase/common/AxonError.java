package com.axonbase.common;

/**
 * Erro tipado do dominio AxonBase.
 * <p>
 * Os códigos seguen a convención definida no wire protocol: por exemplo,
 * {@code -32700} erro de parse, {@code -32000} erro interno, {@code -32009}
 * conflito de transacción.
 */
public final class AxonError extends RuntimeException {

    private final int code;

    public AxonError(int code, String message) {
        super(message);
        this.code = code;
    }

    public AxonError(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static AxonError internal(String message) {
        return new AxonError(-32000, message);
    }

    public static AxonError internal(String message, Throwable cause) {
        return new AxonError(-32000, message, cause);
    }

    public static AxonError parse(String message) {
        return new AxonError(-32700, message);
    }

    public static AxonError txnConflict(String message) {
        return new AxonError(-32009, message);
    }

    public static AxonError auth(String message) {
        return new AxonError(-32002, message);
    }
}