package com.axonbase.core.qos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QoS: configuração")
class QosSettingsTest {

    private static QosSettings from(Map<String, String> env, int cores, int limit) {
        return QosSettings.from(env::get, cores, limit);
    }

    @Test
    @DisplayName("sem variáveis o QoS fica desligado")
    void offByDefault() {
        QosSettings settings = from(Map.of(), 16, 112);

        assertEquals(QosSettings.Mode.OFF, settings.mode());
        assertFalse(settings.enabled());
        assertFalse(QosSettings.off().enabled());
    }

    @Test
    @DisplayName("padrões derivam dos núcleos")
    void defaultsFollowCores() {
        QosSettings settings = from(Map.of("AXON_QOS", "auto"), 16, 112);

        assertTrue(settings.enabled());
        assertEquals(4, settings.concurrency());
        assertEquals(16, settings.queue());
        assertEquals(5_000, settings.queueTimeoutMillis());
        assertEquals(50, settings.cpuPercent());
        assertEquals(0, settings.timeoutMillis());
        assertEquals(10_000, settings.scanThreshold());
        assertFalse(settings.trustClient());
    }

    @Test
    @DisplayName("lê todas as variáveis")
    void readsEveryVariable() {
        QosSettings settings = from(Map.of("AXON_QOS", "explicit", "AXON_QOS_ANALYTICAL_CONCURRENCY", "3",
            "AXON_QOS_ANALYTICAL_QUEUE", "7", "AXON_QOS_ANALYTICAL_QUEUE_TIMEOUT_MS", "100",
            "AXON_QOS_ANALYTICAL_CPU_PERCENT", "25", "AXON_QOS_ANALYTICAL_TIMEOUT_MS", "900",
            "AXON_QOS_SCAN_THRESHOLD", "50", "AXON_QOS_ANALYTICAL_USERS", "bi, relatorio",
            "AXON_QOS_TRUST_CLIENT", "true"), 8, 112);

        assertEquals(QosSettings.Mode.EXPLICIT, settings.mode());
        assertEquals(3, settings.concurrency());
        assertEquals(7, settings.queue());
        assertEquals(100, settings.queueTimeoutMillis());
        assertEquals(25, settings.cpuPercent());
        assertEquals(900, settings.timeoutMillis());
        assertEquals(50, settings.scanThreshold());
        assertEquals(Set.of("bi", "relatorio"), settings.analyticalUsers());
        assertTrue(settings.trustClient());
    }

    @Test
    @DisplayName("vagas mais fila não passam da metade das requisições de dados")
    void slotsAndQueueLeaveRoomForTransactions() {
        QosSettings settings = from(Map.of("AXON_QOS", "auto", "AXON_QOS_ANALYTICAL_CONCURRENCY", "100",
            "AXON_QOS_ANALYTICAL_QUEUE", "100"), 64, 40);

        assertEquals(20, settings.concurrency());
        assertEquals(0, settings.queue());
    }

    @ParameterizedTest
    @CsvSource({
        "AXON_QOS, ligado",
        "AXON_QOS_ANALYTICAL_CONCURRENCY, 0",
        "AXON_QOS_ANALYTICAL_CPU_PERCENT, 101",
        "AXON_QOS_ANALYTICAL_QUEUE, muitas",
        "AXON_QOS_SCAN_THRESHOLD, -1"
    })
    @DisplayName("valor inválido impede a subida")
    void invalidValuesFail(String name, String value) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> from(Map.of(name, value), 8, 112));

        assertTrue(failure.getMessage().contains(name), failure.getMessage());
    }
}
