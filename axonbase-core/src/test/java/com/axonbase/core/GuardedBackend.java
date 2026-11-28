package com.axonbase.core;

import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Backend de teste sobre memória que anota todo pedido de valor, ou de lista completa de chaves, nos prefixos
 * guardados. Só {@link #scanPrefix} passa sem anotação: é a varredura em fluxo, só de chaves.
 */
final class GuardedBackend implements VersionedKvBackend {

    private final MemoryBackend inner = new MemoryBackend();
    private final List<String> guarded = new CopyOnWriteArrayList<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();

    void guard(String... prefixes) {
        guarded.addAll(List.of(prefixes));
    }

    List<String> violations() {
        return List.copyOf(violations);
    }

    void clearViolations() {
        violations.clear();
    }

    private void note(String operation, String key) {
        if (guarded.stream().anyMatch(key::startsWith)) {
            violations.add(operation + " " + key.replace('\u0000', '/'));
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        note("get", key);
        return inner.get(key);
    }

    @Override
    public Optional<VersionedValue> getVersioned(String key) {
        note("getVersioned", key);
        return inner.getVersioned(key);
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        note("keysWithPrefix", prefix);
        return inner.keysWithPrefix(prefix);
    }

    @Override
    public void scanPrefix(String prefix, Predicate<String> visitor) {
        for (String key : inner.keysWithPrefix(prefix)) {
            if (!visitor.test(key)) {
                return;
            }
        }
    }

    @Override
    public void scanEntries(String prefix, BiPredicate<String, byte[]> visitor) {
        note("scanEntries", prefix);
        inner.scanEntries(prefix, visitor);
    }

    @Override
    public void scanEntriesFrom(String prefix, String from, BiPredicate<String, byte[]> visitor) {
        note("scanEntriesFrom", prefix);
        inner.scanEntriesFrom(prefix, from, visitor);
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
    public long versionOf(String key) {
        return inner.versionOf(key);
    }

    @Override
    public Optional<byte[]> snapshotAt(String key, long timestampEpochMillis) {
        return inner.snapshotAt(key, timestampEpochMillis);
    }

    @Override
    public void commit(Map<String, Long> expectedVersions, Map<String, byte[]> puts, Set<String> deletes,
                       Set<String> readPrefixes) {
        inner.commit(expectedVersions, puts, deletes, readPrefixes);
    }
}
