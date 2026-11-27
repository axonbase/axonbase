package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Transação: leituras sem registro")
class UntrackedReadsTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static MemoryBackend backend(String... keys) {
        MemoryBackend backend = new MemoryBackend();
        for (String key : keys) {
            backend.put(key, bytes(key));
        }
        return backend;
    }

    private static List<String> scan(KvBackend view, String prefix) {
        List<String> keys = new ArrayList<>();
        view.scanPrefix(prefix, key -> {
            keys.add(key);
            return true;
        });
        return keys;
    }

    @Test
    @DisplayName("a varredura junta, em ordem e sem repetir, a base com o que a transação gravou")
    void scan_mergesStagedKeysInOrder() {
        Transaction tx = new Transaction(backend("t/b", "t/d", "t/f", "u/a"));
        tx.put("t/a", bytes("novo"));
        tx.put("t/d", bytes("mudou"));
        tx.put("t/e", bytes("novo"));
        tx.put("t/g", bytes("novo"));
        tx.put("u/b", bytes("fora"));
        tx.delete("t/f");

        assertEquals(List.of("t/a", "t/b", "t/d", "t/e", "t/g"), scan(tx.untrackedReads(), "t/"));
    }

    @Test
    @DisplayName("faixa apagada pela transação não aparece, salvo o que foi gravado nela depois")
    void scan_hidesDroppedRange() {
        Transaction tx = new Transaction(backend("t/a", "t/b1", "t/b2", "t/c"));
        RangeDelete.Range range = RangeDelete.ofPrefix("t/b");
        tx.put(RangeDelete.key(range), RangeDelete.value(range));
        tx.put("t/b3", bytes("depois"));

        assertEquals(List.of("t/a", "t/b3", "t/c"), scan(tx.untrackedReads(), "t/"));
    }

    @Test
    @DisplayName("chave que a transação já leu como ausente continua ausente")
    void scan_keepsRepeatableAbsence() {
        MemoryBackend backend = backend("t/a");
        Transaction tx = new Transaction(backend);
        assertEquals(true, tx.get("t/b").isEmpty());
        backend.put("t/b", bytes("alheia"));

        assertEquals(List.of("t/a"), scan(tx.untrackedReads(), "t/"));
    }

    @Test
    @DisplayName("a varredura para quando o visitante pede, na base ou nas chaves gravadas")
    void scan_stopsWhenVisitorSaysSo() {
        Transaction tx = new Transaction(backend("t/b", "t/d"));
        tx.put("t/a", bytes("novo"));
        tx.put("t/e", bytes("novo"));

        for (String last : List.of("t/a", "t/b", "t/e")) {
            List<String> seen = new ArrayList<>();
            tx.untrackedReads().scanPrefix("t/", key -> {
                seen.add(key);
                return !key.equals(last);
            });
            assertEquals(last, seen.get(seen.size() - 1));
        }
    }

    @Test
    @DisplayName("o que se lê pela vista não faz o commit conflitar")
    void reads_areNotValidatedAtCommit() {
        MemoryBackend backend = backend("t/a", "t/b");
        Transaction tx = new Transaction(backend);
        KvBackend view = tx.untrackedReads();
        assertEquals(List.of("t/a", "t/b"), scan(view, "t/"));
        assertEquals(true, view.get("t/a").isPresent());
        backend.put("t/a", bytes("alheia"));
        backend.put("t/c", bytes("alheia"));
        tx.put("u/a", bytes("minha"));

        assertDoesNotThrow(tx::commit);
    }

    @Test
    @DisplayName("a vista não grava")
    void view_isReadOnly() {
        KvBackend view = new Transaction(backend("t/a")).untrackedReads();

        assertThrows(UnsupportedOperationException.class, () -> view.put("t/b", bytes("x")));
        assertThrows(UnsupportedOperationException.class, () -> view.putIfAbsent("t/b", bytes("x")));
        assertThrows(UnsupportedOperationException.class, () -> view.delete("t/a"));
    }
}
