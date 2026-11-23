package com.axonbase.server;

import java.util.Map;

/**
 * Parâmetros do registro de atividade, lidos do ambiente: {@code AXON_SLOW_QUERY_MS} (padrão 1000),
 * {@code AXON_ACTIVITY_CAPTURE_SQL} (padrão true; false guarda só o fingerprint) e {@code AXON_ACTIVITY_SLOW_BUFFER}
 * (padrão 500 consultas lentas em memória).
 */
public record ActivityConfig(long slowQueryMs, boolean captureSql, int slowBuffer) {

    public static final int MAX_SQL_CHARS = 4096;

    public static ActivityConfig defaults() {
        return new ActivityConfig(1000L, true, 500);
    }

    public static ActivityConfig from(Map<String, String> env) {
        ActivityConfig d = defaults();
        return new ActivityConfig(
            positive(env.get("AXON_SLOW_QUERY_MS"), d.slowQueryMs()),
            !"false".equalsIgnoreCase(env.getOrDefault("AXON_ACTIVITY_CAPTURE_SQL", "true").trim()),
            (int) Math.min(10_000, positive(env.get("AXON_ACTIVITY_SLOW_BUFFER"), d.slowBuffer())));
    }

    private static long positive(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
