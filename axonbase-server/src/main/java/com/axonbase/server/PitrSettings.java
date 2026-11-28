package com.axonbase.server;

import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.storage.RocksDbBackend;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

/**
 * Configuração do PITR (Point-In-Time Recovery), toda opcional:
 *
 * <ul>
 * <li>{@code AXON_PITR} ({@code on} ou {@code true}) liga o journal;</li>
 * <li>{@code AXON_PITR_DIR}, o diretório dos segmentos (padrão {@code <dados>/.pitr-journal}, que a troca de base
 *     de um restore não move);</li>
 * <li>{@code AXON_PITR_RETENTION_DAYS}, de 1 a 35 (padrão 7);</li>
 * <li>{@code AXON_PITR_SEGMENT_SECONDS}, idade máxima de um segmento, de 1 a 30 (padrão 25).</li>
 * </ul>
 *
 * @param directory diretório configurado, ou {@code null} para o padrão ao lado dos dados
 */
record PitrSettings(boolean enabled, Path directory, JournalOptions options) {

    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int MAX_RETENTION_DAYS = 35;
    private static final int MAX_SEGMENT_SECONDS = 30;

    static PitrSettings fromEnvironment() {
        return from(name -> {
            String value = System.getenv(name);
            return value == null || value.isBlank()
                ? System.getProperty(name.toLowerCase(java.util.Locale.ROOT).replace('_', '.')) : value;
        });
    }

    static PitrSettings defaults() {
        return from(name -> null);
    }

    static PitrSettings from(Function<String, String> env) {
        String flag = env.apply("AXON_PITR");
        boolean enabled = flag != null && (flag.trim().equalsIgnoreCase("on") || flag.trim().equalsIgnoreCase("true"));
        String dir = env.apply("AXON_PITR_DIR");
        JournalOptions defaults = JournalOptions.defaults();
        int days = clamped(env.apply("AXON_PITR_RETENTION_DAYS"), DEFAULT_RETENTION_DAYS, MAX_RETENTION_DAYS);
        int seconds = clamped(env.apply("AXON_PITR_SEGMENT_SECONDS"),
            (int) defaults.maxSegmentAge().toSeconds(), MAX_SEGMENT_SECONDS);
        return new PitrSettings(enabled, dir == null || dir.isBlank() ? null : Path.of(dir.trim()),
            defaults.withRetention(Duration.ofDays(days)).withMaxSegmentAge(Duration.ofSeconds(seconds)));
    }

    /**
     * O diretório do journal para este armazenamento.
     *
     * @throws IllegalArgumentException quando o diretório configurado fica dentro dos dados sem começar com ponto: a
     *                                  troca de base de um restore o moveria embora
     */
    Path directoryFor(RocksDbBackend rocks) {
        if (directory == null) {
            return rocks.directory().resolve(".pitr-journal");
        }
        Path data = rocks.directory().toAbsolutePath().normalize();
        Path chosen = directory.toAbsolutePath().normalize();
        if (chosen.startsWith(data)
            && (chosen.equals(data) || !data.relativize(chosen).getName(0).toString().startsWith("."))) {
            throw new IllegalArgumentException("AXON_PITR_DIR dentro do diretório de dados precisa começar com ponto"
                + " (por exemplo " + data.resolve(".pitr-journal") + "): " + chosen);
        }
        return chosen;
    }

    private static int clamped(String raw, int fallback, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
