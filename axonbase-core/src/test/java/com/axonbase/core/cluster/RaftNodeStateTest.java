package com.axonbase.core.cluster;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.assertEquals;
class RaftNodeStateTest { @Test void persisteTermoEVoto() throws Exception { var dir=Files.createTempDirectory("raft-state"); try { var first=new RaftNodeState(dir); first.update(7,"n2",42); var recovered=new RaftNodeState(dir); assertEquals(7,recovered.term()); assertEquals("n2",recovered.votedFor()); assertEquals(42,recovered.appliedIndex()); } finally { try(var f=Files.walk(dir)){f.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});} } } }
