package com.axonbase.core.cluster;
import java.net.InetSocketAddress; import java.util.List;
/** Configuração imutável de um nó de cluster. */
public record ClusterConfig(String nodeId,String clusterId,InetSocketAddress advertiseAddress,List<InetSocketAddress> peers){
 public ClusterConfig { if(nodeId==null||nodeId.isBlank()||clusterId==null||clusterId.isBlank()) throw new IllegalArgumentException("node_id e cluster_id são obrigatórios"); peers=List.copyOf(peers); }
 public static ClusterConfig parse(String nodeId,String clusterId,String advertise,String peers){ return new ClusterConfig(nodeId,clusterId,address(advertise),peers==null||peers.isBlank()?List.of():java.util.Arrays.stream(peers.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(ClusterConfig::address).toList()); }
 private static InetSocketAddress address(String raw){int i=raw.lastIndexOf(':');if(i<1)throw new IllegalArgumentException("endereço inválido: "+raw);return new InetSocketAddress(raw.substring(0,i),Integer.parseInt(raw.substring(i+1)));}
}
