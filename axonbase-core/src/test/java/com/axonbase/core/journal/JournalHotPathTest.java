package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.CommitWatermark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Caminho do commit e escrita do journal")
class JournalHotPathTest {

    @TempDir
    Path tmp;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static JournalOptions options() {
        return JournalOptions.defaults().withMaxSegmentAge(Duration.ofHours(1)).withIdleHeartbeat(Duration.ZERO);
    }

    private static CommitWatermark watermark() {
        return new CommitWatermark(new HlcTimestamp(1, 0), 0, 9);
    }

    private static List<JournalRecord> readAll(Path dir, PitrJournal journal) throws IOException {
        List<JournalRecord> out = new ArrayList<>();
        for (SegmentInfo segment : journal.segments()) {
            JournalSegments.read(dir.resolve(segment.name()), record -> {
                out.add(record);
                return true;
            });
        }
        return out;
    }

    /** O registro de lote montado byte a byte, como a versão 1 do formato o define. */
    private static byte[] expectedRecord(long index, HlcTimestamp stamp, Map<String, byte[]> puts,
                                         Set<String> deletes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeByte(1);
        out.writeLong(index);
        out.writeLong(stamp.physical());
        out.writeInt(stamp.logical());
        out.writeInt(puts.size());
        for (var put : puts.entrySet()) {
            out.writeInt(bytes(put.getKey()).length);
            out.write(bytes(put.getKey()));
            out.writeInt(put.getValue().length);
            out.write(put.getValue());
        }
        out.writeInt(deletes.size());
        for (String key : deletes) {
            out.writeInt(bytes(key).length);
            out.write(bytes(key));
        }
        byte[] typeAndBody = body.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(typeAndBody);
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(whole);
        framed.writeInt(typeAndBody.length);
        framed.write(typeAndBody);
        framed.writeInt((int) crc.getValue());
        return whole.toByteArray();
    }

    @Test
    @DisplayName("o arquivo do segmento tem exatamente o formato da versão 1, com chaves fora do ASCII")
    void theSegmentKeepsFormatVersionOne() throws Exception {
        Map<String, byte[]> puts = new LinkedHashMap<>();
        puts.put("tabela:ação-ü-€", bytes("valor"));
        puts.put("vazio", new byte[0]);
        Set<String> deletes = new LinkedHashSet<>(List.of("apagada", "outra"));
        HlcTimestamp stamp = new HlcTimestamp(1_790_000_000_123L, 4);
        Path dir = tmp.resolve("j");
        SegmentInfo segment;
        try (PitrJournal journal = PitrJournal.open(dir, options(), JournalHotPathTest::watermark)) {
            journal.committed(7L, stamp, puts, deletes);
            segment = journal.seal();
        }
        byte[] file = Files.readAllBytes(dir.resolve(segment.name()));
        byte[] expected = expectedRecord(7L, stamp, puts, deletes);

        assertArrayEquals(expected,
            Arrays.copyOfRange(file, SegmentFormat.HEADER_BYTES, SegmentFormat.HEADER_BYTES + expected.length));
        assertEquals(segment.sizeBytes(), file.length);
    }

    @Test
    @DisplayName("o journal copia o que recebe antes de voltar: o chamador pode reaproveitar mapas e vetores")
    void theCallerMayReuseMapsAndArraysAfterwards() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean(true);
        Path dir = tmp.resolve("j");
        // a thread de escrita fica parada ao abrir o segmento, antes de ler o conteúdo do lote
        try (PitrJournal journal = PitrJournal.open(dir, options(), () -> {
            if (block.get()) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return watermark();
        })) {
            Map<String, byte[]> puts = new HashMap<>();
            byte[] value = bytes("original");
            puts.put("k", value);
            Set<String> deletes = new java.util.HashSet<>(Set.of("d"));

            journal.committed(1L, new HlcTimestamp(5, 0), puts, deletes);

            Arrays.fill(value, (byte) 'x');
            puts.put("intrusa", bytes("1"));
            deletes.add("intrusa-d");
            block.set(false);
            release.countDown();
            journal.seal();

            JournalRecord record = readAll(dir, journal).get(0);
            assertEquals(Set.of("k"), record.batch().puts().keySet());
            assertArrayEquals(bytes("original"), record.batch().puts().get("k"));
            assertEquals(Set.of("d"), record.batch().deletes());
        }
    }

    @Test
    @DisplayName("lotes que enchem o buffer de escrita várias vezes, e um maior que ele, chegam inteiros")
    void batchesLargerThanTheWriteBufferSurvive() throws Exception {
        Random random = new Random(5);
        Path dir = tmp.resolve("j");
        List<Map<String, byte[]>> sent = new ArrayList<>();
        try (PitrJournal journal = PitrJournal.open(dir, options().withMaxSegmentBytes(5L << 20),
                JournalHotPathTest::watermark)) {
            int[] sizes = {1, 700_000, 700_000, 3_000_000, 10, 1_048_576, 1_048_540, 2, 900_000, 5};
            for (int i = 0; i < sizes.length; i++) {
                byte[] value = new byte[sizes[i]];
                random.nextBytes(value);
                sent.add(Map.of("k" + i, value));
                journal.committed(i + 1L, new HlcTimestamp(10 + i, 0), sent.get(i), Set.of());
            }
            journal.seal();

            List<JournalRecord> got = readAll(dir, journal);
            assertEquals(sizes.length, got.size());
            for (int i = 0; i < sizes.length; i++) {
                assertEquals(i + 1L, got.get(i).index());
                assertArrayEquals(sent.get(i).get("k" + i), got.get(i).batch().puts().get("k" + i), "lote " + i);
            }
            assertTrue(journal.segments().size() >= 2, "o limite de tamanho precisa ter virado o segmento");
            assertEquals(0, journal.droppedBatches());
        }
    }

    @Test
    @DisplayName("falha ao pedir a marca na troca de história descarta o segmento aberto, que não recebe mais nada")
    void aFailureAtAHistoryBreakDropsTheOpenSegment() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong timeline = new java.util.concurrent.atomic.AtomicLong(1);
        Path dir = tmp.resolve("j");
        try (PitrJournal journal = PitrJournal.open(dir, options(), () -> {
            if (fail.get()) {
                throw new IllegalStateException("disco cheio");
            }
            return new CommitWatermark(new HlcTimestamp(1, 0), 0, timeline.get());
        })) {
            journal.committed(1L, new HlcTimestamp(5, 0), Map.of("antes", bytes("1")), Set.of());
            long deadline = System.currentTimeMillis() + 30_000;
            while (journal.openSegmentBatches() == 0 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            fail.set(true);
            journal.historyBreaking();
            fail.set(false);
            timeline.set(2);
            journal.committed(3L, new HlcTimestamp(6, 0), Map.of("depois", bytes("2")), Set.of());
            SegmentInfo after = journal.seal();

            assertEquals(2L, after.timeline());
            assertEquals(1L, after.batches());
            assertTrue(journal.writeErrors() >= 1);
        }
    }

    @Test
    @DisplayName("8 threads: todo lote aceito é arquivado, e o que passa do limite é contado como descartado")
    void everyAcceptedBatchIsArchivedAndTheRestCounted() throws Exception {
        Path dir = tmp.resolve("j");
        int threads = 8;
        int each = 3_000;
        try (PitrJournal journal = PitrJournal.open(dir, options().withQueueLimits(64, 1L << 20),
                JournalHotPathTest::watermark)) {
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int n = t;
                workers.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < each; i++) {
                        journal.committed(1L + (long) i * threads + n, new HlcTimestamp(100, i),
                            Map.of("k" + n + "-" + i, bytes("v")), Set.of());
                    }
                }));
            }
            for (Thread w : workers) {
                w.join(60_000);
            }
            journal.seal();

            long archived = journal.segments().stream().mapToLong(SegmentInfo::batches).sum();
            assertEquals((long) threads * each, archived + journal.droppedBatches());
            assertEquals(0, journal.pendingBatches());
        }
    }
}
