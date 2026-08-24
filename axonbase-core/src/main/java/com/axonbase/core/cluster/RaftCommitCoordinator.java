package com.axonbase.core.cluster;
/** Coordenador que só confirma após o RaftGroup alcançar quórum. */
public final class RaftCommitCoordinator implements CommitCoordinator {
 private final RaftGroup group; public RaftCommitCoordinator(RaftGroup group){this.group=group;}
 @Override public long confirm(String nodeId,CommittedBatch batch){return group.append(nodeId,batch);}
}
