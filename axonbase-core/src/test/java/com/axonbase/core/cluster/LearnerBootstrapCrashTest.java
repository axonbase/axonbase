package com.axonbase.core.cluster;

import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Entrada por backup: restauração interrompida")
class LearnerBootstrapCrashTest {

    @TempDir
    Path dir;

    private Path backupOfAClusterNode() {
        Path backups = dir.resolve("backups");
        try (RocksDbBackend source = new RocksDbBackend(dir.resolve("origem").toString());
             PhysicalBackups engine = PhysicalBackups.open(backups)) {
            source.applyReplicated(Map.of(RaftApplier.APPLIED_KEY, RaftApplier.marker(7, 2),
                "k", "v".getBytes(StandardCharsets.UTF_8)), Set.of());
            engine.create(source);
        }
        return backups;
    }

    @Test
    @DisplayName("sobras de uma restauração morta no meio não viram base, e a nova tentativa completa")
    void leftoverIsNotTakenAsData() throws IOException {
        Path backups = backupOfAClusterNode();
        Path data = dir.resolve("learner");
        Files.createDirectories(data.resolve(".restoring"));
        Files.writeString(data.resolve(".restoring").resolve("000001.sst"), "metade de um arquivo");

        Optional<RaftApplier.Applied> applied = LearnerBootstrap.restore(backups, data);

        assertTrue(applied.isPresent(), "a sobra foi tomada como base existente");
        assertEquals(7, applied.get().index());
        try (RocksDbBackend restored = new RocksDbBackend(data.toString())) {
            assertTrue(restored.get("k").isPresent());
        }
        assertFalse(Files.exists(data.resolve(".restoring")));
    }

    @Test
    @DisplayName("uma base já pronta não é tocada")
    void existingBaseIsKept() {
        Path backups = backupOfAClusterNode();
        Path data = dir.resolve("learner");
        try (RocksDbBackend existing = new RocksDbBackend(data.toString())) {
            existing.put("meu", new byte[] {1});
        }

        assertTrue(LearnerBootstrap.restore(backups, data).isEmpty());

        try (RocksDbBackend kept = new RocksDbBackend(data.toString())) {
            assertTrue(kept.get("meu").isPresent());
            assertTrue(kept.get("k").isEmpty());
        }
    }
}
