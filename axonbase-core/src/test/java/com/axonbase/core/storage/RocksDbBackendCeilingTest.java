package com.axonbase.core.storage;

import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Persistência do HLC por teto periódico")
class RocksDbBackendCeilingTest {

    private static final long START = 1_790_000_000_000L;

    @TempDir
    Path tmp;

    private final AtomicLong wall = new AtomicLong(START);

    private RocksDbBackend open(String name, RocksDbBackend.SyncMode mode) {
        return new RocksDbBackend(tmp.resolve(name).toString(), mode, new HybridClock(wall::get, 500L, e -> { }));
    }

    private RocksDbBackend open(String name) {
        return open(name, RocksDbBackend.SyncMode.OFF);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Copia a base com o processo "vivo": é o que um kill -9 deixaria em disco. */
    private void crashCopy(String from, String to) throws Exception {
        Path source = tmp.resolve(from);
        try (var files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path target = tmp.resolve(to).resolve(source.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    private HlcTimestamp firstStampAfterReopening(String name) {
        List<HlcTimestamp> stamps = new ArrayList<>();
        try (RocksDbBackend b = open(name)) {
            b.commitSink((index, batch) -> stamps.add(batch.stamp()));
            b.put("depois", bytes("1"));
        }
        return stamps.get(0);
    }

    @Test
    @DisplayName("no instante em que um lote é entregue, o teto gravado já cobre o carimbo e o índice dele")
    void theStoredCeilingAlwaysCoversADeliveredBatch() {
        Random random = new Random(3);
        List<String> violations = new ArrayList<>();
        try (RocksDbBackend b = open("a")) {
            b.commitSink((index, batch) -> {
                CommitWatermark ceiling = b.persistedCeiling();
                if (ceiling.stamp().compareTo(batch.stamp()) < 0 || ceiling.index() < index) {
                    violations.add(index + " " + batch.stamp() + " teto " + ceiling);
                }
            });
            for (int i = 0; i < 300; i++) {
                int dice = random.nextInt(10);
                if (dice == 0) {
                    wall.addAndGet(random.nextInt(10_000));
                } else if (dice == 1) {
                    wall.addAndGet(-random.nextInt(10_000));
                } else {
                    wall.incrementAndGet();
                }
                b.put("k" + i, bytes("v"));
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    @DisplayName("com o relógio parado, mil commits não regravam o teto nem posição alguma")
    void commitsDoNotWriteMetadata() {
        try (RocksDbBackend b = open("a")) {
            b.put("primeira", bytes("1"));
            CommitWatermark ceiling = b.persistedCeiling();
            CommitWatermark recorded = b.recordedPosition();
            for (int i = 0; i < 1_000; i++) {
                b.put("k" + i, bytes("v"));
            }
            assertEquals(ceiling, b.persistedCeiling());
            assertEquals(recorded, b.recordedPosition());
            assertEquals(1_001L, b.watermark().index());
        }
    }

    @Test
    @DisplayName("com o relógio andando, o teto sobe de tempos em tempos, sempre à frente")
    void theCeilingIsRenewedAheadOfTime() {
        Set<Long> ceilings = new LinkedHashSet<>();
        try (RocksDbBackend b = open("a")) {
            for (int i = 0; i < 200; i++) {
                wall.addAndGet(10);
                b.put("k", bytes("v"));
                long ceiling = b.persistedCeiling().stamp().physical();
                assertTrue(ceiling - wall.get() >= 100, "teto à beira do fim: " + (ceiling - wall.get()));
                assertTrue(ceiling - wall.get() <= 250, "teto longe demais: " + (ceiling - wall.get()));
                ceilings.add(ceiling);
            }
        }
        assertTrue(ceilings.size() >= 8 && ceilings.size() <= 20,
            "2 s de relógio deveriam gravar perto de 15 tetos, foram " + ceilings.size());
    }

    @ParameterizedTest
    @EnumSource(RocksDbBackend.SyncMode.class)
    @DisplayName("queda com o relógio saltando para trás: o primeiro carimbo novo passa de todos os emitidos")
    void aCrashNeverRepeatsOrLowersAStamp(RocksDbBackend.SyncMode mode) throws Exception {
        List<HlcTimestamp> emitted = Collections.synchronizedList(new ArrayList<>());
        long lastIndex;
        try (RocksDbBackend b = open("a", mode)) {
            b.commitSink((index, batch) -> emitted.add(batch.stamp()));
            for (int i = 0; i < 50; i++) {
                wall.addAndGet(i % 7 == 0 ? -40 : 13);
                b.put("k" + i, bytes("v"));
            }
            emitted.add(b.watermark().stamp());
            lastIndex = b.watermark().index();
            crashCopy("a", "crashed");
        }
        wall.addAndGet(-3_600_000L);

        List<Long> indexes = new ArrayList<>();
        HlcTimestamp first;
        try (RocksDbBackend b = open("crashed", mode)) {
            List<HlcTimestamp> stamps = new ArrayList<>();
            b.commitSink((index, batch) -> {
                indexes.add(index);
                stamps.add(batch.stamp());
            });
            b.put("depois", bytes("1"));
            first = stamps.get(0);
        }
        for (HlcTimestamp stamp : emitted) {
            assertTrue(first.compareTo(stamp) > 0, "repetiu ou recuou: " + first + " não passa de " + stamp);
        }
        assertTrue(indexes.get(0) > lastIndex + 1, "depois de uma queda o índice pula: " + indexes.get(0));
    }

    @Test
    @DisplayName("queda entre gravar o teto e usá-lo: o carimbo já emitido continua abaixo dos novos")
    void aCrashRightAfterRaisingTheCeiling() throws Exception {
        HlcTimestamp unused;
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            wall.addAndGet(60_000);
            // sem escrita em curso a marca d'água emite um carimbo: o teto sobe e nenhum lote o acompanha
            unused = b.watermark().stamp();
            crashCopy("a", "crashed");
        }
        wall.addAndGet(-120_000);

        assertTrue(firstStampAfterReopening("crashed").compareTo(unused) > 0);
    }

    @Test
    @DisplayName("duas quedas seguidas sem escrita no meio continuam subindo")
    void consecutiveCrashesKeepMovingForward() throws Exception {
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            crashCopy("a", "one");
        }
        wall.addAndGet(-10_000);
        HlcTimestamp first;
        List<HlcTimestamp> stamps = new ArrayList<>();
        try (RocksDbBackend b = open("one")) {
            b.commitSink((index, batch) -> stamps.add(batch.stamp()));
            b.put("b", bytes("2"));
            first = stamps.get(0);
            crashCopy("one", "two");
        }
        assertTrue(firstStampAfterReopening("two").compareTo(first) > 0);
    }

    @Test
    @DisplayName("fechamento limpo aperta o teto: o reinício não adianta o relógio nem pula índice")
    void aCleanCloseTightensTheCeiling() {
        HlcTimestamp last;
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            b.put("b", bytes("2"));
            last = b.watermark().stamp();
        }
        try (RocksDbBackend b = open("a")) {
            assertEquals(last.physical(), b.persistedCeiling().stamp().physical());
            assertEquals(2L, b.persistedCeiling().index());
        }
    }

    @Test
    @DisplayName("backup físico registra o carimbo exato do que contém, e não o teto")
    void aPhysicalBackupRecordsItsExactContent() throws Exception {
        List<HlcTimestamp> stamps = new ArrayList<>();
        Path store = tmp.resolve("store");
        try (RocksDbBackend b = open("a"); PhysicalBackups backups = PhysicalBackups.open(store)) {
            b.commitSink((index, batch) -> stamps.add(batch.stamp()));
            b.put("a", bytes("1"));
            wall.addAndGet(5);
            b.put("b", bytes("2"));
            wall.addAndGet(5);
            int id = backups.create(b).id();
            Path prepared = PhysicalBackups.prepareRestore(store, id, tmp.resolve("a"));
            try (RocksDbBackend restored = new RocksDbBackend(prepared.toString(), RocksDbBackend.SyncMode.OFF,
                    new HybridClock(wall::get, 500L, e -> { }))) {
                assertEquals(stamps.get(1), restored.recordedPosition().stamp());
                // o índice registrado já inclui o salto da linha do tempo nova, aberta na validação do restore
                assertTrue(restored.recordedPosition().index() > 2L);
            }
        }
    }

    @Test
    @DisplayName("lotes gravados durante a cópia entram na posição registrada do backup")
    void batchesWrittenDuringACopyAreRecorded() throws Exception {
        Path store = tmp.resolve("store");
        AtomicBoolean stop = new AtomicBoolean();
        try (RocksDbBackend b = open("a"); PhysicalBackups backups = PhysicalBackups.open(store)) {
            b.commitSink((index, batch) -> { });
            // cada valor guarda o índice do lote que o gravou
            Thread writer = Thread.ofPlatform().start(() -> {
                for (long i = 1; !stop.get(); i++) {
                    wall.incrementAndGet();
                    b.put("n" + i, bytes(String.valueOf(i)));
                }
            });
            List<Integer> ids = new ArrayList<>();
            try {
                while (b.watermark().index() < 200) {
                    Thread.onSpinWait();
                }
                for (int i = 0; i < 5; i++) {
                    ids.add(backups.create(b).id());
                }
            } finally {
                stop.set(true);
                writer.join(30_000);
            }
            for (int id : ids) {
                Path prepared = PhysicalBackups.prepareRestore(store, id, tmp.resolve("a"));
                try (RocksDbBackend restored = new RocksDbBackend(prepared.toString(), RocksDbBackend.SyncMode.OFF,
                        new HybridClock(wall::get, 500L, e -> { }))) {
                    long newest = restored.keysWithPrefix("n").stream()
                        .mapToLong(k -> Long.parseLong(k.substring(1))).max().orElse(0);
                    assertTrue(restored.recordedPosition().index() >= newest,
                        "backup " + id + " contém o lote " + newest + " e registra só até "
                            + restored.recordedPosition().index());
                }
                PhysicalBackups.discardPrepared(tmp.resolve("a"));
            }
        }
    }

    @Test
    @DisplayName("depois de uma queda a posição registrada é o teto: nunca abaixo do que a base contém")
    void afterACrashTheRecordedPositionIsAnUpperBound() throws Exception {
        HlcTimestamp last;
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            wall.addAndGet(20);
            b.put("b", bytes("2"));
            last = b.watermark().stamp();
            crashCopy("a", "crashed");
        }
        try (RocksDbBackend b = open("crashed")) {
            assertTrue(b.recordedPosition().stamp().compareTo(last) >= 0);
        }
    }

    @Test
    @DisplayName("marca d'água depois do fechamento não tenta gravar numa base fechada")
    void watermarkAfterCloseDoesNotTouchTheDatabase() {
        RocksDbBackend b = open("a");
        b.put("a", bytes("1"));
        b.close();
        wall.addAndGet(60_000);

        assertDoesNotThrow(b::watermark);
        assertEquals(1L, b.watermark().index());
    }

    @Test
    @DisplayName("a base trocada recebe o teto: queda logo depois não recua o carimbo")
    void aSwappedDatabaseReceivesTheCeiling() throws Exception {
        Path store = tmp.resolve("store");
        HlcTimestamp last;
        try (RocksDbBackend b = open("a"); PhysicalBackups backups = PhysicalBackups.open(store)) {
            b.put("a", bytes("1"));
            int id = backups.create(b).id();
            wall.addAndGet(60_000);
            b.put("b", bytes("2"));
            last = b.watermark().stamp();
            b.replaceWith(PhysicalBackups.prepareRestore(store, id, tmp.resolve("a")), 10_000);
            assertTrue(b.persistedCeiling().stamp().compareTo(last) >= 0);
            crashCopy("a", "crashed");
        }
        wall.addAndGet(-600_000);

        assertTrue(firstStampAfterReopening("crashed").compareTo(last) > 0);
    }

    @Test
    @DisplayName("fechar com um commit em curso não marca a base como limpa")
    void closingWithACommitInFlightIsNotACleanShutdown() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RocksDbBackend b = open("a");
        long timeline = b.watermark().timeline();
        b.commitSink((index, batch) -> {
            inside.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread writer = Thread.ofPlatform().start(() -> b.put("lento", bytes("1")));
        assertTrue(inside.await(30, TimeUnit.SECONDS));

        b.close();
        release.countDown();
        writer.join(30_000);

        try (RocksDbBackend reopened = open("a")) {
            assertNotEquals(timeline, reopened.watermark().timeline());
        }
    }

    @Test
    @DisplayName("lote reaplicado leva o teto junto: queda em seguida não recua o carimbo")
    void aReplayedBatchRaisesTheStoredCeiling() throws Exception {
        HlcTimestamp ahead = new HlcTimestamp(START + 90_000, 2);
        try (RocksDbBackend b = open("a")) {
            b.applyArchived(40L, new CommittedBatch("", Map.of("k", bytes("v")), Set.of()).withStamp(ahead));
            assertTrue(b.persistedCeiling().stamp().compareTo(ahead) >= 0);
            assertTrue(b.persistedCeiling().index() >= 40);
            crashCopy("a", "crashed");
        }
        assertTrue(firstStampAfterReopening("crashed").compareTo(ahead) > 0);
    }
}
