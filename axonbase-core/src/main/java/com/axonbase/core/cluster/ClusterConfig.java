package com.axonbase.core.cluster;
import com.axonbase.common.Messages;
import java.net.InetSocketAddress; import java.util.List;
/**
 * Configuração imutável de um nó de cluster. Num learner, {@code peers} são os endereços dos membros que votam.
 */
public record ClusterConfig(String nodeId,String clusterId,InetSocketAddress advertiseAddress,List<InetSocketAddress> peers,boolean learner){
 public ClusterConfig { if(nodeId==null||nodeId.isBlank()||clusterId==null||clusterId.isBlank()) throw new IllegalArgumentException(Messages.get("cluster_node_id_required")); peers=List.copyOf(peers); }
 /** Membro que vota. */
 public ClusterConfig(String nodeId,String clusterId,InetSocketAddress advertiseAddress,List<InetSocketAddress> peers){ this(nodeId,clusterId,advertiseAddress,peers,false); }
 public ClusterConfig asLearner(){ return new ClusterConfig(nodeId,clusterId,advertiseAddress,peers,true); }
 public static ClusterConfig parse(String nodeId,String clusterId,String advertise,String peers){ return new ClusterConfig(nodeId,clusterId,address(advertise),peers==null||peers.isBlank()?List.of():java.util.Arrays.stream(peers.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(ClusterConfig::address).toList()); }
 private static InetSocketAddress address(String raw){int i=raw.lastIndexOf(':');if(i<1)throw new IllegalArgumentException(Messages.get("cluster_address_invalid", raw));return new InetSocketAddress(raw.substring(0,i),Integer.parseInt(raw.substring(i+1)));}
}
