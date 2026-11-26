package com.axonbase.core.cluster;

import com.axonbase.common.Messages;

/**
 * O desfecho da escrita é desconhecido: ela entrou no log do líder, mas a confirmação não chegou. Pode já ter
 * sido confirmada, vir a ser quando o quórum voltar, ou nunca acontecer. Não é uma falha: antes de repetir,
 * consulte o estado.
 *
 * <p>É uma subclasse de {@link QuorumUnavailableException} para não quebrar quem já a tratava; capture esta
 * primeiro, porque repetir às cegas pode duplicar a escrita.</p>
 */
public final class CommitOutcomeUnknownException extends QuorumUnavailableException {

    public CommitOutcomeUnknownException(String group) {
        this(group, null);
    }

    public CommitOutcomeUnknownException(String group, Throwable cause) {
        super(Messages.get("cluster_outcome_unknown", group), cause);
    }
}
