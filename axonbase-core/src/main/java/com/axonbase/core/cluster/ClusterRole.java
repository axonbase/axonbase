package com.axonbase.core.cluster;

/** Papel observável de um nó no protocolo de consenso. */
public enum ClusterRole {
    FOLLOWER, CANDIDATE, LEADER
}
