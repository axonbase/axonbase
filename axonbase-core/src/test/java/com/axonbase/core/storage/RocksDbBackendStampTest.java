package com.axonbase.core.storage;

import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Carimbo HLC no commit do armazenamento")
class RocksDbBackendStampTest {

    @TempDir
    Path tmp;

    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);

    private record Delivered(long index, CommittedBatch batch) {
    }

    private static final class Sink implements BatchSink {
        final List<Delivered> got = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void offer(long index, CommittedBatch batch) {
            got.add(new Delivered(index, batch));
        }
    }

    private RocksDbBackend open(String name) {
        return new RocksDbBackend(tmp.resolve(name).toString(), RocksDbBackend.SyncMode.OFF,
            new HybridClock(wall::get, 500L, event -> { }));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("put, putIfAbsent, delete e commit entregam o lote carimbado, com índices consecutivos")
    void everyWritePathDeliversAStampedBatch() {
        Sink sink = new Sink();
        try (RocksDbBackend b = open("a")) {
            b.commitSink(sink);
            b.put("a", bytes("1"));
            assertTrue(b.putIfAbsent("b", bytes("2")));
            assertTrue(b.delete("a"));
            b.commit(Map.of(), Map.of("c", bytes("3")), Set.of("b"));
        }
        assertEquals(List.of(1L, 2L, 3L, 4L), sink.got.stream().map(Delivered::index).toList());
        HlcTimestamp previous = HlcTimestamp.NONE;
        for (Delivered d : sink.got) {
            assertTrue(d.batch().stamp().compareTo(previous) > 0);
            previous = d.batch().stamp();
        }
        assertEquals(Set.of("a"), sink.got.get(0).batch().puts().keySet());
        assertEquals(Set.of("a"), sink.got.get(2).batch().deletes());
        assertEquals(Set.of("c"), sink.got.get(3).batch().puts().keySet());
        assertEquals(Set.of("b"), sink.got.get(3).batch().deletes());
    }

    @Test
    @DisplayName("operação que não grava nada não consome índice")
    void noOpWritesDeliverNothing() {
        Sink sink = new Sink();
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            b.commitSink(sink);
            b.delete("inexistente");
            b.putIfAbsent("a", bytes("2"));
            b.commit(Map.of(), Map.of(), Set.of());
            assertEquals(1L, b.persistedPosition().index());
        }
        assertTrue(sink.got.isEmpty());
    }

    @Test
    @DisplayName("reinício: o primeiro carimbo novo é maior que o persistido, mesmo com o relógio atrasado")
    void restartContinuesAboveThePersistedStamp() {
        HlcTimestamp last;
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            b.put("b", bytes("2"));
            last = b.persistedPosition().stamp();
        }
        wall.addAndGet(-3_600_000L);
        Sink sink = new Sink();
        try (RocksDbBackend b = open("a")) {
            assertEquals(2L, b.persistedPosition().index());
            assertEquals(last, b.persistedPosition().stamp());
            b.commitSink(sink);
            b.put("c", bytes("3"));
        }
        assertEquals(3L, sink.got.get(0).index(), "sem divergência, os índices continuam sem salto");
        assertTrue(sink.got.get(0).batch().stamp().compareTo(last) > 0);
    }

    @Test
    @DisplayName("a chave de metadados não aparece em varreduras nem no snapshot de backup")
    void metadataKeyIsInvisibleToScans() throws Exception {
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            assertEquals(List.of("a"), b.keysWithPrefix(""));
            List<String> snapshot = new ArrayList<>();
            b.snapshotScan(0, (k, v, ver) -> snapshot.add(k), null);
            assertEquals(List.of("a"), snapshot);
        }
    }

    @Test
    @DisplayName("8 threads: índices sem buraco, na ordem dos carimbos, e por chave a entrega segue o carimbo")
    void concurrentCommitsKeepIndexAndStampOrder() throws Exception {
        Sink sink = new Sink();
        try (RocksDbBackend b = open("a")) {
            b.commitSink(sink);
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int n = t;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 500; i++) {
                        if (i % 7 == 0) {
                            b.commit(Map.of(), Map.of("k" + (i % 13), bytes(n + ":" + i),
                                "own" + n, bytes("x" + i)), Set.of());
                        } else {
                            b.put("k" + (i % 13), bytes(n + ":" + i));
                        }
                        if (i % 50 == 0) {
                            wall.incrementAndGet();
                        }
                    }
                }));
            }
            for (Thread th : threads) {
                th.join(60_000);
            }
            assertEquals(4000, sink.got.size());
            TreeMap<Long, HlcTimestamp> byIndex = new TreeMap<>();
            sink.got.forEach(d -> byIndex.put(d.index(), d.batch().stamp()));
            assertEquals(1L, byIndex.firstKey());
            assertEquals(4000L, byIndex.lastKey());
            assertEquals(4000, byIndex.size());
            HlcTimestamp previous = HlcTimestamp.NONE;
            for (HlcTimestamp stamp : byIndex.values()) {
                assertTrue(stamp.compareTo(previous) > 0);
                previous = stamp;
            }
            Map<String, HlcTimestamp> lastByKey = new TreeMap<>();
            Map<String, byte[]> replay = new TreeMap<>();
            for (Delivered d : sink.got) {
                d.batch().puts().forEach((key, value) -> {
                    HlcTimestamp before = lastByKey.put(key, d.batch().stamp());
                    assertTrue(before == null || d.batch().stamp().compareTo(before) > 0,
                        "chave " + key + " entregue fora da ordem de carimbo");
                    replay.put(key, value);
                });
            }
            for (var entry : replay.entrySet()) {
                assertEquals(new String(entry.getValue(), StandardCharsets.UTF_8),
                    new String(b.get(entry.getKey()).orElseThrow(), StandardCharsets.UTF_8),
                    "replay na ordem de entrega diverge em " + entry.getKey());
            }
            assertEquals(4000L, b.persistedPosition().index());
        }
        try (RocksDbBackend reopened = open("a")) {
            assertEquals(4000L, reopened.persistedPosition().index());
        }
    }

    @Test
    @DisplayName("a marca d'água não passa de um lote ainda não entregue")
    void watermarkStopsBeforeAnInFlightBatch() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<HlcTimestamp> blocked = new ArrayList<>();
        try (RocksDbBackend b = open("a")) {
            b.put("antes", bytes("1"));
            b.commitSink((index, batch) -> {
                blocked.add(batch.stamp());
                inside.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread writer = Thread.ofPlatform().start(() -> b.put("lento", bytes("2")));
            assertTrue(inside.await(10, TimeUnit.SECONDS));

            CommitWatermark during = b.watermark();
            assertEquals(1L, during.index());
            assertTrue(during.stamp().compareTo(blocked.get(0)) < 0);

            release.countDown();
            writer.join(10_000);
            CommitWatermark after = b.watermark();
            assertEquals(2L, after.index());
            assertTrue(after.stamp().compareTo(blocked.get(0)) > 0);
        }
    }

    @Test
    @DisplayName("lote arquivado é aplicado com o carimbo e o índice originais, sem voltar ao journal")
    void applyArchivedKeepsTheOriginalPosition() {
        HlcTimestamp stamp = new HlcTimestamp(wall.get() + 10, 3);
        Sink sink = new Sink();
        try (RocksDbBackend b = open("a")) {
            b.put("some", bytes("old"));
            b.commitSink(sink);
            b.applyArchived(77L, new CommittedBatch("", Map.of("k", bytes("v")), Set.of("some")).withStamp(stamp));
            assertEquals("v", new String(b.get("k").orElseThrow(), StandardCharsets.UTF_8));
            assertTrue(b.get("some").isEmpty());
            assertTrue(b.versionOf("k") > 0);
            assertEquals(77L, b.persistedPosition().index());
            assertEquals(stamp, b.persistedPosition().stamp());
        }
        assertTrue(sink.got.isEmpty());
    }

    @Test
    @DisplayName("trocar a base abre um buraco no índice e o relógio não recua")
    void replacingTheDatabaseBreaksTheTimeline() {
        Path live = tmp.resolve("a");
        Path store = tmp.resolve("bk");
        Sink sink = new Sink();
        try (RocksDbBackend b = open("a"); PhysicalBackups pb = PhysicalBackups.open(store)) {
            b.put("a", bytes("1"));
            int id = pb.create(b).id();
            b.put("b", bytes("2"));
            HlcTimestamp before = b.persistedPosition().stamp();

            b.replaceWith(PhysicalBackups.prepareRestore(store, id, live), 10_000);

            b.commitSink(sink);
            b.put("c", bytes("3"));
            assertTrue(sink.got.get(0).index() > 3, "índice 2 já usado e um salto: " + sink.got.get(0).index());
            assertTrue(sink.got.get(0).batch().stamp().compareTo(before) > 0);
        }
    }

    @Test
    @DisplayName("journal à frente da base (base voltou no tempo): o índice pula e o relógio não recua")
    void resumingAfterALongerArchiveSkipsAnIndex() {
        Sink sink = new Sink();
        HlcTimestamp archived = new HlcTimestamp(wall.get() + 100_000, 4);
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            b.resumeAfter(10L, archived);
            b.commitSink(sink);
            b.put("b", bytes("2"));
        }
        assertEquals(12L, sink.got.get(0).index());
        assertTrue(sink.got.get(0).batch().stamp().compareTo(archived) > 0);
    }

    @Test
    @DisplayName("journal em dia com a base não abre buraco")
    void resumingAfterAnEqualArchiveKeepsContinuity() {
        Sink sink = new Sink();
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
            b.resumeAfter(1L, b.persistedPosition().stamp());
            b.commitSink(sink);
            b.put("b", bytes("2"));
        }
        assertEquals(2L, sink.got.get(0).index());
    }

    @Test
    @DisplayName("escrita feita por uma versão sem carimbo (downgrade) é percebida e abre um buraco")
    void writesByAnOlderBinaryBreakTheTimeline() throws Exception {
        try (RocksDbBackend b = open("a")) {
            b.put("a", bytes("1"));
        }
        writeAsOlderBinary(tmp.resolve("a"));
        Sink sink = new Sink();
        try (RocksDbBackend b = open("a")) {
            b.commitSink(sink);
            b.put("c", bytes("3"));
        }
        assertEquals(3L, sink.got.get(0).index());
    }

    /** Uma gravação direta na column family de dados, sem a chave {@code !hlc}, como faz a 0.10.1. */
    private static void writeAsOlderBinary(Path dir) throws Exception {
        List<byte[]> names;
        try (org.rocksdb.Options options = new org.rocksdb.Options()) {
            names = RocksDB.listColumnFamilies(options, dir.toString());
        }
        var descriptors = names.stream().map(org.rocksdb.ColumnFamilyDescriptor::new).toList();
        List<org.rocksdb.ColumnFamilyHandle> handles = new ArrayList<>();
        try (org.rocksdb.DBOptions options = new org.rocksdb.DBOptions();
             RocksDB db = RocksDB.open(options, dir.toString(), descriptors, handles)) {
            for (int i = 0; i < names.size(); i++) {
                if (new String(names.get(i), StandardCharsets.UTF_8).equals("data")) {
                    db.put(handles.get(i), bytes("velho"), bytes("x"));
                }
            }
            handles.forEach(org.rocksdb.ColumnFamilyHandle::close);
        }
    }
}
