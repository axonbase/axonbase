package com.axonbase.core.cluster;
/** Adapta o estado real de um grupo Raft ao contrato do servidor. */
public final class RaftStatusProvider implements ClusterStatusProvider { private final String node;private final String cluster;private final RaftGroup group;public RaftStatusProvider(String node,String cluster,RaftGroup group){this.node=node;this.cluster=cluster;this.group=group;}public Status status(){var s=group.status();return new Status(node,cluster,s.leader(),s.term(),s.commitIndex(),s.activeMembers(),group.quorum());}}
