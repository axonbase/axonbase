package com.axonbase.core.storage;

import com.axonbase.common.Messages;

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
        if (RangeDelete.isMarker(key)) {
            deleteRange(RangeDelete.parse(key, value));
        }
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

    /** Como no RocksDB, some o dado e a versão de cada chave da faixa. */
    private void deleteRange(RangeDelete.Range range) {
        data.subMap(range.from(), true, range.to(), false).clear();
        versions.subMap(range.from(), true, range.to(), false).clear();
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
    public long snapshotScan(long since, EntryVisitor entries, KeyVisitor deleted) throws java.io.IOException {
        long mark;
        List<Object[]> puts = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        synchronized (this) {
            mark = clock;
            for (var e : data.entrySet()) {
                long v = versions.getOrDefault(e.getKey(), 0L);
                if (since <= 0 || v > since) {
                    puts.add(new Object[] {e.getKey(), e.getValue().clone(), v});
                }
            }
            if (since > 0 && deleted != null) {
                versions.forEach((k, v) -> {
                    if (v > since && !data.containsKey(k)) {
                        gone.add(k);
                    }
                });
            }
        }
        for (Object[] p : puts) {
            if (!entries.visit((String) p[0], (byte[]) p[1], (Long) p[2])) {
                return mark;
            }
        }
        for (String k : gone) {
            if (!deleted.visit(k)) {
                break;
            }
        }
        return mark;
    }

    @Override
    public void scanDeletedSince(long since, java.util.function.Predicate<String> visitor) {
        List<String> deleted = new ArrayList<>();
        synchronized (this) {
            versions.forEach((k, v) -> {
                if (v > since && !data.containsKey(k)) {
                    deleted.add(k);
                }
            });
        }
        for (String k : deleted) {
            if (!visitor.test(k)) {
                return;
            }
        }
    }

    @Override
    public synchronized long currentVersion() {
        return clock;
    }

    @Override
    public synchronized long versionOf(String key) {
        return versions.getOrDefault(key, 0L);
    }

@Override
    public synchronized void commit(java.util.Map<String, Long> expectedVersions,
                                    java.util.Map<String, byte[]> puts, java.util.Set<String> deletes) {
        commit(expectedVersions, puts, deletes, java.util.Set.of());
    }

    @Override
    public synchronized void commit(java.util.Map<String, Long> expectedVersions,
                                    java.util.Map<String, byte[]> puts, java.util.Set<String> deletes,
                                    java.util.Set<String> readPrefixes) {
        for (var expected : expectedVersions.entrySet()) {
            if (versionOf(expected.getKey()) != expected.getValue()) {
                throw new VersionConflictException(Messages.get("storage_version_conflict", expected.getKey()), expected.getKey());
            }
        }
        for (String prefix : readPrefixes) {
            for (String key : keysWithPrefix(prefix)) {
                if (expectedVersions.containsKey(key) || puts.containsKey(key) || deletes.contains(key)) {
                    continue;
                }
                throw new VersionConflictException(Messages.get("storage_phantom_read", prefix, key), key);
            }
        }
        RangeDelete.in(puts).forEach(this::deleteRange);
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
