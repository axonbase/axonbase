package com.axonbase.server;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Log estruturado: uma linha JSON por evento em stdout, com os campos do contrato
 * ({@code time, level, event, msg, request_id, user, ns, db, duration_ms, error}).
 */
public final class AxonLog {

    private static final int DEFAULT_BUFFER = 10_000;
    private static volatile LogBuffer buffer = new LogBuffer(envCapacity());

    private AxonLog() {
    }

    /** Capacidade do buffer em memória: {@code AXON_LOG_BUFFER}, padrão 10000. */
    static int envCapacity() {
        String v = System.getenv("AXON_LOG_BUFFER");
        if (v != null && !v.isBlank()) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n > 0) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // valor inválido: usa o padrão
            }
        }
        return DEFAULT_BUFFER;
    }

    /** Buffer circular das últimas linhas registradas. */
    public static LogBuffer buffer() {
        return buffer;
    }

    /** Troca o buffer (testes e configuração). */
    static void buffer(LogBuffer replacement) {
        buffer = replacement;
    }

    /** Monta a linha JSON (sem imprimir). Campos nulos ou vazios ficam de fora. */
    public static String json(String level, String event, String msg) {
        return json(level, event, msg, Map.of());
    }

    public static String json(String level, String event, String msg, Map<String, ?> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", Instant.now().toString());
        m.put("level", level);
        m.put("event", event);
        if (msg != null && !msg.isEmpty()) {
            m.put("msg", msg);
        }
        extra.forEach((k, v) -> {
            if (v != null && !String.valueOf(v).isEmpty()) {
                m.put(k, v);
            }
        });
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** Escreve uma linha de log em stdout. */
    public static void log(String level, String event, String msg, Map<String, ?> extra) {
        System.out.println(json(level, event, msg, extra));
        Map<String, Object> fields = new LinkedHashMap<>();
        extra.forEach((k, v) -> {
            if (v != null && !String.valueOf(v).isEmpty()) {
                fields.put(k, v);
            }
        });
        buffer.add(new LogBuffer.Entry(System.currentTimeMillis(), level, event, msg, fields));
    }

    public static void log(String level, String event, String msg) {
        log(level, event, msg, Map.of());
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
