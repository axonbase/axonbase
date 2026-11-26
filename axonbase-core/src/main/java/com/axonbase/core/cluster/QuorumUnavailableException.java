package com.axonbase.core.cluster;

/**
 * Não há maioria de membros ativos para confirmar uma escrita Raft. Lançada assim, a escrita certamente não
 * aconteceu; quando o desfecho não é conhecido, vem a subclasse {@link CommitOutcomeUnknownException}.
 */
public class QuorumUnavailableException extends RuntimeException {
    public QuorumUnavailableException(String group) {
        super("quórum indisponível no grupo " + group);
    }

    QuorumUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
