package com.axonbase.core.cluster;

/** Adapta o estado real de um grupo Raft embutido ao contrato do servidor. */
public final class RaftStatusProvider implements ClusterStatusProvider {

    private final String node;
    private final String cluster;
    private final RaftGroup group;

    public RaftStatusProvider(String node, String cluster, RaftGroup group) {
        this.node = node;
        this.cluster = cluster;
        this.group = group;
    }

    @Override
    public Status status() {
        RaftGroup.Status s = group.status();
        ClusterRole role = node.equals(s.leader()) ? ClusterRole.LEADER : ClusterRole.FOLLOWER;
        return new Status(node, cluster, s.leader() == null ? "" : s.leader(), "", role, s.term(),
            s.commitIndex(), s.activeMembers(), s.members(), group.quorum());
    }
}
