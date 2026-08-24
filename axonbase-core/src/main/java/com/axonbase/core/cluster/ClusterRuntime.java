package com.axonbase.core.cluster;
import java.io.IOException; import java.nio.file.Path;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;
/** Runtime de nó: estado persistente e listener TCP Raft. */
public final class ClusterRuntime implements AutoCloseable {
 private final ClusterConfig config; private final RaftNode node; private final TcpRaftServer server;
 public ClusterRuntime(ClusterConfig config, Path dataDir) throws IOException {this(config,dataDir,new MemoryBackend());}
 public ClusterRuntime(ClusterConfig config, Path dataDir, VersionedKvBackend backend) throws IOException {this.config=config;node=new RaftNode(config.nodeId(),dataDir,backend);server=new TcpRaftServer(config.advertiseAddress().getPort(),node::handle);}
 public ClusterConfig config(){return config;} public int port(){return server.port();}
 public VersionedKvBackend backend(){return node.stateMachine();}
 public Status status(){return new Status(config.nodeId(),config.clusterId(),port(),node.handle(new RaftWire.Request(RaftWire.VOTE,"",0,"",0,0,new byte[0])).term());}
 @Override public void close() throws IOException{server.close();}
 public record Status(String nodeId,String clusterId,int port,long term){}
}
