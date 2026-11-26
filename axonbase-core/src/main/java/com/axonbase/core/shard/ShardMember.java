package com.axonbase.core.shard;

/** Um nó de um shard, no mesmo processo ou do outro lado da rede. */
@FunctionalInterface
public interface ShardMember {

    /**
     * @throws com.axonbase.core.cluster.NotLeaderException    este nó não é o líder (nada executado)
     * @throws ShardUnreachableException                       não deu para falar com o nó (nada executado)
     * @throws com.axonbase.core.cluster.QuorumUnavailableException sem maioria
     */
    ShardReply handle(ShardRequest request);
}
