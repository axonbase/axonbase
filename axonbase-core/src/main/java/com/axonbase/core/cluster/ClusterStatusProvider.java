package com.axonbase.core.cluster;
/** Fonte de readiness e estado usada pelo servidor sem acoplá-lo ao transporte. */
public interface ClusterStatusProvider {
 Status status();
 record Status(String nodeId,String clusterId,String leader,long term,long commitIndex,int active,int quorum){
  public boolean ready(){return leader!=null&&active>=quorum;}
  public static Status local(){return new Status("local","local","local",0,0,1,1);}
 }
}
