package com.axonbase.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbBackendTest {

    @TempDir
    Path tempDir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] v) {
        return new String(v, StandardCharsets.UTF_8);
    }

    @Test
    void crud() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("crud").toString())) {
            assertTrue(b.get("a").isEmpty());
            b.put("a", bytes("1"));
            assertEquals("1", text(b.get("a").orElseThrow()));
            assertFalse(b.putIfAbsent("a", bytes("x")));
            assertTrue(b.putIfAbsent("b", bytes("2")));
            assertEquals("2", text(b.get("b").orElseThrow()));
            assertTrue(b.delete("a"));
            assertTrue(b.get("a").isEmpty());
            assertFalse(b.delete("missing"));
        }
    }

    @Test
    void keysWithPrefix() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("prefix").toString())) {
            b.put("alpha", bytes("1"));
            b.put("beta", bytes("2"));
            b.put("alpine", bytes("3"));
            b.put("boulder", bytes("4"));
            assertEquals(Set.of("alpha", "alpine"), Set.copyOf(b.keysWithPrefix("al")));
            assertEquals(2, b.keysWithPrefix("al").size());
            assertEquals(0, b.keysWithPrefix("z").size());
            assertEquals(4, b.keysWithPrefix("").size());
        }
    }

    @Test
    void versionIncrements() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("version").toString())) {
            assertEquals(0L, b.versionOf("k"));
            b.put("k", bytes("v1"));
            long v1 = b.versionOf("k");
            assertTrue(v1 > 0);
            b.put("k", bytes("v2"));
            long v2 = b.versionOf("k");
            assertTrue(v2 > v1);
            b.delete("k");
            long v3 = b.versionOf("k");
            assertTrue(v3 > v2);
        }
    }

    @Test
    void commitDetectsVersionConflict() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("conflict").toString())) {
            b.put("x", bytes("original"));
            long v = b.versionOf("x");
            b.put("x", bytes("sneaky-update"));
            assertThrows(VersionConflictException.class, () ->
                b.commit(Map.of("x", v), Map.of("x", bytes("tx-update")), Set.of()));
            assertEquals("sneaky-update", text(b.get("x").orElseThrow()));
        }
    }

    @Test
    void commitAtomico() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("atomic").toString())) {
            b.put("a", bytes("1"));
            b.put("b", bytes("2"));
            long va = b.versionOf("a");
            long vb = b.versionOf("b");
            b.commit(Map.of("a", va, "b", vb),
                Map.of("a", bytes("10"), "c", bytes("30")),
                Set.of("b"));
            assertEquals("10", text(b.get("a").orElseThrow()));
            assertTrue(b.get("b").isEmpty());
            assertEquals("30", text(b.get("c").orElseThrow()));
        }
    }

    @Test
    void commitDetectsPhantomRead() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("phantom").toString())) {
            b.put("user:1", bytes("ana"));
            b.put("user:2", bytes("bob"));
            long v1 = b.versionOf("user:1");
            long v2 = b.versionOf("user:2");
            b.put("user:3", bytes("eve"));
            assertThrows(VersionConflictException.class, () ->
                b.commit(Map.of("user:1", v1, "user:2", v2),
                    Map.of("user:1", bytes("ana-updated")),
                    Set.of(),
                    Set.of("user:")));
        }
    }

    @Test
    void commitWithoutValidationAppliesDirectly() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("direct").toString())) {
            b.commit(Map.of(),
                Map.of("a", bytes("1"), "b", bytes("2")),
                Set.of());
            assertEquals("1", text(b.get("a").orElseThrow()));
            assertEquals("2", text(b.get("b").orElseThrow()));
        }
    }

    @Test
    void persistenciaAposReabertura() {
        Path dir = tempDir.resolve("persist");
        try (RocksDbBackend b = new RocksDbBackend(dir.toString())) {
            b.put("persist-key", bytes("survive"));
            b.put("another", bytes("value"));
            b.flush();
        }
        try (RocksDbBackend b = new RocksDbBackend(dir.toString())) {
            assertEquals("survive", text(b.get("persist-key").orElseThrow()));
            assertEquals("value", text(b.get("another").orElseThrow()));
            assertEquals(2, b.keysWithPrefix("").size());
        }
    }

    @Test
    void persistenciaBatchAposReabertura() {
        Path dir = tempDir.resolve("batch-persist");
        try (RocksDbBackend b = new RocksDbBackend(dir.toString())) {
            b.commit(Map.of(),
                Map.of("k1", bytes("v1"), "k2", bytes("v2")),
                Set.of());
            b.flush();
        }
        try (RocksDbBackend b = new RocksDbBackend(dir.toString())) {
            assertEquals("v1", text(b.get("k1").orElseThrow()));
            assertEquals("v2", text(b.get("k2").orElseThrow()));
        }
    }

    @Test
    void flushEFsyncSemErro() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("flush").toString())) {
            b.put("sync", bytes("data"));
            assertDoesNotThrow(b::flush);
        }
    }

    @Test
    void ordenacaoLexicografica() {
        try (RocksDbBackend b = new RocksDbBackend(tempDir.resolve("order").toString())) {
            b.put("b", bytes("2"));
            b.put("a", bytes("1"));
            b.put("c", bytes("3"));
            assertEquals(List.of("a", "b", "c"), b.keysWithPrefix(""));
        }
    }
}