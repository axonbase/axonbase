package com.axonbase.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Recuperação do log Raft em disco")
class FileRaftLogRecoveryTest {

    @TempDir
    Path dir;

    private static FileRaftLog.Entry entry(long index) {
        return new FileRaftLog.Entry(index, 1, new CommittedBatch("tx" + index,
            Map.of("k" + index, new byte[] {(byte) index}), Set.of("d" + index)));
    }

    @Test
    @DisplayName("reabrir devolve todas as entradas, não só a última")
    void reopenKeepsEveryEntry() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir)) {
            for (long i = 1; i <= 5; i++) {
                log.append(entry(i));
            }
        }
        try (FileRaftLog reloaded = new FileRaftLog(dir)) {
            assertEquals(5, reloaded.lastIndex());
            assertEquals(5, reloaded.entries().size());
            for (long i = 1; i <= 5; i++) {
                assertNotNull(reloaded.entry(i), "entrada " + i);
                assertEquals("tx" + i, reloaded.entry(i).batch().transactionId());
            }
        }
    }

    @Test
    @DisplayName("cauda cortada no meio da última entrada é descartada e as anteriores ficam")
    void tornTailIsDropped() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir)) {
            for (long i = 1; i <= 4; i++) {
                log.append(entry(i));
            }
        }
        Path file = dir.resolve("raft.log");
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(ch.size() - 3);
        }
        try (FileRaftLog reloaded = new FileRaftLog(dir)) {
            assertEquals(3, reloaded.lastIndex());
            assertEquals(3, reloaded.entries().size());
            reloaded.append(entry(4));
            assertEquals(4, reloaded.lastIndex());
        }
        try (FileRaftLog again = new FileRaftLog(dir)) {
            assertEquals(4, again.entries().size());
        }
    }
}
