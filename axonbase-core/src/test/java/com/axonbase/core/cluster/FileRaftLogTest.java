package com.axonbase.core.cluster;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileRaftLogTest {
 @Test void recuperaEntradasPersistidas() throws Exception {
  var dir=Files.createTempDirectory("raft-log");
  try { var log=new FileRaftLog(dir); log.append(new FileRaftLog.Entry(1,2,new CommittedBatch("tx",Map.of("a",new byte[]{1}),Set.of())));
   var reloaded=new FileRaftLog(dir); assertEquals(1,reloaded.lastIndex()); assertEquals(2,reloaded.entries().get(0).term());
  } finally { try(var files=Files.walk(dir)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
 }
}
