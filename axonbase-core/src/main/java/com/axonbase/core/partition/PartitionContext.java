package com.axonbase.core.partition;

import java.util.HashMap;
import java.util.Map;

/**
 * O que um executor sabe sobre as partições durante a execução: para onde vai cada registro que está sendo escrito,
 * onde cada um foi achado e qual faixa de tempo a consulta em curso pode alcançar. Vale para uma thread; as varreduras
 * paralelas só leem.
 */
public final class PartitionContext {

    /** Entradas guardadas por mapa; passado isso o mapa recomeça (são só atalhos e destinos do statement). */
    private static final int REMEMBERED = 100_000;

    private final Map<String, Long> targets = new HashMap<>();
    private final Map<String, Long> located = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> fresh = new java.util.HashSet<>();
    private volatile Map<String, TimeRange> ranges = Map.of();

    /** O que desfaz a poda de uma consulta quando ela termina. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** Restringe as varreduras da tabela (chave {@code ns|db|tabela}) até o escopo fechar. */
    public Scope prune(String table, TimeRange range) {
        Map<String, TimeRange> previous = ranges;
        Map<String, TimeRange> next = new HashMap<>(previous);
        next.put(table, range);
        ranges = Map.copyOf(next);
        return () -> ranges = previous;
    }

    TimeRange range(String table) {
        return ranges.getOrDefault(table, TimeRange.ALL);
    }

    void target(String record, long start, boolean generated) {
        if (targets.size() >= REMEMBERED) {
            targets.clear();
            fresh.clear();
        }
        targets.put(record, start);
        if (generated) {
            fresh.add(record);
        }
    }

    Long target(String record) {
        return targets.get(record);
    }

    /** {@code true} uma única vez para o registro cujo id o motor acabou de gerar. */
    boolean takeFresh(String record) {
        return fresh.remove(record);
    }

    void located(String record, long start) {
        if (located.size() >= REMEMBERED) {
            located.clear();
        }
        located.put(record, start);
    }

    Long located(String record) {
        return located.get(record);
    }

    void forget(String record) {
        located.remove(record);
    }
}
