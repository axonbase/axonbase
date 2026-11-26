package com.axonbase.core.shard;

/** Não deu para falar com um nó, e é certo que ele não executou nada: o cliente do grupo pode tentar outro. */
public final class ShardUnreachableException extends RuntimeException {

    public ShardUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
