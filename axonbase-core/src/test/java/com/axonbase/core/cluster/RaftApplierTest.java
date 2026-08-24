package com.axonbase.core.cluster;
import com.axonbase.core.storage.MemoryBackend;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class RaftApplierTest{@Test void aplicaBatchAtomicoAoStateMachine(){var b=new MemoryBackend();RaftApplier.apply(b,new CommittedBatch("t",Map.of("doc",new byte[]{1},"idx",new byte[]{2}),Set.of()));assertTrue(b.get("doc").isPresent());assertTrue(b.get("idx").isPresent());}}
