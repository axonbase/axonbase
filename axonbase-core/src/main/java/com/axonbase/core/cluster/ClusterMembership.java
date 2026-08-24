package com.axonbase.core.cluster;

import java.net.InetSocketAddress;
import java.util.Map;

/** Membros imutáveis de um grupo, separados da lógica de consenso. */
public record ClusterMembership(String localNodeId, Map<String, InetSocketAddress> members) {
    public ClusterMembership {
        members = Map.copyOf(members);
        if (!members.containsKey(localNodeId)) throw new IllegalArgumentException("membro local ausente");
    }
    public int quorum() { return members.size() / 2 + 1; }
}
