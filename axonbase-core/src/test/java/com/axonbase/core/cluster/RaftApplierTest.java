package com.axonbase.core.cluster;
import com.axonbase.core.storage.MemoryBackend;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class RaftApplierTest{
 @Test void aplicaBatchAtomicoAoStateMachine(){var b=new MemoryBackend();RaftApplier.apply(b,new CommittedBatch("t",Map.of("doc",new byte[]{1},"idx",new byte[]{2}),Set.of()));assertTrue(b.get("doc").isPresent());assertTrue(b.get("idx").isPresent());}
 @Test void snapshotAutoritativoRemoveChavesOrfas(){var b=new MemoryBackend();b.put("orfao",new byte[]{9});b.put("manter",new byte[]{2});
   RaftApplier.apply(b,new CommittedBatch("snap",Map.of("manter",new byte[]{1}),Set.of(),true));
   assertFalse(b.get("orfao").isPresent());
   assertTrue(b.get("manter").isPresent());
   assertArrayEquals(new byte[]{1},b.get("manter").orElseThrow());
 }
}