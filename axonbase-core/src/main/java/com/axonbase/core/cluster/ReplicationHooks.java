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

    /**
     * A partir de que índice os ganchos precisam rever o que o nó já aplicou, por terem perdido numa queda o que
     * só estava na memória. Ao instalá-los, o nó entrega por {@link #committed} as entradas aplicadas dali em
     * diante que o log ainda guarda. {@link Long#MAX_VALUE} quando não há nada a rever.
     */
    default long replayFrom() {
        return Long.MAX_VALUE;
    }

    /**
     * No líder, sob a trava que atribui o índice: a ordem das chamadas é a ordem do log. Também carimba o lote
     * de um snapshot antes do envio, porque quem o recebe pula as entradas que ele cobre.
     */
    default CommittedBatch stamp(CommittedBatch batch) {
        return batch;
    }

    /**
     * No seguidor ou learner, antes de gravar um lote recebido (inclusive o de um snapshot); {@code false}
     * rejeita a requisição.
     */
    default boolean accept(CommittedBatch batch) {
        return true;
    }

    /** Em todo nó, em ordem de índice, depois de o lote confirmado chegar ao armazenamento. */
    default void committed(long index, long term, CommittedBatch batch) {
    }
}
