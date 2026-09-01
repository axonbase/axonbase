package com.axonbase.core.cluster;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FileRaftLogTest {
 @Test void recuperaEntradasPersistidas() throws Exception {
  var dir=Files.createTempDirectory("raft-log");
  try { var log=new FileRaftLog(dir); log.append(new FileRaftLog.Entry(1,2,new CommittedBatch("tx",Map.of("a",new byte[]{1}),Set.of())));
   var reloaded=new FileRaftLog(dir); assertEquals(1,reloaded.lastIndex()); assertEquals(2,reloaded.entries().get(0).term());
  } finally { try(var files=Files.walk(dir)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
 }

 @Test void compactRemoveEntradasAplicadasMantemNaoAplicadas() throws Exception {
  var dir=Files.createTempDirectory("raft-compact");
  try {
   var log=new FileRaftLog(dir);
   log.append(new FileRaftLog.Entry(1,1,new CommittedBatch("tx1",Map.of(),Set.of())));
   log.append(new FileRaftLog.Entry(2,1,new CommittedBatch("tx2",Map.of(),Set.of())));
   log.append(new FileRaftLog.Entry(3,1,new CommittedBatch("tx3",Map.of(),Set.of())));
   log.append(new FileRaftLog.Entry(4,1,new CommittedBatch("tx4",Map.of(),Set.of())));
   assertEquals(4,log.lastIndex());
   log.compact(2);
   assertEquals(4,log.lastIndex());
   assertNull(log.entry(1));
   assertNull(log.entry(2));
   assertNotNull(log.entry(3));
   assertNotNull(log.entry(4));
   assertEquals(2,log.entries().size());
  } finally { try(var files=Files.walk(dir)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
 }

 @Test void entryLookupEOrdemConstanteAposCompact() throws Exception {
  var dir=Files.createTempDirectory("raft-lookup");
  try {
   var log=new FileRaftLog(dir);
   for (long i=1;i<=100;i++) {
    log.append(new FileRaftLog.Entry(i,1,new CommittedBatch("tx"+i,Map.of(),Set.of())));
   }
   log.compact(50);
   var t0=System.nanoTime();
   for (long i=51;i<=100;i++) {
    assertNotNull(log.entry(i));
   }
   var elapsed=System.nanoTime()-t0;
   assertTrue(elapsed<5_000_000,"entry(long) must be O(1), took "+elapsed+"ns");
  } finally { try(var files=Files.walk(dir)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
 }

 @Test void dadosPersistemAposReaberturaComFsync() throws Exception {
  var dir=Files.createTempDirectory("raft-fsync");
  try {
   var log=new FileRaftLog(dir);
   log.append(new FileRaftLog.Entry(1,1,new CommittedBatch("tx",Map.of("k",new byte[]{42}),Set.of())));
   log.close();
   var reloaded=new FileRaftLog(dir);
   assertEquals(1,reloaded.lastIndex());
   assertArrayEquals(new byte[]{42},reloaded.entry(1).batch().puts().get("k"));
  } finally { try(var files=Files.walk(dir)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} }
 }
}