package com.axonbase.core.cluster;

import com.axonbase.core.storage.KvBackend;

/** Fronteira entre commit do Datastore e confirmação distribuída. */
public interface CommitCoordinator {

    long confirm(String nodeId, CommittedBatch batch);

    /**
     * Chamado antes de uma transação executar. No líder, espera a base local e o catálogo em memória refletirem tudo
     * que o log pode vir a confirmar, e devolve a mesma marca de {@link #beginValidation()}.
     *
     * @return a marca, ou {@code -1} num nó que não lidera
     */
    default long awaitCatalog() {
        return 0;
    }

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

    /** {@code false} quando este nó sabidamente não pode confirmar escritas (não é o líder). */
    default boolean acceptsWrites(String nodeId) {
        return true;
    }

    /** A admissão de uma escrita; fechá-la diz que ela terminou. */
    interface WritePermit extends AutoCloseable {
        WritePermit NONE = () -> { };

        @Override
        void close();
    }

    /**
     * Chamado antes de executar uma escrita avulsa de cliente. Durante uma transferência de liderança, segura a
     * escrita; se o nó deixar de liderar, lança {@link NotLeaderException} sem que nada tenha sido executado.
     */
    default WritePermit admitWrite() {
        return WritePermit.NONE;
    }

    /** O consenso aplica o lote confirmado neste armazenamento, então quem chamou não deve regravá-lo. */
    default boolean appliesTo(KvBackend backend) {
        return false;
    }
}
