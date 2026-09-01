package com.axonbase.core.cluster;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files; import java.util.List; import java.util.Map; import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
class RaftNodeTest {
 @Test void persisteVotoEAppendEntriesIdempotente() throws Exception {
  var dir=Files.createTempDirectory("raft-node"); try {
   var node=new RaftNode("n2",dir);
   assertTrue(node.handle(new RaftWire.Request(RaftWire.VOTE,"g",3,"n1",0,0,new byte[0])).accepted());
   var batch=new CommittedBatch("tx",Map.of("key",new byte[]{1}),Set.of());
   var request=new RaftWire.Request(RaftWire.APPEND,"g",3,"n1",4,4,RaftPayload.encode(batch));
   assertEquals(4,node.handle(request).matchIndex()); assertEquals(4,node.handle(request).matchIndex());
   var recovered=new RaftNode("n2",dir);
   assertFalse(recovered.handle(new RaftWire.Request(RaftWire.VOTE,"g",2,"n3",0,0,new byte[0])).accepted());
  } finally { try(var f=Files.walk(dir)){f.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
  }
  @Test void descartaCaudaNaoConfirmada() throws Exception {
  var dir=Files.createTempDirectory("raft-tail"); try {
   var node=new RaftNode("n1",dir);
   node.appendLocal(1,1,new CommittedBatch("pending",Map.of("x",new byte[]{1}),Set.of()));
   node.discardUncommittedFrom(1);
   assertEquals(0,node.lastIndex());
  } finally { try(var f=Files.walk(dir)){f.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
  }
  @Test void listenerDeBatchNaoRodaSobMonitorDoNo() throws Exception {
   var dir=Files.createTempDirectory("raft-listener"); try {
    var node=new RaftNode("n1",dir); var held=new AtomicBoolean();
    node.listener(applied -> held.set(Thread.holdsLock(node)));
    var batch=new CommittedBatch("tx",Map.of("x",new byte[]{1}),Set.of());
    node.handle(new RaftWire.Request(RaftWire.APPEND,"g",1,"leader",1,1,RaftPayload.encode(batch)));
    assertFalse(held.get());
   } finally { try(var f=Files.walk(dir)){f.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
  }
  @Test void commitThroughDetectaConfigEAtualizaPeers() throws Exception {
   var dir=Files.createTempDirectory("raft-config"); try {
    var node=new RaftNode("n1",dir);
    assertTrue(node.peers().isEmpty());
    byte[] configBytes="10.0.0.1:7001,10.0.0.2:7002".getBytes(StandardCharsets.UTF_8);
    var batch=new CommittedBatch("config",Map.of("__raft/config",configBytes),Set.of());
    var request=new RaftWire.Request(RaftWire.APPEND,"g",1,"leader",1,1,RaftPayload.encode(batch));
    node.handle(request);
    assertEquals(2,node.peers().size());
    assertEquals(7001,node.peers().get(0).getPort());
    // Após recovery, peers devem persistir
    var recovered=new RaftNode("n1",dir);
    assertEquals(2,recovered.peers().size());
   } finally { try(var f=Files.walk(dir)){f.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
  }
}
