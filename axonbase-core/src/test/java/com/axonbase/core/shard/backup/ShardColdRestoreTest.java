package com.axonbase.core.shard.backup;

import com.axonbase.common.AxonError;
import com.axonbase.core.control.ControlStore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.partition.ColdTier;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: restauração fora de linha com camada fria")
@Timeout(300)
class ShardColdRestoreTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("sem camada fria configurada, a base que a referencia não é restaurada, e nada é publicado")
    void withoutColdTierRefuses() throws IOException {
        try (ColdFleet fleet = new ColdFleet(dir)) {
            fleet.firstDayCold();
            ClusterManifest manifest = fleet.backups.create();
            Path destination = dir.resolve("sem-camada");

            AxonError refused = assertThrows(AxonError.class,
                () -> ClusterRestore.restore(manifest, null, ColdFleet.sources(manifest), destination));

            assertTrue(refused.getMessage().contains("AXON_COLD_DIR"), refused.getMessage());
            assertTrue(empty(destination), "nenhum shard pode ficar publicado");
        }
    }

    @Test
    @DisplayName("com um objeto frio faltando, a restauração inteira é recusada")
    void missingObjectRefuses() throws IOException {
        try (ColdFleet fleet = new ColdFleet(dir)) {
            fleet.firstDayCold();
            ClusterManifest manifest = fleet.backups.create();
            for (Path object : fleet.coldObjects()) {
                Files.delete(object);
            }
            Path destination = dir.resolve("sem-objeto");

            try (ColdTier cold = ColdFleet.tier(dir, "restore")) {
                AxonError refused = assertThrows(AxonError.class, () -> ClusterRestore.restore(manifest, null,
                    ColdFleet.sources(manifest), destination, cold));

                assertTrue(refused.getMessage().contains("parts/n/d/events/"), refused.getMessage());
            }
            assertTrue(empty(destination), "nenhum shard pode ficar publicado");
        }
    }

    @Test
    @DisplayName("a restauração não abre um motor: não grava na base, não constrói nem remove o índice interrompido")
    void restoresWithoutBuildingIndexes() throws Exception {
        try (ColdFleet fleet = new ColdFleet(dir)) {
            fleet.firstDayCold();
            fleet.interruptUniqueIndex();
            byte[] control = fleet.home().backend().get(ControlStore.KEY).orElseThrow();
            // marcas que um motor grava ao subir sobre uma base com dados
            var engineMarks = fleet.home().backend().keysWithPrefix("EKM|");
            ClusterManifest manifest = fleet.backups.create();
            Path destination = dir.resolve("restaurado");
            AtomicInteger chunks = new AtomicInteger();

            IndexBuilder.chunkHook(chunks::incrementAndGet);
            try (ColdTier cold = ColdFleet.tier(dir, "restore")) {
                ClusterRestore.restore(manifest, null, ColdFleet.sources(manifest), destination, cold);
                assertTrue(IndexBuilder.awaitAll(10_000));
            } finally {
                IndexBuilder.chunkHook(null);
            }

            assertEquals(0, chunks.get(), "a restauração construiu o índice");
            try (RocksDbBackend home = new RocksDbBackend(destination.resolve("s0").toString())) {
                assertEquals(engineMarks, home.keysWithPrefix("EKM|"), "um motor gravou na base restaurada");
                assertTrue(home.get(ColdFleet.BUILD_MARKER).isPresent(), "o índice interrompido mudou de estado");
                assertArrayEquals(control, home.get(ControlStore.KEY).orElseThrow(), "o catálogo mudou");
            }
        }
    }

    private static boolean empty(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return true;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.findAny().isEmpty();
        }
    }
}
