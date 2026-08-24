package com.axonbase.core.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Backend de memoria baseado nun {@link TreeMap}. As chaves ordean
 * lexicográficamente, o que permite varrido por prefixo. Para o MVP, todo
 * o estado vive no mapa en memoria.
 */
public final class MemoryBackend implements VersionedKvBackend {

    private final TreeMap<String, byte[]> data = new TreeMap<>();
    private final TreeMap<String, Long> versions = new TreeMap<>();
    private long clock;

    @Override
    public synchronized Optional<byte[]> get(String key) {
        byte[] v = data.get(key);
        return v == null ? Optional.empty() : Optional.of(v.clone());
    }

    @Override
    public synchronized void put(String key, byte[] value) {
        data.put(key, value.clone());
        versions.put(key, ++clock);
    }

    @Override
    public synchronized boolean putIfAbsent(String key, byte[] value) {
        if (data.containsKey(key)) {
            return false;
        }
        put(key, value);
        return true;
    }

    @Override
    public synchronized boolean delete(String key) {
        if (data.remove(key) == null) {
            return false;
        }
        versions.put(key, ++clock);
        return true;
    }

    @Override
    public synchronized List<String> keysWithPrefix(String prefix) {
        List<String> keys = new ArrayList<>();
        for (String k : data.tailMap(prefix).keySet()) {
            if (k.startsWith(prefix)) {
                keys.add(k);
            } else {
                break;
            }
        }
        return keys;
    }

    @Override
    public synchronized long versionOf(String key) {
        return versions.getOrDefault(key, 0L);
    }

    @Override
    public synchronized void commit(java.util.Map<String, Long> expectedVersions,
                                    java.util.Map<String, byte[]> puts, java.util.Set<String> deletes) {
        for (var expected : expectedVersions.entrySet()) {
            if (versionOf(expected.getKey()) != expected.getValue()) {
                throw new VersionConflictException("conflito de versão na chave '" + expected.getKey() + "'");
            }
        }
        for (String key : deletes) {
            if (data.remove(key) != null) {
                versions.put(key, ++clock);
            }
        }
        for (var put : puts.entrySet()) {
            data.put(put.getKey(), put.getValue().clone());
            versions.put(put.getKey(), ++clock);
        }
    }
}
