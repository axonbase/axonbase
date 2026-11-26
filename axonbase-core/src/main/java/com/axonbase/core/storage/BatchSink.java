package com.axonbase.core.storage;

import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.hlc.HlcTimestamp;

import java.util.Map;
import java.util.Set;

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

    /**
     * A forma que o commit do armazenamento usa. Os mapas e os vetores são do chamador, que pode reaproveitá-los
     * assim que a chamada voltar: quem guarda alguma coisa precisa copiar antes disso. Por padrão monta um
     * {@link CommittedBatch}, que já copia tudo.
     */
    default void committed(long index, HlcTimestamp stamp, Map<String, byte[]> puts, Set<String> deletes) {
        offer(index, new CommittedBatch("", puts, deletes, false, stamp));
    }

    /**
     * A linha do tempo vai mudar (a base será trocada). Chamado sem escritas em curso e antes da mudança: o destino
     * precisa terminar o que já recebeu, que pertence à linha do tempo antiga.
     */
    default void historyBreaking() {
    }
}
