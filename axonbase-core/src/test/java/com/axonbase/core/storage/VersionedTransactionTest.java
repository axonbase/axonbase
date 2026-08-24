package com.axonbase.core.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionedTransactionTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    @Test
    void transacaoDetectaLostUpdatePorVersaoLida() {
        MemoryBackend backend = new MemoryBackend();
        backend.put("account", bytes("100"));
        Transaction first = new Transaction(backend);
        Transaction second = new Transaction(backend);

        first.get("account");
        second.get("account");
        first.put("account", bytes("90"));
        second.put("account", bytes("80"));

        first.commit();
        assertThrows(VersionConflictException.class, second::commit);
        assertEquals("90", text(backend.get("account").orElseThrow()));
    }

    @Test
    void transacaoLeSnapshotMesmoAposEscritaExterna() {
        MemoryBackend backend = new MemoryBackend();
        backend.put("key", bytes("before"));
        Transaction transaction = new Transaction(backend);

        assertEquals("before", text(transaction.get("key").orElseThrow()));
        backend.put("key", bytes("after"));

        assertEquals("before", text(transaction.get("key").orElseThrow()));
    }

    @Test
    void batchAtomicoValidaTodasPrecondicoesAntesDeAplicar() {
        MemoryBackend backend = new MemoryBackend();
        backend.put("a", bytes("old"));
        long aVersion = backend.versionOf("a");
        backend.put("a", bytes("newer"));

        assertThrows(VersionConflictException.class, () -> backend.commit(
            Map.of("a", aVersion, "missing", 0L),
            Map.of("a", bytes("new"), "missing", bytes("created")),
            java.util.Set.of()));
        assertEquals("newer", text(backend.get("a").orElseThrow()));
        assertThrows(java.util.NoSuchElementException.class, () -> backend.get("missing").orElseThrow());
    }

    @Test
    void walRecuperaBatchCompletoDepoisDeReabrir() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("axon-mvcc");
        try {
            WalBackend first = new WalBackend(dir.toString());
            Transaction transaction = new Transaction(first);
            transaction.put("document", bytes("ok"));
            transaction.put("index", bytes("document"));
            transaction.commit();
            first.flush();
            first.close();

            WalBackend recovered = new WalBackend(dir.toString());
            assertEquals("ok", text(recovered.get("document").orElseThrow()));
            assertEquals("document", text(recovered.get("index").orElseThrow()));
            recovered.close();
        } finally {
            try (var files = java.nio.file.Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }
}
