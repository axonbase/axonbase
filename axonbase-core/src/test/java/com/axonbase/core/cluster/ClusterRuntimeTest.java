package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClusterRuntimeTest {
    @Test
    void iniciaListenerDoNo() throws Exception {
        var dir = Files.createTempDirectory("node");
        try (var r = new ClusterRuntime(new ClusterConfig("n1", "c",
            new InetSocketAddress("127.0.0.1", 0), List.of()), dir)) {
            assertTrue(r.port() > 0);
            assertEquals("n1", r.status().nodeId());
        }
    }

    @Test
    void aplicaAppendNoBackendDoRuntimeERecuperaDoLog() throws Exception {
        var dir = Files.createTempDirectory("node");
        var backend = new MemoryBackend();
        var config = new ClusterConfig("n1", "c", new InetSocketAddress("127.0.0.1", 0), List.of());
        try (var runtime = new ClusterRuntime(config, dir, backend)) {
            var response = RaftWire.call(new InetSocketAddress("127.0.0.1", runtime.port()),
                new RaftWire.Request(RaftWire.APPEND, "c", 1, "leader", 1, 1,
                    RaftPayload.encode(new CommittedBatch("tx", Map.of("record", new byte[] {7}), Set.of()))), 1000);
            assertTrue(response.accepted());
            assertArrayEquals(new byte[] {7}, backend.get("record").orElseThrow());
        }
        var recovered = new MemoryBackend();
        try (var runtime = new ClusterRuntime(config, dir, recovered)) {
            assertArrayEquals(new byte[] {7}, recovered.get("record").orElseThrow());
        }
    }
}
