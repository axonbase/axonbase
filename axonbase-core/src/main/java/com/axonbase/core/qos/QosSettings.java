package com.axonbase.core.qos;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Configuração da prioridade por tipo de carga, QoS (Quality of Service). Tudo é opcional e o padrão é desligado
 * (veja {@code docs/operacao-e-resiliencia.md}).
 *
 * @param mode               desligado, só o que é pedido, ou automático
 * @param concurrency        consultas analíticas em execução ao mesmo tempo
 * @param queue              consultas analíticas esperando vaga
 * @param queueTimeoutMillis espera máxima por uma vaga
 * @param cpuPercent         fatia dos núcleos para as analíticas enquanto há trabalho transacional
 * @param timeoutMillis      tempo máximo de execução de uma analítica, 0 sem limite
 * @param scanThreshold      acessos ao armazenamento a partir dos quais uma leitura vira analítica
 * @param analyticalUsers    usuários cujas leituras são sempre analíticas
 * @param trustClient        aceita o cliente se declarar transacional
 * @param cores              núcleos da máquina
 */
public record QosSettings(Mode mode, int concurrency, int queue, long queueTimeoutMillis, int cpuPercent,
                          long timeoutMillis, long scanThreshold, Set<String> analyticalUsers, boolean trustClient,
                          int cores) {

    public enum Mode {
        OFF, EXPLICIT, AUTO
    }

    private static final long DEFAULT_QUEUE_TIMEOUT_MILLIS = 5_000;
    private static final int DEFAULT_CPU_PERCENT = 50;
    private static final long DEFAULT_SCAN_THRESHOLD = 10_000;
    private static final int QUEUE_PER_SLOT = 4;

    public QosSettings {
        analyticalUsers = Set.copyOf(analyticalUsers);
    }

    public boolean enabled() {
        return mode != Mode.OFF;
    }

    public static QosSettings off() {
        return from(name -> null, 1, Integer.MAX_VALUE);
    }

    /**
     * @param env              leitura das variáveis {@code AXON_QOS*}
     * @param cores            núcleos da máquina
     * @param dataRequestLimit requisições de dados que o servidor HTTP atende ao mesmo tempo: vagas e fila analíticas
     *                         não passam da metade disso
     * @throws IllegalArgumentException para um valor que não se entende, para o servidor não subir com um limite
     *                                  diferente do pedido
     */
    public static QosSettings from(Function<String, String> env, int cores, int dataRequestLimit) {
        Mode mode = mode(env.apply("AXON_QOS"));
        int ceiling = Math.max(2, dataRequestLimit / 2);
        int concurrency = (int) Math.min(ceiling,
            number(env, "AXON_QOS_ANALYTICAL_CONCURRENCY", Math.max(1, cores / 4), 1));
        int queue = (int) Math.min(ceiling - concurrency,
            number(env, "AXON_QOS_ANALYTICAL_QUEUE", (long) concurrency * QUEUE_PER_SLOT, 0));
        long cpu = number(env, "AXON_QOS_ANALYTICAL_CPU_PERCENT", DEFAULT_CPU_PERCENT, 1);
        if (cpu > 100) {
            throw invalid("AXON_QOS_ANALYTICAL_CPU_PERCENT", String.valueOf(cpu));
        }
        return new QosSettings(mode, concurrency, Math.max(0, queue),
            number(env, "AXON_QOS_ANALYTICAL_QUEUE_TIMEOUT_MS", DEFAULT_QUEUE_TIMEOUT_MILLIS, 0), (int) cpu,
            number(env, "AXON_QOS_ANALYTICAL_TIMEOUT_MS", 0, 0),
            number(env, "AXON_QOS_SCAN_THRESHOLD", DEFAULT_SCAN_THRESHOLD, 1),
            users(env.apply("AXON_QOS_ANALYTICAL_USERS")), flag(env, "AXON_QOS_TRUST_CLIENT"), Math.max(1, cores));
    }

    private static Mode mode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mode.OFF;
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw invalid("AXON_QOS", raw);
        }
    }

    private static long number(Function<String, String> env, String name, long fallback, long minimum) {
        String raw = env.apply(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(name, raw);
        }
        if (value < minimum) {
            throw invalid(name, raw);
        }
        return value;
    }

    private static boolean flag(Function<String, String> env, String name) {
        String raw = env.apply(name);
        return raw != null && (raw.trim().equalsIgnoreCase("true") || raw.trim().equalsIgnoreCase("on"));
    }

    private static Set<String> users(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(u -> !u.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    private static IllegalArgumentException invalid(String name, String value) {
        return new IllegalArgumentException("valor inválido em " + name + ": " + value);
    }
}
