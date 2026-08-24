package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RaftGroupTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    @Test
    void quorumReplicaBatchEFailoverElegeNovoLider() {
        RaftGroup group = RaftGroup.inMemory("app/main", "n1", "n2", "n3");
        group.append("n1", new CommittedBatch("tx-1", Map.of("doc", bytes("v1")), Set.of()));
        assertEquals("v1", text(group.backend("n2").get("doc").orElseThrow()));

        group.stop("n1");
        assertEquals("n2", group.leader());
        group.append("n2", new CommittedBatch("tx-2", Map.of("doc", bytes("v2")), Set.of()));
        assertEquals("v2", text(group.backend("n3").get("doc").orElseThrow()));
    }

    @Test
    void semQuorumNaoConfirmaEscrita() {
        RaftGroup group = RaftGroup.inMemory("app/main", "n1", "n2", "n3");
        group.stop("n2");
        group.stop("n3");

        assertThrows(QuorumUnavailableException.class, () ->
            group.append("n1", new CommittedBatch("tx", Map.of("doc", bytes("v")), Set.of())));
        assertThrows(java.util.NoSuchElementException.class,
            () -> group.backend("n1").get("doc").orElseThrow());
    }

    @Test
    void noRecuperadoRecebeSnapshotDoLider() {
        RaftGroup group = RaftGroup.inMemory("app/main", "n1", "n2", "n3");
        group.stop("n3");
        group.append("n1", new CommittedBatch("tx", Map.of("doc", bytes("v")), Set.of()));

        group.start("n3");
        assertEquals("v", text(group.backend("n3").get("doc").orElseThrow()));
    }
}
