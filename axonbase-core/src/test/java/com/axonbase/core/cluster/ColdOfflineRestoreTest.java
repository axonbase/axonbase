package com.axonbase.core.cluster;

import com.axonbase.common.AxonError;
import com.axonbase.core.partition.ColdTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Restauração fora de linha de um nó de cluster com camada fria")
class ColdOfflineRestoreTest {

    @TempDir
    Path dir;

    /** O RocksDB grava {@code CURRENT} em toda base: sem ele não há base publicada. */
    private static boolean published(Path data) {
        return Files.exists(data.resolve("CURRENT"));
    }

    @Test
    @DisplayName("a base que referencia a camada fria não é preparada sem ela configurada")
    void prepareWithoutColdTierRefuses() {
        Path backups = ColdBaseFixture.backup(dir);
        Path target = dir.resolve("restaurada");

        AxonError refused = assertThrows(AxonError.class,
            () -> ClusterRestore.prepare(backups, 1, List.of(), target, null));

        assertTrue(refused.getMessage().contains("AXON_COLD_DIR"), refused.getMessage());
        assertFalse(published(target), "a base foi publicada");
        // recusar não estraga o destino: com a camada fria a mesma chamada funciona
        try (ColdTier cold = ColdBaseFixture.tier(dir, "restore")) {
            ClusterRestore.prepare(backups, 1, List.of(), target, null, cold);
        }
        assertTrue(published(target));
    }

    @Test
    @DisplayName("com um objeto frio faltando, a base não é publicada")
    void prepareWithMissingObjectRefuses() {
        Path backups = ColdBaseFixture.backup(dir);
        ColdBaseFixture.loseColdObjects(dir);
        Path target = dir.resolve("restaurada");

        try (ColdTier cold = ColdBaseFixture.tier(dir, "restore")) {
            AxonError refused = assertThrows(AxonError.class,
                () -> ClusterRestore.prepare(backups, 1, List.of(), target, null, cold));

            assertTrue(refused.getMessage().contains("parts/n/d/events/"), refused.getMessage());
        }
        assertFalse(published(target), "a base foi publicada");
    }

    @Test
    @DisplayName("com a camada fria e os objetos, a base é preparada")
    void prepareWithColdTier() {
        Path backups = ColdBaseFixture.backup(dir);
        Path target = dir.resolve("restaurada");

        try (ColdTier cold = ColdBaseFixture.tier(dir, "restore")) {
            ClusterRestore.Prepared prepared = ClusterRestore.prepare(backups, 1, List.of(), target, null, cold);

            assertEquals(ColdBaseFixture.INDEX, prepared.baseIndex());
        }
        assertTrue(published(target));
    }

    @Test
    @DisplayName("um learner sem camada fria não restaura o backup que a referencia, e continua sem base")
    void learnerWithoutColdTierRefuses() {
        Path backups = ColdBaseFixture.backup(dir);
        Path data = dir.resolve("learner");

        AxonError refused = assertThrows(AxonError.class, () -> LearnerBootstrap.restore(backups, data));

        assertTrue(refused.getMessage().contains("AXON_COLD_DIR"), refused.getMessage());
        assertFalse(published(data), "o learner ficou com a base");
    }

    @Test
    @DisplayName("um learner com um objeto frio faltando não restaura o backup")
    void learnerWithMissingObjectRefuses() {
        Path backups = ColdBaseFixture.backup(dir);
        ColdBaseFixture.loseColdObjects(dir);
        Path data = dir.resolve("learner");

        try (ColdTier cold = ColdBaseFixture.tier(dir, "restore")) {
            assertThrows(AxonError.class, () -> LearnerBootstrap.restore(backups, data, cold));
        }
        assertFalse(published(data), "o learner ficou com a base");
    }

    @Test
    @DisplayName("um learner com a camada fria restaura o backup")
    void learnerWithColdTier() {
        Path backups = ColdBaseFixture.backup(dir);
        Path data = dir.resolve("learner");

        try (ColdTier cold = ColdBaseFixture.tier(dir, "restore")) {
            assertEquals(ColdBaseFixture.INDEX,
                LearnerBootstrap.restore(backups, data, cold).orElseThrow().index());
        }
        assertTrue(published(data));
    }
}
