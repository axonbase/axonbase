package com.axonbase.core.journal;

import java.time.Duration;

/**
 * Limites do journal de PITR (Point-In-Time Recovery).
 *
 * @param maxSegmentAge   um segmento aberto fecha ao chegar a esta idade
 * @param maxSegmentBytes ou a este tamanho
 * @param idleHeartbeat   parado, grava um segmento vazio com a marca d'água neste intervalo ({@code ZERO} desliga)
 * @param queueMaxBatches lotes na fila de escrita; além disso o lote é descartado (o commit nunca espera)
 * @param queueMaxBytes   bytes na fila de escrita
 * @param retention       segmentos mais velhos que isto são apagados ({@code ZERO} guarda tudo)
 */
public record JournalOptions(Duration maxSegmentAge, long maxSegmentBytes, Duration idleHeartbeat,
                             int queueMaxBatches, long queueMaxBytes, Duration retention) {

    /**
     * Fecha em 25 s, dentro do limite de 30 s do plano. A fila usa no máximo um oitavo do heap, até 128 MB.
     */
    public static JournalOptions defaults() {
        long queueBytes = Math.min(128L << 20, Runtime.getRuntime().maxMemory() / 8);
        return new JournalOptions(Duration.ofSeconds(25), 64L << 20, Duration.ofSeconds(60), 50_000, queueBytes,
            Duration.ofDays(7));
    }

    public JournalOptions withMaxSegmentAge(Duration value) {
        return new JournalOptions(value, maxSegmentBytes, idleHeartbeat, queueMaxBatches, queueMaxBytes, retention);
    }

    public JournalOptions withMaxSegmentBytes(long value) {
        return new JournalOptions(maxSegmentAge, value, idleHeartbeat, queueMaxBatches, queueMaxBytes, retention);
    }

    public JournalOptions withIdleHeartbeat(Duration value) {
        return new JournalOptions(maxSegmentAge, maxSegmentBytes, value, queueMaxBatches, queueMaxBytes, retention);
    }

    public JournalOptions withQueueLimits(int batches, long bytes) {
        return new JournalOptions(maxSegmentAge, maxSegmentBytes, idleHeartbeat, batches, bytes, retention);
    }

    public JournalOptions withRetention(Duration value) {
        return new JournalOptions(maxSegmentAge, maxSegmentBytes, idleHeartbeat, queueMaxBatches, queueMaxBytes,
            value);
    }
}
