package com.axonbase.core;

import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Backend de teste sobre memória que conta leituras de chave e registra os prefixos listados ou varridos. */
final class CountingBackend implements KvBackend {

    final MemoryBackend inner = new MemoryBackend();
    final AtomicLong gets = new AtomicLong();
    /** Prefixos de {@code keysWithPrefix}, na ordem das chamadas. */
    final List<String> listings = new CopyOnWriteArrayList<>();
    /** Prefixos de {@code scanPrefix} e {@code scanEntries}. */
    final List<String> scans = new CopyOnWriteArrayList<>();

    @Override
    public Optional<byte[]> get(String key) {
        gets.incrementAndGet();
        return inner.get(key);
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
        listings.add(prefix);
        return inner.keysWithPrefix(prefix);
    }

    @Override
    public void scanPrefix(String prefix, Predicate<String> visitor) {
        scans.add(prefix);
        for (String key : inner.keysWithPrefix(prefix)) {
            if (!visitor.test(key)) {
                return;
            }
        }
    }

    @Override
    public void scanEntries(String prefix, BiPredicate<String, byte[]> visitor) {
        scans.add(prefix);
        for (String key : inner.keysWithPrefix(prefix)) {
            byte[] value = inner.get(key).orElse(new byte[0]);
            if (!visitor.test(key, value)) {
                return;
            }
        }
    }
}
