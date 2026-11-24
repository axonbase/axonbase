package com.axonbase.server;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.time.Instant;
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
    /** Anel sem trava: cada escrita reserva uma posição com um contador atômico (várias threads por requisição). */
    private final java.util.concurrent.atomic.AtomicReferenceArray<Entry> ring;
    private final java.util.concurrent.atomic.AtomicLong written = new java.util.concurrent.atomic.AtomicLong();
    private final Set<Consumer<Entry>> subscribers = new CopyOnWriteArraySet<>();

    public LogBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.ring = new java.util.concurrent.atomic.AtomicReferenceArray<>(this.capacity);
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
        long slot = written.getAndIncrement();
        ring.set((int) (slot % capacity), entry);
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
        List<Entry> all = ordered();
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
        return (int) Math.min(written.get(), capacity);
    }

    /** Linhas do anel da mais antiga para a mais nova (posição ainda não preenchida por uma escrita em curso é pulada). */
    private List<Entry> ordered() {
        long end = written.get();
        long start = Math.max(0, end - capacity);
        List<Entry> all = new ArrayList<>((int) (end - start));
        for (long i = start; i < end; i++) {
            Entry e = ring.get((int) (i % capacity));
            if (e != null) {
                all.add(e);
            }
        }
        return all;
    }

    public int capacity() {
        return capacity;
    }

    public AutoCloseable subscribe(Consumer<Entry> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }
}
