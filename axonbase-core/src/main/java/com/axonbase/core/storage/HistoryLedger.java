package com.axonbase.core.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Ledger histórico para time travel. Registra o estado de cada chave
 * após cada operação de escrita, indexado por timestamp.
 *
 * <p>O {@link #record(key, value, version, timestamp)} é chamado a cada
 * {@code put()} com o valor <strong>novo</strong>. Assim o
 * {@link #snapshotAt(key, timestamp)} retorna o valor mais recente que
 * estava vigente em ou antes do timestamp consultado.</p>
 */
public final class HistoryLedger {

    private final Map<String, ConcurrentSkipListMap<Long, Entry>> ledger = new ConcurrentHashMap<>();

    private record Entry(byte[] value, long version, long timestampEpochMillis) {
    }

    private final long retentionMillis;

    public HistoryLedger() {
        this(7L * 24 * 60 * 60 * 1000);
    }

    public HistoryLedger(long retentionMillis) {
        this.retentionMillis = retentionMillis;
    }

    /**
     * Registra o valor <strong>novo</strong> após uma operação de escrita.
     */
    public void record(String key, byte[] value, long version, long timestampEpochMillis) {
        if (key == null || value == null) return;
        var entries = ledger.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
        entries.put(timestampEpochMillis, new Entry(value.clone(), version, timestampEpochMillis));
    }

    /**
     * Retorna o snapshot de uma chave no timestamp dado.
     * Busca a entry com timestamp &lt;= alvo (a vigente na época).
     */
    public Optional<byte[]> snapshotAt(String key, long timestampEpochMillis) {
        var entries = ledger.get(key);
        if (entries == null || entries.isEmpty()) return Optional.empty();
        var entry = entries.floorEntry(timestampEpochMillis);
        if (entry == null) return Optional.empty();
        return Optional.of(entry.getValue().value().clone());
    }

    /** Retorna snapshot de múltiplas chaves no timestamp dado. */
    public Map<String, byte[]> snapshotAt(Set<String> keys, long timestampEpochMillis) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String key : keys) {
            snapshotAt(key, timestampEpochMillis).ifPresent(v -> result.put(key, v));
        }
        return result;
    }

    /** Remove entradas mais antigas que o período de retenção. */
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        ledger.values().forEach(entries -> {
            var head = entries.headMap(cutoff, false);
            head.clear();
        });
        ledger.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public int size() {
        return ledger.values().stream().mapToInt(Map::size).sum();
    }
}
