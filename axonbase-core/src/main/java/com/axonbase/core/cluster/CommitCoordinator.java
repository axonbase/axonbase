package com.axonbase.core.cluster;

import com.axonbase.core.storage.KvBackend;

/** Fronteira entre commit do Datastore e confirmação distribuída. */
public interface CommitCoordinator {

    long confirm(String nodeId, CommittedBatch batch);

    /**
     * Chamado antes de validar uma transação. Espera até o estado local refletir tudo que o log pode vir a
     * confirmar e devolve uma marca que {@link #confirm(String, CommittedBatch, long)} confere.
     */
    default long beginValidation() {
        return 0;
    }

    /**
     * Confirma um lote validado depois de {@link #beginValidation()}. Se a liderança mudou ou uma escrita
     * anterior ficou sem desfecho desde então, a validação não vale mais e nada é gravado.
     */
    default long confirm(String nodeId, CommittedBatch batch, long validation) {
        return confirm(nodeId, batch);
    }

    /** O consenso aplica o lote confirmado neste armazenamento, então quem chamou não deve regravá-lo. */
    default boolean appliesTo(KvBackend backend) {
        return false;
    }
}
