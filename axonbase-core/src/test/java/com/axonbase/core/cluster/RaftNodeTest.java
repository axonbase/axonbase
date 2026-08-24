package com.axonbase.core.cluster;
import org.junit.jupiter.api.Test;
import java.nio.file.Files; import java.util.Map; import java.util.Set;
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
}
