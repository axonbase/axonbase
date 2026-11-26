package com.axonbase.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Log Raft em segmentos")
class FileRaftLogSegmentsTest {

    /** Segmentos pequenos, para um teste gerar vários arquivos com poucas entradas. */
    private static final long SMALL = 512;

    @TempDir
    Path dir;

    private static FileRaftLog.Entry entry(long index, long term) {
        byte[] value = new byte[64];
        value[0] = (byte) index;
        return new FileRaftLog.Entry(index, term, new CommittedBatch("tx" + index,
            Map.of("k" + index, value), Set.of("d" + index)));
    }

    private static void fill(FileRaftLog log, long from, long to) {
        for (long i = from; i <= to; i++) {
            log.append(entry(i, 1));
        }
    }

    private List<Path> segments() throws IOException {
        try (Stream<Path> files = Files.list(dir.resolve("raft-log"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".seg")).sorted().toList();
        }
    }

    private static Object fileKey(Path file) throws IOException {
        return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(offset);
            int b = raf.read();
            raf.seek(offset);
            raf.write(b ^ 0x5A);
        }
    }

    /** Posição, dentro do arquivo, de um byte do valor gravado pela transação indicada. */
    private static long offsetOf(Path file, String text) throws IOException {
        String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        int at = raw.indexOf(text);
        assertTrue(at >= 0, "texto " + text + " não está em " + file);
        return at;
    }

    @Test
    @DisplayName("o log abre vários segmentos e reabre com todas as entradas")
    void rollsSegmentsAndReopens() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 40);
        }
        assertTrue(segments().size() > 3, "segmentos: " + segments());
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertEquals(40, log.lastIndex());
            assertEquals(40, log.entries().size());
            for (long i = 1; i <= 40; i++) {
                assertEquals("tx" + i, log.entry(i).batch().transactionId());
                assertEquals((byte) i, log.entry(i).batch().puts().get("k" + i)[0]);
            }
        }
    }

    @Test
    @DisplayName("truncar não reescreve os segmentos anteriores ao ponto de corte")
    void truncateKeepsEarlierSegmentFiles() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 40);
            List<Path> before = segments();
            Path first = before.getFirst();
            Object key = fileKey(first);
            byte[] bytes = Files.readAllBytes(first);

            log.truncateFrom(20);

            assertEquals(19, log.lastIndex());
            assertNull(log.entry(20));
            assertEquals(key, fileKey(first));
            assertArrayEquals(bytes, Files.readAllBytes(first));
            assertTrue(segments().size() < before.size(), "segmentos posteriores não foram apagados");
            log.append(entry(20, 2));
            assertEquals(2, log.termAt(20));
        }
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertEquals(20, log.lastIndex());
            assertEquals(2, log.termAt(20));
            assertEquals(1, log.termAt(19));
        }
    }

    @Test
    @DisplayName("compactar apaga segmentos inteiros sem reescrever os que ficam")
    void compactDeletesWholeSegments() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 40);
            List<Path> before = segments();
            Path last = before.getLast();
            Object key = fileKey(last);
            byte[] bytes = Files.readAllBytes(last);

            log.compact(30);

            assertTrue(segments().size() < before.size());
            assertEquals(key, fileKey(last));
            assertArrayEquals(bytes, Files.readAllBytes(last));
            assertNull(log.entry(30));
            assertNotNull(log.entry(31));
            assertEquals(31, log.firstIndex());
            assertEquals(30, log.baseIndex());
            assertEquals(1, log.termAt(30), "o termo do último índice compactado continua conhecido");
        }
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertEquals(40, log.lastIndex());
            assertEquals(30, log.baseIndex());
            assertEquals(1, log.termAt(30));
            assertNull(log.entry(12));
            assertEquals(10, log.entries().size());
        }
    }

    @Test
    @DisplayName("compactar tudo mantém o último índice depois de reabrir")
    void compactEverythingKeepsLastIndex() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 10);
            log.compact(10);
            assertEquals(10, log.lastIndex());
        }
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertEquals(10, log.lastIndex());
            log.append(entry(11, 3));
            assertEquals(11, log.lastIndex());
        }
    }

    @Test
    @DisplayName("reset troca o log por um ponto de partida e aceita a entrada seguinte")
    void resetStartsFromSnapshotPoint() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 10);
            log.reset(500, 7);
            assertEquals(500, log.lastIndex());
            assertEquals(7, log.termAt(500));
            assertTrue(log.entries().isEmpty());
            log.append(entry(501, 8));
        }
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertEquals(501, log.lastIndex());
            assertEquals(500, log.baseIndex());
            assertEquals(7, log.termAt(500));
        }
    }

    @Test
    @DisplayName("um log vazio aceita começar depois do índice 1; um log com entradas recusa buraco")
    void refusesGapInTheMiddle() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            log.append(entry(4, 3));
            assertEquals(4, log.lastIndex());
            assertEquals(3, log.baseIndex());
            assertThrows(IllegalStateException.class, () -> log.append(entry(9, 3)));
        }
    }

    @Test
    @DisplayName("zeros no fim do último segmento são descartados como cauda cortada")
    void zeroTailIsDropped() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20)) {
            fill(log, 1, 3);
        }
        Files.write(segments().getLast(), new byte[200], StandardOpenOption.APPEND);
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20)) {
            assertEquals(3, log.lastIndex());
            log.append(entry(4, 1));
        }
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20)) {
            assertEquals(4, log.lastIndex());
        }
    }

    @Test
    @DisplayName("checksum errado na última entrada descarta só ela")
    void badChecksumInTailIsDropped() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20)) {
            fill(log, 1, 3);
        }
        Path file = segments().getLast();
        flipByte(file, offsetOf(file, "tx3"));
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20, 2)) {
            assertEquals(2, log.lastIndex());
        }
    }

    @Test
    @DisplayName("corrupção em entrada já confirmada impede a abertura e não altera o arquivo")
    void corruptionOfCommittedEntryRefusesToOpen() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20)) {
            fill(log, 1, 5);
        }
        Path file = segments().getLast();
        flipByte(file, offsetOf(file, "tx2"));
        byte[] corrupted = Files.readAllBytes(file);

        CorruptRaftLogException failure = assertThrows(CorruptRaftLogException.class,
            () -> new FileRaftLog(dir, 1 << 20, 4));

        assertTrue(failure.getMessage().contains(file.getFileName().toString()), failure.getMessage());
        assertArrayEquals(corrupted, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("buraco na cauda não confirmada é cortado e os bytes ficam guardados para análise")
    void holeInUncommittedTailIsCutAndKept() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20)) {
            fill(log, 1, 5);
        }
        Path file = segments().getLast();
        flipByte(file, offsetOf(file, "tx4"));

        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20, 2)) {
            assertEquals(3, log.lastIndex());
        }
        try (Stream<Path> files = Files.list(dir.resolve("raft-log"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".corrupt")),
                "a cauda descartada deveria ter sido guardada");
        }
    }

    @Test
    @DisplayName("corrupção num segmento que não é o último impede a abertura")
    void corruptionInOlderSegmentRefusesToOpen() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 40);
        }
        Path first = segments().getFirst();
        flipByte(first, offsetOf(first, "tx1"));
        assertThrows(CorruptRaftLogException.class, () -> new FileRaftLog(dir, SMALL));
    }

    @Test
    @DisplayName("segmento faltando no meio impede a abertura")
    void missingSegmentRefusesToOpen() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 40);
        }
        Files.delete(segments().get(1));
        assertThrows(CorruptRaftLogException.class, () -> new FileRaftLog(dir, SMALL));
    }

    @Test
    @DisplayName("segmento recém-criado com cabeçalho incompleto é ignorado")
    void halfCreatedSegmentIsRemoved() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20)) {
            fill(log, 1, 3);
        }
        Files.write(dir.resolve("raft-log").resolve("00000000000000000004.seg"), new byte[] {1, 2, 3});
        try (FileRaftLog log = new FileRaftLog(dir, 1 << 20)) {
            assertEquals(3, log.lastIndex());
            log.append(entry(4, 1));
            assertEquals(4, log.lastIndex());
        }
    }

    @Test
    @DisplayName("os bytes do lote são guardados sem serem reinterpretados")
    void keepsPayloadBytesOpaque() throws Exception {
        byte[] encoded = RaftPayload.encode(entry(1, 1).batch());
        byte[] extended = java.util.Arrays.copyOf(encoded, encoded.length + 12);
        extended[extended.length - 1] = 42;
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            log.appendEncoded(1, 1, extended);
        }
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertArrayEquals(extended, log.encoded(1));
            assertEquals("tx1", log.entry(1).batch().transactionId());
        }
    }

    @Test
    @DisplayName("log antigo em arquivo único é migrado, inclusive com a cauda cortada")
    void migratesLegacyFile() throws Exception {
        Path legacy = dir.resolve("raft.log");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(legacy))) {
            for (long i = 3; i <= 6; i++) {
                writeLegacy(out, i, 2);
            }
            out.writeLong(7);
            out.writeLong(2);
            out.writeShort(40);
        }
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertEquals(6, log.lastIndex());
            assertEquals(2, log.baseIndex());
            assertEquals("legacy4", log.entry(4).batch().transactionId());
            assertArrayEquals(new byte[] {4}, log.entry(4).batch().puts().get("k"));
            assertTrue(log.entry(4).batch().deletes().contains("gone"));
        }
        assertFalse(Files.exists(legacy), "o arquivo antigo não pode ser lido de novo");
        assertTrue(Files.exists(dir.resolve("raft.log.migrated")));
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertEquals(6, log.lastIndex());
        }
    }

    @Test
    @DisplayName("um raft.log novo ao lado dos segmentos (volta de versão antiga) impede a abertura")
    void logWrittenByAnOldVersionAfterMigrationRefusesToOpen() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 3);
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("raft.log")))) {
            writeLegacy(out, 4, 2);
        }
        CorruptRaftLogException failure = assertThrows(CorruptRaftLogException.class,
            () -> new FileRaftLog(dir, SMALL));
        assertTrue(failure.getMessage().contains("raft.log"), failure.getMessage());
        assertTrue(Files.exists(dir.resolve("raft.log")), "o arquivo não pode ser descartado");
    }

    @Test
    @DisplayName("uma migração interrompida antes de renomear o arquivo antigo termina na próxima abertura")
    void interruptedMigrationIsFinished() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            fill(log, 1, 3);
        }
        Files.createFile(dir.resolve("raft-log").resolve("migrating"));
        Files.createFile(dir.resolve("raft.log"));
        try (FileRaftLog log = new FileRaftLog(dir, SMALL)) {
            assertEquals(3, log.lastIndex());
        }
        assertFalse(Files.exists(dir.resolve("raft.log")));
        assertFalse(Files.exists(dir.resolve("raft-log").resolve("migrating")));
    }

    /** Formato de registro das versões 0.9.x e 0.10.x. */
    private static void writeLegacy(DataOutputStream out, long index, long term) throws IOException {
        out.writeLong(index);
        out.writeLong(term);
        byte[] tx = ("legacy" + index).getBytes(StandardCharsets.UTF_8);
        out.writeShort(tx.length);
        out.write(tx);
        out.writeInt(1);
        out.writeShort(4);
        out.write("gone".getBytes(StandardCharsets.UTF_8));
        out.writeInt(1);
        out.writeShort(1);
        out.write('k');
        out.writeInt(1);
        out.write((int) index);
    }

    @Test
    @DisplayName("leituras concorrentes com a escrita devolvem sempre a entrada certa")
    void concurrentReadersSeeConsistentEntries() throws Exception {
        try (FileRaftLog log = new FileRaftLog(dir, SMALL, 0, 0)) {
            List<Throwable> failures = new ArrayList<>();
            Thread reader = new Thread(() -> {
                try {
                    while (log.lastIndex() < 300) {
                        long last = log.lastIndex();
                        for (long i = Math.max(1, last - 5); i <= last; i++) {
                            assertEquals("tx" + i, log.entry(i).batch().transactionId());
                        }
                    }
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            });
            reader.start();
            fill(log, 1, 300);
            reader.join(30_000);
            assertTrue(failures.isEmpty(), failures.toString());
        }
    }
}
