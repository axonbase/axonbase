package com.axonbase.core.cluster;

/** Não há maioria de membros ativos para confirmar uma escrita Raft. */
public final class QuorumUnavailableException extends RuntimeException {
    public QuorumUnavailableException(String group) {
        super("quórum indisponível no grupo " + group);
    }
}
