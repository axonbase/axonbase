package com.axonbase.core.storage;

import com.axonbase.core.cluster.CommittedBatch;

/**
 * Destino dos lotes confirmados (o journal do PITR). No nó único o {@link RocksDbBackend} entrega cada lote logo
 * depois de gravá-lo, ainda com as travas por faixa de chave: lotes que tocam a mesma chave chegam na ordem dos
 * carimbos. No cluster quem entrega é o apply do Raft, com o índice do log.
 *
 * <p>A implementação não pode bloquear: ela roda dentro do caminho de commit.</p>
 */
@FunctionalInterface
public interface BatchSink {

    /**
     * @param index posição do lote: sequência do commit no nó único, índice do log no cluster
     * @param batch escritas e remoções confirmadas, com o carimbo
     */
    void offer(long index, CommittedBatch batch);
}
