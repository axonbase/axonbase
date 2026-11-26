package com.axonbase.core.cluster;

/**
 * Pontos de extensão da replicação, usados pelo carimbo de tempo dos lotes e pelo journal.
 *
 * <p>Todos os métodos são chamados dentro de travas do consenso: precisam ser rápidos e não podem escrever
 * pelo coordenador de commit.</p>
 */
public interface ReplicationHooks {

    ReplicationHooks NONE = new ReplicationHooks() {
    };

    /** Ao instalar os ganchos num nó que já tem log: a entrada mais recente dele, confirmada ou não. */
    default void recovered(CommittedBatch batch) {
    }

    /** No líder, sob a trava que atribui o índice: a ordem das chamadas é a ordem do log. */
    default CommittedBatch stamp(CommittedBatch batch) {
        return batch;
    }

    /** No seguidor ou learner, antes de gravar um lote recebido; {@code false} rejeita o AppendEntries. */
    default boolean accept(CommittedBatch batch) {
        return true;
    }

    /** Em todo nó, em ordem de índice, depois de o lote confirmado chegar ao armazenamento. */
    default void committed(long index, long term, CommittedBatch batch) {
    }
}
