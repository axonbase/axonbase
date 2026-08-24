package com.axonbase.core.cluster;
import org.junit.jupiter.api.Test;import java.net.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class ElectionStateTest { private ElectionState s(){return new ElectionState(new ClusterMembership("n1",Map.of("n1",new InetSocketAddress(1),"n2",new InetSocketAddress(2),"n3",new InetSocketAddress(3))),0,"");}
 @Test void promoveComMaioria(){var s=s();assertEquals(1,s.startElection());assertFalse(s.receiveVote("n2",1,false));assertTrue(s.receiveVote("n2",1,true));assertEquals(ClusterRole.LEADER,s.role());}
 @Test void heartbeatDeTermoMaiorRebaixaLider(){var s=s();s.startElection();s.receiveVote("n2",1,true);s.receiveHeartbeat("n3",2);assertEquals(ClusterRole.FOLLOWER,s.role());assertEquals(2,s.term());}}
