package com.axonbase.core.cluster;

import java.util.HashSet;
import java.util.Set;

/** Máquina de estado Raft determinística, sem relógio ou rede. */
public final class ElectionState {
    private final ClusterMembership membership;
    private long term;
    private String votedFor;
    private ClusterRole role = ClusterRole.FOLLOWER;
    private final Set<String> votes = new HashSet<>();

    public ElectionState(ClusterMembership membership, long persistedTerm, String persistedVote) {
        this.membership = membership; this.term = persistedTerm; this.votedFor = persistedVote == null ? "" : persistedVote;
    }
    public long term() { return term; }
    public ClusterRole role() { return role; }
    public String votedFor() { return votedFor; }
    public long startElection() { term++; role = ClusterRole.CANDIDATE; votedFor = membership.localNodeId(); votes.clear(); votes.add(votedFor); return term; }
    public boolean receiveVote(String voter, long voteTerm, boolean granted) { if (voteTerm != term || role != ClusterRole.CANDIDATE || !granted) return false; votes.add(voter); if (votes.size() >= membership.quorum()) { role = ClusterRole.LEADER; return true; } return false; }
    public boolean receiveVoteRequest(String candidate, long candidateTerm) { if (candidateTerm < term) return false; if (candidateTerm > term) { term = candidateTerm; role = ClusterRole.FOLLOWER; votedFor = ""; votes.clear(); } if (votedFor.isEmpty() || votedFor.equals(candidate)) { votedFor = candidate; return true; } return false; }
    public void receiveHeartbeat(String leader, long leaderTerm) { if (leaderTerm >= term) { term = leaderTerm; votedFor = leader; role = ClusterRole.FOLLOWER; votes.clear(); } }
}
