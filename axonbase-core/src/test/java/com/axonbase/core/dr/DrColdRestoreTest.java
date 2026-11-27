package com.axonbase.core.dr;

import com.axonbase.core.cluster.ColdBaseFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DR: restauração de uma base com camada fria")
class DrColdRestoreTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    /** Arquiva o backup de uma base com partição fria e devolve o diretório do arquivamento. */
    private Path archive() {
        Path archive = dir.resolve("arquivo");
        DrArchiver archiver = new DrArchiver(new DirectoryArchiveStore(archive),
            new ArchiveLayout("axon-dr", "e1", "g"), "n1");
        assertEquals(1, archiver.pushBackups(ColdBaseFixture.backup(dir)));
        return archive;
    }

    private int restore(Map<String, String> env, Path archive, String name) {
        bytes.reset();
        return DrTool.run(new String[] {"restore", "--store", archive.toString(), "--epoch", "e1",
            "--target", dir.resolve(name).toString(), "--work", dir.resolve(name + "-work").toString()},
            env, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private boolean published(String name) {
        return Files.exists(dir.resolve(name).resolve("CURRENT"));
    }

    @Test
    @DisplayName("sem AXON_COLD_DIR nem AXON_COLD_S3 a restauração falha e não publica a base")
    void withoutColdTierFails() {
        Path archive = archive();

        assertEquals(1, restore(Map.of(), archive, "sem-camada"), output());

        assertTrue(output().contains("DR FAILED") && output().contains("AXON_COLD_DIR"), output());
        assertFalse(published("sem-camada"));
    }

    @Test
    @DisplayName("com um objeto frio faltando a restauração falha e não publica a base")
    void missingObjectFails() {
        Path archive = archive();
        ColdBaseFixture.loseColdObjects(dir);

        assertEquals(1, restore(Map.of("AXON_COLD_DIR", dir.resolve("cold").toString()), archive, "sem-objeto"),
            output());

        assertTrue(output().contains("parts/n/d/events/"), output());
        assertFalse(published("sem-objeto"));
    }

    @Test
    @DisplayName("com a camada fria no ambiente e os objetos presentes a base é restaurada")
    void withColdTier() {
        Path archive = archive();

        assertEquals(0, restore(Map.of("AXON_COLD_DIR", dir.resolve("cold").toString(),
            "AXON_COLD_CACHE_DIR", dir.resolve("cache-dr").toString()), archive, "restaurada"), output());

        assertTrue(published("restaurada"));
    }

    @Test
    @DisplayName("as duas variáveis da camada fria juntas são uso errado")
    void bothColdVariablesAreUsageError() {
        assertEquals(2, restore(Map.of("AXON_COLD_DIR", "a", "AXON_COLD_S3", "s3://b/c"), dir.resolve("a"),
            "nada"), output());
        assertTrue(output().startsWith("DR USAGE"), output());
    }
}
