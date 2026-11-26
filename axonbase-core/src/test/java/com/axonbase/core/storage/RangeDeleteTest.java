package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A marca de remoção por faixa dentro de um lote, em cada armazenamento. */
@DisplayName("Remoção por faixa")
class RangeDeleteTest {

    @TempDir
    Path tmp;

    private static final RangeDelete.Range RANGE = RangeDelete.ofPrefix("t\u0000p1\u0000");

    private VersionedKvBackend open(String kind) {
        return switch (kind) {
            case "rocks" -> new RocksDbBackend(tmp.resolve("db").toString(), RocksDbBackend.SyncMode.OFF);
            case "wal" -> new WalBackend(tmp.resolve("wal").toString());
            default -> new MemoryBackend();
        };
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> marker() {
        Map<String, byte[]> puts = new LinkedHashMap<>();
        puts.put(RangeDelete.key(RANGE), RangeDelete.value(RANGE));
        return puts;
    }

    private static void seed(VersionedKvBackend db) {
        db.put("t\u0000p0\u0000a", bytes("antes"));
        db.put("t\u0000p1\u0000a", bytes("um"));
        db.put("t\u0000p1\u0000b", bytes("dois"));
        db.put("t\u0000p2\u0000a", bytes("depois"));
    }

    private static List<String> rows(KvBackend db) {
        return db.keysWithPrefix("t\u0000");
    }

    @Test
    @DisplayName("a marca vai e volta, e a faixa de um prefixo cobre só ele")
    void marker_roundTrips() {
        assertEquals(RANGE, RangeDelete.parse(RangeDelete.key(RANGE), RangeDelete.value(RANGE)));
        assertTrue(RANGE.contains("t\u0000p1\u0000zzz"));
        assertFalse(RANGE.contains("t\u0000p10\u0000a"));
        assertFalse(RangeDelete.key(RANGE).contains("\u0000"));
        assertThrows(IllegalArgumentException.class, () -> new RangeDelete.Range("b", "a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "rocks", "wal"})
    @DisplayName("o lote apaga a faixa inteira, deixa o resto e grava a marca")
    void commit_deletesRange(String kind) {
        VersionedKvBackend db = open(kind);
        try {
            seed(db);
            db.commit(Map.of(), marker(), Set.of());

            assertEquals(List.of("t\u0000p0\u0000a", "t\u0000p2\u0000a"), rows(db));
            assertTrue(db.get(RangeDelete.key(RANGE)).isPresent());
        } finally {
            db.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "rocks", "wal"})
    @DisplayName("um put do mesmo lote dentro da faixa sobrevive, e um put solto da marca também apaga")
    void putsInSameBatchSurvive(String kind) {
        VersionedKvBackend db = open(kind);
        try {
            seed(db);
            Map<String, byte[]> puts = marker();
            puts.put("t\u0000p1\u0000c", bytes("novo"));
            db.commit(Map.of(), puts, Set.of());
            assertEquals(List.of("t\u0000p0\u0000a", "t\u0000p1\u0000c", "t\u0000p2\u0000a"), rows(db));

            db.put(RangeDelete.key(RANGE), RangeDelete.value(RANGE));
            assertEquals(List.of("t\u0000p0\u0000a", "t\u0000p2\u0000a"), rows(db));
        } finally {
            db.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "rocks"})
    @DisplayName("quem leu uma chave apagada pela faixa conflita no commit")
    void rangeDelete_conflictsReaders(String kind) {
        VersionedKvBackend db = open(kind);
        try {
            seed(db);
            Transaction tx = new Transaction(db);
            tx.get("t\u0000p1\u0000a");
            tx.put("outra", bytes("x"));
            db.commit(Map.of(), marker(), Set.of());

            assertThrows(VersionConflictException.class, tx::commit);
        } finally {
            db.close();
        }
    }

    @Test
    @DisplayName("RocksDB: a faixa continua apagada depois de reabrir e entra no lote confirmado")
    void rocks_durableAndDelivered() {
        AtomicReference<Map<String, byte[]>> delivered = new AtomicReference<>();
        try (RocksDbBackend db = new RocksDbBackend(tmp.resolve("db").toString(), RocksDbBackend.SyncMode.OFF)) {
            seed(db);
            db.commitSink((index, batch) -> delivered.set(batch.puts()));
            db.commit(Map.of(), marker(), Set.of());
        }
        assertTrue(delivered.get().containsKey(RangeDelete.key(RANGE)));
        try (RocksDbBackend db = new RocksDbBackend(tmp.resolve("db").toString(), RocksDbBackend.SyncMode.OFF)) {
            assertEquals(List.of("t\u0000p0\u0000a", "t\u0000p2\u0000a"), rows(db));
        }
    }

    @Test
    @DisplayName("RocksDB: a aplicação replicada também apaga a faixa")
    void rocks_applyReplicated() {
        try (RocksDbBackend db = new RocksDbBackend(tmp.resolve("db").toString(), RocksDbBackend.SyncMode.OFF)) {
            seed(db);
            db.applyReplicated(marker(), Set.of());

            assertEquals(List.of("t\u0000p0\u0000a", "t\u0000p2\u0000a"), rows(db));
        }
    }

    @Test
    @DisplayName("transação: leitura sem registro e gravação cega não geram conflito")
    void transaction_peekAndBlindPut() {
        MemoryBackend db = new MemoryBackend();
        db.put("meta", bytes("1"));
        Transaction tx = new Transaction(db);
        assertEquals("1", new String(tx.peek("meta").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(List.of("meta"), tx.peekKeys("me"));
        tx.putBlind("outra", bytes("2"));
        db.put("meta", bytes("mudou"));
        db.put("outra", bytes("mudou"));

        tx.commit();

        assertEquals("2", new String(db.get("outra").orElseThrow(), StandardCharsets.UTF_8));
    }
}
