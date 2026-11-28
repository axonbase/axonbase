package com.axonbase.core.cluster;

import java.util.Locale;

/**
 * O que uma leitura exige do nó que a atende.
 *
 * <ul>
 *   <li>{@code stale}: o que o nó tiver;</li>
 *   <li>{@code max-lag=<ms>}: o nó precisa ter estado em dia com o líder há no máximo esse tempo;</li>
 *   <li>{@code linearizable}: a leitura enxerga toda escrita confirmada antes dela (ReadIndex).</li>
 * </ul>
 */
public record ReadConsistency(Mode mode, long maxLagMillis) {

    public enum Mode {
        STALE, BOUNDED, LINEARIZABLE
    }

    public static final ReadConsistency STALE = new ReadConsistency(Mode.STALE, 0);
    public static final ReadConsistency LINEARIZABLE = new ReadConsistency(Mode.LINEARIZABLE, 0);
    private static final String MAX_LAG = "max-lag=";

    public static ReadConsistency maxLag(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("defasagem máxima não pode ser negativa: " + millis);
        }
        return new ReadConsistency(Mode.BOUNDED, millis);
    }

    /** Interpreta o texto pedido por um cliente; vazio ou {@code null} devolve {@code null} (padrão do nó). */
    public static ReadConsistency parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.equals("stale")) {
            return STALE;
        }
        if (text.equals("linearizable")) {
            return LINEARIZABLE;
        }
        if (text.startsWith(MAX_LAG)) {
            try {
                return maxLag(Long.parseLong(text.substring(MAX_LAG.length())));
            } catch (NumberFormatException invalid) {
                // cai no erro abaixo
            }
        }
        throw new IllegalArgumentException("consistência de leitura inválida: " + raw
            + " (use stale, linearizable ou max-lag=<ms>)");
    }
}
