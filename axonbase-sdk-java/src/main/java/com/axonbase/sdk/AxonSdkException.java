package com.axonbase.sdk;

/** Erro do lado cliente/SDK do AxonBase. */
public class AxonSdkException extends RuntimeException {

    public AxonSdkException(String message) {
        super(message);
    }

    public AxonSdkException(String message, Throwable cause) {
        super(message, cause);
    }
}