package com.axonbase.core.storage;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.HashMap;

/**
 * Transación: unha vista {@link KvBackend} que bufferiza os writes e deixa ver
 * os cambios non commiteados. As lecturas leen o staged e, senón, o backend
 * subxacente (read-through), o que da unha snapshot consistente dentro da
 * transación.
 * <p>
 * {@code commit()} aplica o staged ó backend base; {@code cancel()} descarta.
 */
public final class Transaction implements KvBackend {

    private final KvBackend base;
    private final TreeMap<String, byte[]> staged = new TreeMap<>();
    private final java.util.Set<String> deleted = new java.util.HashSet<>();
    /** Valor visto na primeira leitura, para leituras repetíveis por chave. */
    private final java.util.Map<String, Optional<byte[]>> snapshot = new HashMap<>();
    /** Versão esperada no commit otimista. */
    private final java.util.Map<String, Long> readVersions = new HashMap<>();
    private boolean open = true;

    public Transaction(KvBackend base) {
        this.base = base;
    }

    public boolean isOpen() {
        return open;
    }
    public java.util.Map<String, byte[]> stagedWrites() { return java.util.Map.copyOf(staged); }
    public java.util.Set<String> stagedDeletes() { return java.util.Set.copyOf(deleted); }

    @Override
    public Optional<byte[]> get(String key) {
        ensureOpen();
        if (deleted.contains(key)) {
            return Optional.empty();
        }
        byte[] v = staged.get(key);
        if (v != null) {
            return Optional.of(v.clone());
        }
        Optional<byte[]> cached = snapshot.get(key);
        if (cached != null) {
            return cached.map(byte[]::clone);
        }
        Optional<byte[]> read;
        if (base instanceof VersionedKvBackend versioned) {
            Optional<VersionedKvBackend.VersionedValue> value = versioned.getVersioned(key);
            readVersions.put(key, value.map(VersionedKvBackend.VersionedValue::version)
                .orElseGet(() -> versioned.versionOf(key)));
            read = value.map(VersionedKvBackend.VersionedValue::value);
        } else {
            read = base.get(key);
        }
        Optional<byte[]> frozen = read.map(byte[]::clone);
        snapshot.put(key, frozen);
        return frozen.map(byte[]::clone);
    }

    @Override
    public void put(String key, byte[] value) {
        ensureOpen();
        // Toda escrita tem uma precondição, inclusive CREATE sem leitura prévia.
        if (!staged.containsKey(key) && !snapshot.containsKey(key)) {
            get(key);
        }
        staged.put(key, value.clone());
        deleted.remove(key);
    }

    @Override
    public boolean putIfAbsent(String key, byte[] value) {
        ensureOpen();
        if (staged.containsKey(key) || deleted.contains(key)) {
            return false;
        }
        if (get(key).isPresent()) {
            return false;
        }
        put(key, value);
        return true;
    }

    @Override
    public boolean delete(String key) {
        ensureOpen();
        boolean existed = staged.containsKey(key) || (!deleted.contains(key) && get(key).isPresent());
        if (!existed) {
            return false;
        }
        staged.remove(key);
        deleted.add(key);
        return true;
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        ensureOpen();
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (String k : base.keysWithPrefix(prefix)) {
            // Captura o valor/versão de cada chave do scan para validá-la no commit.
            get(k);
            keys.add(k);
        }
        for (String k : staged.keySet()) {
            if (k.startsWith(prefix)) {
                keys.add(k);
            }
        }
        keys.removeAll(deleted);
        return List.copyOf(keys);
    }

    @Override
    public void flush() {
    }

    /** Aplica o staged ó backend base e pecha a transación. */
    public void commit() {
        ensureOpen();
        if (base instanceof VersionedKvBackend versioned) {
            versioned.commit(java.util.Map.copyOf(readVersions), java.util.Map.copyOf(staged),
                java.util.Set.copyOf(deleted));
            finish();
            return;
        }
        for (String k : deleted) {
            base.delete(k);
        }
        for (var e : staged.entrySet()) {
            base.put(e.getKey(), e.getValue());
        }
        finish();
    }

    private void finish() {
        open = false;
        staged.clear();
        deleted.clear();
        snapshot.clear();
        readVersions.clear();
    }

    /** Descarta o estado sen tocar o base. */
    public void cancel() {
        finish();
    }

    private void ensureOpen() {
        if (!open) {
            throw new IllegalStateException("transación xa pechada");
        }
    }
}
