package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Outra escrita entra num ponto escolhido da leitura de uma transação. Qualquer que seja o ponto, a transação ou vê o
 * valor dela ou conflita no commit: nunca grava por cima.
 */
@DisplayName("Transação: escrita no meio da leitura não é sobrescrita")
class TransactionReadRaceTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("chave ausente criada durante a leitura")
    void createdWhileReadingAbsentKey(int point) {
        Intruder backend = new Intruder(point, false);

        assertIntruderSurvives(backend, new Transaction(backend));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("chave presente alterada durante a leitura, na leitura versionada padrão")
    void changedWhileReadingPresentKey(int point) {
        Intruder backend = new Intruder(point, true);
        backend.inner.put("k", bytes("antes"));

        assertIntruderSurvives(backend, new Transaction(backend));
    }

    private static void assertIntruderSurvives(Intruder backend, Transaction tx) {
        Optional<byte[]> seen = tx.get("k");
        boolean sawIntruder = seen.isPresent() && "outro".equals(text(seen.get()));
        if (!sawIntruder) {
            tx.put("k", bytes("meu"));
            assertThrows(VersionConflictException.class, tx::commit);
        }
        assertEquals("outro", text(backend.inner.get("k").orElseThrow()));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Grava {@code outro} logo depois da leitura de número {@code point}. */
    private static final class Intruder implements VersionedKvBackend {

        final MemoryBackend inner = new MemoryBackend();
        private final int point;
        private final boolean defaultVersionedRead;
        private int reads;

        Intruder(int point, boolean defaultVersionedRead) {
            this.point = point;
            this.defaultVersionedRead = defaultVersionedRead;
        }

        private void intrude(String key) {
            if (++reads == point) {
                inner.put(key, bytes("outro"));
            }
        }

        @Override
        public Optional<VersionedValue> getVersioned(String key) {
            if (defaultVersionedRead) {
                return VersionedKvBackend.super.getVersioned(key);
            }
            Optional<VersionedValue> value = inner.getVersioned(key);
            intrude(key);
            return value;
        }

        @Override
        public long versionOf(String key) {
            long version = inner.versionOf(key);
            intrude(key);
            return version;
        }

        @Override
        public Optional<byte[]> get(String key) {
            Optional<byte[]> value = inner.get(key);
            if (defaultVersionedRead) {
                intrude(key);
            }
            return value;
        }

        @Override
        public void put(String key, byte[] value) {
            inner.put(key, value);
        }

        @Override
        public boolean putIfAbsent(String key, byte[] value) {
            return inner.putIfAbsent(key, value);
        }

        @Override
        public boolean delete(String key) {
            return inner.delete(key);
        }

        @Override
        public List<String> keysWithPrefix(String prefix) {
            return inner.keysWithPrefix(prefix);
        }

        @Override
        public void commit(Map<String, Long> expectedVersions, Map<String, byte[]> puts, Set<String> deletes,
                           Set<String> readPrefixes) {
            inner.commit(expectedVersions, puts, deletes, readPrefixes);
        }
    }
}
