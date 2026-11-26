package com.axonbase.core.shard;

/** Um shard visto pelo gateway: o grupo inteiro, não um nó. */
@FunctionalInterface
public interface ShardClient {

    /**
     * Executa no líder do shard.
     *
     * @throws ShardException                                       sem nó que atenda dentro do prazo (nada executado)
     * @throws com.axonbase.core.cluster.CommitOutcomeUnknownException a escrita pode ou não ter acontecido
     */
    ShardReply execute(ShardRequest request);
}
