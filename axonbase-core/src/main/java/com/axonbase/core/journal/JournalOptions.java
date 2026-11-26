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
 * @param syncInterval    o segmento aberto é sincronizado em disco neste intervalo: é o que uma queda de energia
 *                        pode perder dele
 * @param syncEveryBytes  além do intervalo, sincroniza a cada tantos bytes escritos ({@code 0} desliga), para o disco
 *                        receber rajadas menores
 * @param writerPause     quanto a thread de escrita dorme quando a fila esvazia
 */
public record JournalOptions(Duration maxSegmentAge, long maxSegmentBytes, Duration idleHeartbeat,
                             int queueMaxBatches, long queueMaxBytes, Duration retention, Duration syncInterval,
                             long syncEveryBytes, Duration writerPause) {

    private static final Duration DEFAULT_SYNC_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_WRITER_PAUSE = Duration.ofMillis(10);

    /** Com a sincronização padrão (a cada segundo) e a pausa padrão da thread de escrita. */
    public JournalOptions(Duration maxSegmentAge, long maxSegmentBytes, Duration idleHeartbeat, int queueMaxBatches,
                          long queueMaxBytes, Duration retention) {
        this(maxSegmentAge, maxSegmentBytes, idleHeartbeat, queueMaxBatches, queueMaxBytes, retention,
            DEFAULT_SYNC_INTERVAL, 0L, DEFAULT_WRITER_PAUSE);
    }

    /**
     * Fecha em 25 s, dentro do limite de 30 s do plano. A fila usa no máximo um oitavo do heap, até 128 MB.
     */
    public static JournalOptions defaults() {
        long queueBytes = Math.min(128L << 20, Runtime.getRuntime().maxMemory() / 8);
        return new JournalOptions(Duration.ofSeconds(25), 64L << 20, Duration.ofSeconds(60), 50_000, queueBytes,
            Duration.ofDays(7));
    }

    public JournalOptions withMaxSegmentAge(Duration value) {
        return new JournalOptions(value, maxSegmentBytes, idleHeartbeat, queueMaxBatches, queueMaxBytes, retention,
            syncInterval, syncEveryBytes, writerPause);
    }

    public JournalOptions withMaxSegmentBytes(long value) {
        return new JournalOptions(maxSegmentAge, value, idleHeartbeat, queueMaxBatches, queueMaxBytes, retention,
            syncInterval, syncEveryBytes, writerPause);
    }

    public JournalOptions withIdleHeartbeat(Duration value) {
        return new JournalOptions(maxSegmentAge, maxSegmentBytes, value, queueMaxBatches, queueMaxBytes, retention,
            syncInterval, syncEveryBytes, writerPause);
    }

    public JournalOptions withQueueLimits(int batches, long bytes) {
        return new JournalOptions(maxSegmentAge, maxSegmentBytes, idleHeartbeat, batches, bytes, retention,
            syncInterval, syncEveryBytes, writerPause);
    }

    public JournalOptions withRetention(Duration value) {
        return new JournalOptions(maxSegmentAge, maxSegmentBytes, idleHeartbeat, queueMaxBatches, queueMaxBytes,
            value, syncInterval, syncEveryBytes, writerPause);
    }

    public JournalOptions withSync(Duration interval, long everyBytes) {
        return new JournalOptions(maxSegmentAge, maxSegmentBytes, idleHeartbeat, queueMaxBatches, queueMaxBytes,
            retention, interval, everyBytes, writerPause);
    }

    public JournalOptions withWriterPause(Duration value) {
        return new JournalOptions(maxSegmentAge, maxSegmentBytes, idleHeartbeat, queueMaxBatches, queueMaxBytes,
            retention, syncInterval, syncEveryBytes, value);
    }
}
