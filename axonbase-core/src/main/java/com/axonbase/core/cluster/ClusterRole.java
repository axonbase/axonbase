package com.axonbase.core.cluster;

/** Papel observável de um nó no protocolo de consenso. */
public enum ClusterRole {
    FOLLOWER, CANDIDATE, LEADER,
    /** Réplica de leitura: recebe o log, não vota e não conta para o quórum. */
    LEARNER
}
