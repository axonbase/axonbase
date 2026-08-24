package com.axonbase.core.cluster;

/** A escrita chegou a um nó que não é o líder atual. */
public final class NotLeaderException extends RuntimeException {
    public NotLeaderException(String leader) {
        super("escrita deve ser enviada ao líder " + leader);
    }
}
