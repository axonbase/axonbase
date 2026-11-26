package com.axonbase.core.journal;

import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.CommitWatermark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Journal de PITR: segmentos, fila limitada, recuperação e importação")
class PitrJournalTest {

    @TempDir
    Path tmp;

    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);
    private final HybridClock clock = new HybridClock(wall::get, 500L, e -> { });
    private final AtomicLong index = new AtomicLong();

    /** Marca d'água de um produtor de uma thread só: tudo o que foi emitido já foi entregue. */
    private final Supplier<CommitWatermark> watermark = () -> new CommitWatermark(clock.now(), index.get());

    private static JournalOptions options() {
        return JournalOptions.defaults().withMaxSegmentAge(Duration.ofHours(1)).withIdleHeartbeat(Duration.ZERO);
    }

    private CommittedBatch next(String key, byte[] value) {
        return new CommittedBatch("", Map.of(key, value), Set.of()).withStamp(clock.now());
    }

    private CommittedBatch offer(PitrJournal journal, String key, String value) {
        CommittedBatch batch = next(key, value.getBytes(StandardCharsets.UTF_8));
        journal.offer(index.incrementAndGet(), batch);
        return batch;
    }

    private static List<JournalRecord> read(Path dir, SegmentInfo segment) throws IOException {
        List<JournalRecord> out = new ArrayList<>();
        JournalSegments.read(dir.resolve(segment.name()), record -> {
            out.add(record);
            return true;
        });
        return out;
    }

    private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("tempo esgotado esperando: " + what);
            }
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("seal fecha o segmento com carimbos, índices e a marca d'água, e os lotes voltam iguais")
    void sealedSegmentCarriesItsRangeAndRecords() throws Exception {
        Path dir = tmp.resolve("j");
        byte[] binary = new byte[] {0, 1, 2, (byte) 0xFF};
        String longKey = "k".repeat(70_000);
        try (PitrJournal journal = PitrJournal.open(dir, options(), watermark)) {
            CommittedBatch first = next("a\u0000b|ç", binary);
            journal.offer(index.incrementAndGet(), first);
            CommittedBatch second = new CommittedBatch("", Map.of(longKey, new byte[0]), Set.of("x", "y"))
                .withStamp(clock.now());
            journal.offer(index.incrementAndGet(), second);

            SegmentInfo info = journal.seal();

            assertEquals(2, info.batches());
            assertEquals(1L, info.firstIndex());
            assertEquals(2L, info.lastIndex());
            assertEquals(first.stamp(), info.firstStamp());
            assertEquals(second.stamp(), info.lastStamp());
            assertEquals(2L, info.complete().index());
            assertTrue(info.complete().stamp().compareTo(second.stamp()) > 0);
            assertTrue(info.holes().isEmpty());
            assertEquals(Files.size(dir.resolve(info.name())), info.sizeBytes());
            assertEquals(List.of(info), journal.segments());

            List<JournalRecord> records = read(dir, info);
            assertEquals(2, records.size());
            assertEquals(1L, records.get(0).index());
            assertEquals(first.stamp(), records.get(0).batch().stamp());
            assertArrayEquals(binary, records.get(0).batch().puts().get("a\u0000b|ç"));
            assertEquals(Set.of("x", "y"), records.get(1).batch().deletes());
            assertTrue(records.get(1).batch().puts().containsKey(longKey));
        }
    }

    @Test
    @DisplayName("índices fora de ordem e faltando aparecem como faixa e buracos do segmento")
    void holesAreRecorded() throws Exception {
        try (PitrJournal journal = PitrJournal.open(tmp.resolve("j"), options(), watermark)) {
            for (long i : new long[] {3, 1, 6, 7}) {
                journal.offer(i, next("k" + i, new byte[] {1}));
            }
            index.set(7);
            SegmentInfo info = journal.seal();

            assertEquals(1L, info.firstIndex());
            assertEquals(7L, info.lastIndex());
            assertEquals(List.of(new IndexRange(2, 2), new IndexRange(4, 5)), info.holes());
        }
    }

    @Test
    @DisplayName("o segmento fecha sozinho ao atingir a idade máxima")
    void segmentsCloseByAge() throws Exception {
        Path dir = tmp.resolve("j");
        try (PitrJournal journal = PitrJournal.open(dir, options().withMaxSegmentAge(Duration.ofMillis(200)),
                watermark)) {
            offer(journal, "a", "1");
            await("segmento por idade", () -> journal.segments().size() == 1);
            assertEquals(1, journal.segments().get(0).batches());
        }
    }

    @Test
    @DisplayName("o segmento fecha ao passar do tamanho máximo")
    void segmentsCloseBySize() throws Exception {
        try (PitrJournal journal = PitrJournal.open(tmp.resolve("j"), options().withMaxSegmentBytes(2_000),
                watermark)) {
            for (int i = 0; i < 40; i++) {
                offer(journal, "a" + i, "v".repeat(300));
            }
            journal.seal();
            long total = journal.segments().stream().mapToLong(SegmentInfo::batches).sum();
            assertEquals(40L, total);
            assertTrue(journal.segments().size() > 3, "segmentos: " + journal.segments().size());
        }
    }

    @Test
    @DisplayName("parado, o journal grava segmentos vazios que avançam a marca d'água")
    void idleHeartbeatAdvancesCoverage() throws Exception {
        try (PitrJournal journal = PitrJournal.open(tmp.resolve("j"),
                options().withIdleHeartbeat(Duration.ofMillis(150)), watermark)) {
            await("dois heartbeats", () -> journal.segments().size() >= 2);
            List<SegmentInfo> segments = journal.segments();
            assertEquals(0, segments.get(0).batches());
            assertTrue(segments.get(1).complete().stamp().compareTo(segments.get(0).complete().stamp()) > 0);
        }
    }

    @Test
    @DisplayName("fila cheia descarta sem bloquear o commit, conta os descartes e o índice fica com buraco")
    void offerNeverBlocksWhenTheQueueIsFull() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean(true);
        Supplier<CommitWatermark> slow = () -> {
            if (block.get()) {
                inside.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return watermark.get();
        };
        try (PitrJournal journal = PitrJournal.open(tmp.resolve("j"), options().withQueueLimits(4, 1 << 20), slow)) {
            Thread sealer = Thread.ofPlatform().start(journal::seal);
            assertTrue(inside.await(30, TimeUnit.SECONDS));

            Thread producer = Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 100; i++) {
                    offer(journal, "k" + i, "v");
                }
            });
            producer.join(20_000);
            assertTrue(!producer.isAlive(), "offer bloqueou");
            assertEquals(96L, journal.droppedBatches());

            block.set(false);
            release.countDown();
            sealer.join(30_000);
            journal.seal();
            assertEquals(4L, journal.segments().stream().mapToLong(SegmentInfo::batches).sum());
        }
    }

    @Test
    @DisplayName("um lote maior que o limite de bytes ainda entra quando a fila está vazia")
    void anOversizedBatchStillEntersAnEmptyQueue() throws Exception {
        try (PitrJournal journal = PitrJournal.open(tmp.resolve("j"), options().withQueueLimits(100, 10),
                watermark)) {
            offer(journal, "grande", "v".repeat(1_000));
            assertEquals(1, journal.seal().batches());
            assertEquals(0L, journal.droppedBatches());
        }
    }

    @Test
    @DisplayName("depois de uma queda, o segmento aberto é recuperado até o último lote inteiro")
    void openSegmentIsRecoveredAfterACrash() throws Exception {
        Path dir = tmp.resolve("j");
        Path crashed = tmp.resolve("crashed");
        Files.createDirectories(crashed);
        try (PitrJournal journal = PitrJournal.open(dir, options(), watermark)) {
            offer(journal, "selado", "0");
            journal.seal();
            for (int i = 1; i <= 3; i++) {
                offer(journal, "k" + i, "v" + i);
            }
            await("os três lotes no arquivo aberto", () -> journal.pendingBatches() == 0
                && journal.openSegmentBatches() == 3);
            // copia o diretório com o processo "vivo": é o que um kill -9 deixaria
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    byte[] raw = Files.readAllBytes(p);
                    if (p.getFileName().toString().endsWith(".open")) {
                        raw = Arrays.copyOf(raw, raw.length - 3); // o último registro fica cortado
                    }
                    Files.write(crashed.resolve(p.getFileName()), raw);
                }
            }
        }
        try (PitrJournal recovered = PitrJournal.open(crashed, options(), watermark)) {
            List<SegmentInfo> segments = recovered.segments();
            assertEquals(2, segments.size());
            SegmentInfo last = segments.get(1);
            assertEquals(2, last.batches());
            assertEquals(2L, last.firstIndex());
            assertEquals(3L, last.lastIndex());
            assertTrue(last.recovered());
            assertEquals(segments.get(0).complete(), last.complete(), "o recuperado não promete cobertura nova");
            assertEquals(3L, recovered.archived().index());
        }
        try (var files = Files.list(crashed)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".open")));
        }
    }

    @Test
    @DisplayName("fechar o journal sela o que está pendente; reabrir informa até onde foi arquivado")
    void closeSealsAndReopenReportsTheArchivedPosition() throws Exception {
        Path dir = tmp.resolve("j");
        CommittedBatch last;
        try (PitrJournal journal = PitrJournal.open(dir, options(), watermark)) {
            offer(journal, "a", "1");
            last = offer(journal, "b", "2");
        }
        try (PitrJournal journal = PitrJournal.open(dir, options(), watermark)) {
            assertEquals(1, journal.segments().size());
            assertEquals(2L, journal.archived().index());
            assertTrue(journal.archived().stamp().compareTo(last.stamp()) >= 0);
        }
    }

    @Test
    @DisplayName("a retenção apaga segmentos mais velhos que o prazo e mantém os novos")
    void retentionRemovesOldSegments() throws Exception {
        try (PitrJournal journal = PitrJournal.open(tmp.resolve("j"), options().withRetention(Duration.ofDays(7)),
                watermark)) {
            offer(journal, "velho", "1");
            SegmentInfo old = journal.seal();
            wall.addAndGet(Duration.ofDays(8).toMillis());
            offer(journal, "novo", "2");
            SegmentInfo fresh = journal.seal();

            journal.applyRetention(wall.get());

            assertEquals(List.of(fresh), journal.segments());
            assertTrue(!Files.exists(tmp.resolve("j").resolve(old.name())));
        }
    }

    @Test
    @DisplayName("apagar um segmento pelo nome o tira da lista e do disco")
    void deleteRemovesASegment() throws Exception {
        try (PitrJournal journal = PitrJournal.open(tmp.resolve("j"), options(), watermark)) {
            offer(journal, "a", "1");
            SegmentInfo info = journal.seal();
            assertTrue(journal.delete(info.name()));
            assertTrue(!journal.delete(info.name()));
            assertTrue(journal.segments().isEmpty());
        }
    }

    @Test
    @DisplayName("importar um segmento valida o conteúdo e grava de forma atômica na área da origem")
    void importValidatesTheSegment() throws Exception {
        Path dir = tmp.resolve("j");
        byte[] raw;
        String name;
        try (PitrJournal journal = PitrJournal.open(dir, options(), watermark)) {
            offer(journal, "a", "1");
            name = journal.seal().name();
            raw = Files.readAllBytes(dir.resolve(name));
        }
        Path target = tmp.resolve("destino");
        SegmentInfo imported = JournalSegments.write(target, "outro-motor", name,
            new ByteArrayInputStream(raw), raw.length);
        assertEquals(1, imported.batches());
        assertEquals(List.of(imported), JournalSegments.list(JournalSegments.importRoot(target, "outro-motor")));

        byte[] corrupted = raw.clone();
        corrupted[20] ^= 0x55;
        assertThrows(IllegalArgumentException.class, () -> JournalSegments.write(target, "ruim", name,
            new ByteArrayInputStream(corrupted), corrupted.length));
        assertThrows(IllegalArgumentException.class, () -> JournalSegments.write(target, "ruim", name,
            new ByteArrayInputStream(Arrays.copyOf(raw, raw.length - 1)), raw.length - 1));
        assertThrows(IOException.class, () -> JournalSegments.write(target, "ruim", name,
            new ByteArrayInputStream(raw), raw.length + 5));
        assertTrue(JournalSegments.list(JournalSegments.importRoot(target, "ruim")).isEmpty());
        try (var files = Files.list(JournalSegments.importRoot(target, "ruim"))) {
            assertEquals(0, files.count(), "nenhum temporário fica para trás");
        }
    }

    @Test
    @DisplayName("nomes de segmento e de origem vindos de fora são recusados quando fogem do padrão")
    void namesFromOutsideAreValidated() {
        Path dir = tmp.resolve("j");
        for (String bad : new String[] {"../x.axj", "seg-1.axj", "seg-000000000000001-0000000001.axj/..",
            "a/seg-000000000000001-0000000001.axj", "", "seg-000000000000001-0000000001.open"}) {
            assertThrows(IllegalArgumentException.class, () -> JournalSegments.resolve(dir, null, bad), bad);
        }
        assertThrows(IllegalArgumentException.class,
            () -> JournalSegments.resolve(dir, "..", "seg-000000000000001-0000000001.axj"));
        assertThrows(IllegalArgumentException.class,
            () -> JournalSegments.resolve(dir, "a/b", "seg-000000000000001-0000000001.axj"));
    }

    @Test
    @DisplayName("um segmento ilegível fica fora da lista sem impedir a abertura do journal")
    void unreadableSegmentDoesNotPreventOpening() throws Exception {
        Path dir = tmp.resolve("j");
        SegmentInfo good;
        SegmentInfo bad;
        try (PitrJournal journal = PitrJournal.open(dir, options(), watermark)) {
            offer(journal, "a", "1");
            good = journal.seal();
            offer(journal, "b", "2");
            bad = journal.seal();
        }
        Files.write(dir.resolve(bad.name()), new byte[] {1, 2, 3});
        try (PitrJournal journal = PitrJournal.open(dir, options(), watermark)) {
            assertEquals(List.of(good), journal.segments());
            offer(journal, "c", "3");
            assertTrue(journal.seal().name().compareTo(bad.name()) > 0, "o nome do ilegível não é reusado");
        }
    }

    @Test
    @DisplayName("um registro alterado no disco é detectado na leitura")
    void corruptedRecordFailsTheRead() throws Exception {
        Path dir = tmp.resolve("j");
        SegmentInfo info;
        try (PitrJournal journal = PitrJournal.open(dir, options(), watermark)) {
            offer(journal, "chave", "valor-bem-reconhecivel");
            info = journal.seal();
        }
        byte[] raw = Files.readAllBytes(dir.resolve(info.name()));
        raw[30] ^= 0x01;
        Files.write(dir.resolve(info.name()), raw);
        assertThrows(IOException.class, () -> JournalSegments.read(dir.resolve(info.name()), r -> true));
    }
}
