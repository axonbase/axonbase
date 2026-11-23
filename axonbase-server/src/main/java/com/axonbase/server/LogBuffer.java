package com.axonbase.server;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Buffer circular em memória com as últimas linhas do {@link AxonLog}. Quando enche, a linha mais
 * antiga é descartada. Assinantes (SSE) recebem cada linha nova.
 */
public final class LogBuffer {

    /** Uma linha de log estruturada. */
    public record Entry(long tsMillis, String level, String event, String msg, Map<String, Object> fields) {

        /** JSON no formato do contrato: {@code {ts, level, event, msg, fields}}. */
        public AxonValue toValue() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", Instant.ofEpochMilli(tsMillis).toString());
            m.put("level", level);
            m.put("event", event);
            m.put("msg", msg == null ? "" : msg);
            m.put("fields", fields);
            return AdminJson.of(m);
        }

        public String toJson() {
            return AxonJson.write(toValue());
        }
    }

    private final int capacity;
    private final ArrayDeque<Entry> ring;
    private final Set<Consumer<Entry>> subscribers = new CopyOnWriteArraySet<>();

    public LogBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.ring = new ArrayDeque<>(Math.min(this.capacity, 1024));
    }

    /** Severidade numérica para filtrar por nível mínimo; níveis desconhecidos contam como info. */
    static int severity(String level) {
        if (level == null) {
            return 1;
        }
        return switch (level.toLowerCase(java.util.Locale.ROOT)) {
            case "debug", "trace" -> 0;
            case "warn", "warning" -> 2;
            case "error", "fatal" -> 3;
            default -> 1;
        };
    }

    public void add(Entry entry) {
        synchronized (ring) {
            if (ring.size() >= capacity) {
                ring.pollFirst();
            }
            ring.addLast(entry);
        }
        for (Consumer<Entry> s : subscribers) {
            try {
                s.accept(entry);
            } catch (RuntimeException e) {
                subscribers.remove(s);
            }
        }
    }

    /**
     * Últimas linhas em ordem cronológica.
     *
     * @param limit    máximo de linhas devolvidas (as mais recentes)
     * @param minLevel nível mínimo ({@code null} para todos)
     * @param since    só linhas com {@code ts >= since} (0 para todas)
     */
    public List<Entry> snapshot(int limit, String minLevel, long since) {
        int min = minLevel == null || minLevel.isBlank() ? -1 : severity(minLevel);
        List<Entry> all;
        synchronized (ring) {
            all = new ArrayList<>(ring);
        }
        List<Entry> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            Entry e = all.get(i);
            if (e.tsMillis() < since || severity(e.level()) < min) {
                continue;
            }
            out.add(e);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public int size() {
        synchronized (ring) {
            return ring.size();
        }
    }

    public int capacity() {
        return capacity;
    }

    public AutoCloseable subscribe(Consumer<Entry> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }
}
