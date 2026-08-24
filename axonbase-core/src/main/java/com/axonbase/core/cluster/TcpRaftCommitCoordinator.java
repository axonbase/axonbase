package com.axonbase.core.cluster;
import java.net.InetSocketAddress;import java.util.List;
/** Confirma batches por AppendEntries TCP em uma maioria de peers. */
public final class TcpRaftCommitCoordinator implements CommitCoordinator{
 private final String group,node;private final List<InetSocketAddress> peers;private long term=1,index;
 public TcpRaftCommitCoordinator(String group,String node,List<InetSocketAddress> peers){this.group=group;this.node=node;this.peers=List.copyOf(peers);}
  public synchronized long confirm(String nodeId,CommittedBatch batch){if(!node.equals(nodeId))throw new NotLeaderException(node);int accepted=1;long next=++index;var acceptedPeers=new java.util.ArrayList<InetSocketAddress>();for(var peer:peers)try{var r=RaftWire.call(peer,new RaftWire.Request(RaftWire.APPEND,group,term,node,next,0,RaftPayload.encode(batch)),1000);if(r.accepted()){accepted++;acceptedPeers.add(peer);}}catch(Exception ignored){}if(accepted<(peers.size()+1)/2+1)throw new QuorumUnavailableException(group);for(var peer:acceptedPeers)try{RaftWire.call(peer,new RaftWire.Request(RaftWire.APPEND,group,term,node,next,next,new byte[0]),1000);}catch(Exception ignored){}return next;}
}
