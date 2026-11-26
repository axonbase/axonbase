package com.axonbase.core.dr;

import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.journal.PitrJournal;
import com.axonbase.core.storage.CommitWatermark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DR: linha de comando e layout")
class DrToolTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private int run(Map<String, String> env, String... args) {
        bytes.reset();
        return DrTool.run(args, env, new PrintStream(bytes, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("uso errado sai com 2 e explica o que falta")
    void usage() {
        assertEquals(2, run(Map.of()));
        assertEquals(2, run(Map.of(), "apagar"));
        assertEquals(2, run(Map.of(), "push", "--store", dir.toString(), "--epoch", "e1", "--node", "n1"));
        assertTrue(output().contains("--backups"), output());
        assertEquals(2, run(Map.of(), "push", "--store", "s3", "--epoch", "e1", "--node", "n1",
            "--journal", dir.toString()));
        assertTrue(output().contains("AXON_S3_ENDPOINT"), output());
        assertEquals(2, run(Map.of(), "restore", "--store", dir.toString(), "--epoch", "e1", "--at", "ontem",
            "--target", "t", "--work", "w"));
        assertTrue(output().startsWith("DR USAGE"), output());
    }

    @Test
    @DisplayName("push de diretórios vazios não envia nada, e restaurar sem backup falha com 1")
    void emptyPushAndRestore() throws Exception {
        Path empty = Files.createDirectories(dir.resolve("vazio"));

        assertEquals(0, run(Map.of("AXON_S3_ENCRYPT_KEY", "k"), "push", "--store", dir.resolve("a").toString(),
            "--epoch", "e1", "--node", "n1", "--backups", empty.toString(), "--journal", empty.toString()));
        assertTrue(output().contains("DR push node=n1 backups=0 segments=0"), output());
        assertEquals(1, run(Map.of(), "restore", "--store", dir.resolve("a").toString(), "--epoch", "e1",
            "--target", dir.resolve("t").toString(), "--work", dir.resolve("w").toString()));
        assertTrue(output().startsWith("DR FAILED"), output());
    }

    @Test
    @DisplayName("um backup com defeito não segura os segmentos do journal")
    void brokenBackupDoesNotStopTheJournal() throws Exception {
        Path backups = dir.resolve("backups");
        Files.createDirectories(backups.resolve("meta"));
        Files.writeString(backups.resolve("meta").resolve("1"), "metadata zz\n");
        Path journal = dir.resolve("journal");
        try (PitrJournal open = PitrJournal.open(journal,
                JournalOptions.defaults().withIdleHeartbeat(Duration.ZERO), () -> CommitWatermark.NONE)) {
            open.seal();
        }

        int code = run(Map.of(), "push", "--store", dir.resolve("a").toString(), "--epoch", "e1", "--node", "n1",
            "--backups", backups.toString(), "--journal", journal.toString());

        assertEquals(1, code, output());
        assertTrue(output().contains("backups=0 segments=1"), output());
        assertTrue(output().contains("error="), output());
    }

    @Test
    @DisplayName("o layout separa épocas, grupos e nós e recusa nomes que escapam")
    void layout() {
        ArchiveLayout layout = new ArchiveLayout("axon-dr", "e1", "g");

        assertEquals("axon-dr/e1/groups/g/journal/n1/", layout.journal("n1"));
        assertEquals("axon-dr/e1/groups/g/backups/n1/files/meta/1", layout.backupFile("n1", "meta/1"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ArchiveLayout("axon-dr", "..", "g"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> layout.journal("a/b"));
        BackupDescriptor descriptor = BackupDescriptor.parse(layout.descriptors("n2") + "3-1700-4").orElseThrow();
        assertEquals("n2", descriptor.node());
        assertEquals(3, descriptor.id());
        assertEquals("3-1700-4", descriptor.fileName());
        assertTrue(BackupDescriptor.parse(layout.backupFile("n2", "meta/3")).isEmpty());
        assertTrue(BackupDescriptor.parse(layout.descriptors("n2") + "lixo").isEmpty());
    }
}
