package com.axonbase.core.storage;

import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Versão de chave: marca d'água do backup incremental")
class BackendVersionTest {

    @Test
    @DisplayName("RocksDB: versão de chave nova continua maior depois de reabrir o banco")
    void rocksVersionSurvivesReopen() throws Exception {
        String dir = Files.createTempDirectory("ver").toString();
        long before;
        try (RocksDbBackend first = new RocksDbBackend(dir)) {
            for (int i = 0; i < 5; i++) {
                first.put("k" + i, new byte[] {1});
            }
            before = first.currentVersion();
            assertTrue(before >= first.versionOf("k4"));
        }
        try (RocksDbBackend reopened = new RocksDbBackend(dir)) {
            reopened.put("novo", new byte[] {1});
            assertTrue(reopened.versionOf("novo") > before,
                "versão nova=" + reopened.versionOf("novo") + " marca anterior=" + before);
            assertTrue(reopened.currentVersion() >= reopened.versionOf("novo"));
        }
    }

    @Test
    @DisplayName("Memória: currentVersion acompanha as escritas")
    void memoryCurrentVersionGrows() {
        MemoryBackend backend = new MemoryBackend();
        long start = backend.currentVersion();
        backend.put("a", new byte[] {1});
        backend.put("b", new byte[] {1});
        assertTrue(backend.currentVersion() >= start + 2);
        assertTrue(backend.versionOf("b") <= backend.currentVersion());
    }
}
