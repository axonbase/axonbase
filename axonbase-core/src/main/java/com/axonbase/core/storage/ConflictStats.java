package com.axonbase.core.storage;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Conflitos de versão do commit otimista, no total e por tabela (o AxonBase não tem locks: a disputa aparece aqui). */
public final class ConflictStats {

    private static final int MAX_LABELS = 200;
    private static final LongAdder TOTAL = new LongAdder();
    private static final Map<String, LongAdder> BY_LABEL = new ConcurrentHashMap<>();

    private ConflictStats() {}

    /** Registra um conflito sobre a chave dada. */
    public static void record(String key) {
        TOTAL.increment();
        String label = label(key);
        LongAdder adder = BY_LABEL.get(label);
        if (adder == null) {
            if (BY_LABEL.size() >= MAX_LABELS) {
                label = "other";
            }
            adder = BY_LABEL.computeIfAbsent(label, k -> new LongAdder());
        }
        adder.increment();
    }

    public static long total() {
        return TOTAL.sum();
    }

    public record Entry(String target, long count) {}

    /** Conflitos por tabela, do maior para o menor. */
    public static List<Entry> byTarget() {
        return BY_LABEL.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue().sum()))
            .sorted(Comparator.comparingLong(Entry::count).reversed().thenComparing(Entry::target)).toList();
    }

    /**
     * Registros têm a forma {@code ns\0db\0tabela\0id}; índices usam {@code PREFIXO|ns|db|tabela|...}. Qualquer outra chave
     * vira "other". O resultado é {@code ns/db/tabela}.
     */
    static String label(String key) {
        if (key == null) {
            return "other";
        }
        String[] parts = key.indexOf('\u0000') >= 0 ? key.split("\u0000", 4) : key.split("\\|", 5);
        if (key.indexOf('\u0000') >= 0 && parts.length >= 3) {
            return parts[0] + "/" + parts[1] + "/" + parts[2];
        }
        if (parts.length >= 4 && key.indexOf('|') > 0) {
            return parts[1] + "/" + parts[2] + "/" + parts[3];
        }
        return "other";
    }

    /** Só para testes. */
    static void reset() {
        TOTAL.reset();
        BY_LABEL.clear();
    }
}
