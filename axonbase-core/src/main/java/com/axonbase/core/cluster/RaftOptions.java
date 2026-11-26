package com.axonbase.core.cluster;

import com.axonbase.core.storage.RocksDbBackend.SyncMode;

import java.util.concurrent.TimeUnit;

/**
 * Ajustes do consenso. Nada é obrigatório: {@link #defaults()} lê {@code AXON_STORAGE_SYNC},
 * {@code AXON_RAFT_RETAIN_ENTRIES}, {@code AXON_RAFT_RETAIN_MINUTES} e {@code AXON_RAFT_MAX_READ_LAG_MS}.
 *
 * @param sync                  quando o log Raft vai ao disco; o mesmo significado do armazenamento
 * @param segmentBytes          tamanho em que um segmento do log é fechado
 * @param retainEntries         entradas já aplicadas mantidas no log, para seguidores e learners atrasados
 * @param commitTimeoutMillis   espera máxima pelo quórum antes de desistir de uma escrita
 * @param maxEntriesPerRequest  entradas agrupadas num AppendEntries
 * @param maxBytesPerRequest    bytes agrupados num AppendEntries (uma entrada maior vai sozinha)
 * @param maxInflightRequests   requisições em voo por seguidor (pipeline)
 * @param maxReadLagMillis      defasagem máxima aceita numa leitura sem consistência pedida; 0 não limita
 * @param retainMillis          idade mínima de um segmento para sair do log, para que um backup recente mais o
 *                              log bastem para um learner entrar; 0 não segura por tempo
 */
public record RaftOptions(SyncMode sync, long segmentBytes, long retainEntries, long commitTimeoutMillis,
                          int maxEntriesPerRequest, int maxBytesPerRequest, int maxInflightRequests,
                          long maxReadLagMillis, long retainMillis) {

    private static final long DEFAULT_RETAIN_ENTRIES = 100_000;
    private static final long DEFAULT_COMMIT_TIMEOUT_MILLIS = 10_000;
    private static final int DEFAULT_ENTRIES_PER_REQUEST = 512;
    private static final int DEFAULT_BYTES_PER_REQUEST = 4 << 20;
    private static final int DEFAULT_INFLIGHT = 32;
    private static final long DEFAULT_RETAIN_MINUTES = 30;

    public static RaftOptions defaults() {
        return new RaftOptions(SyncMode.fromEnvironment(), FileRaftLog.DEFAULT_SEGMENT_BYTES,
            fromEnvironment("AXON_RAFT_RETAIN_ENTRIES", DEFAULT_RETAIN_ENTRIES), DEFAULT_COMMIT_TIMEOUT_MILLIS,
            DEFAULT_ENTRIES_PER_REQUEST, DEFAULT_BYTES_PER_REQUEST, DEFAULT_INFLIGHT,
            fromEnvironment("AXON_RAFT_MAX_READ_LAG_MS", 0),
            TimeUnit.MINUTES.toMillis(fromEnvironment("AXON_RAFT_RETAIN_MINUTES", DEFAULT_RETAIN_MINUTES)));
    }

    private static long fromEnvironment(String name, long fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException invalid) {
            // cai no erro abaixo
        }
        throw new IllegalArgumentException(name + " precisa ser um inteiro maior ou igual a zero: " + raw);
    }

    public RaftOptions withSync(SyncMode mode) {
        return new RaftOptions(mode, segmentBytes, retainEntries, commitTimeoutMillis, maxEntriesPerRequest,
            maxBytesPerRequest, maxInflightRequests, maxReadLagMillis, retainMillis);
    }

    public RaftOptions withSegmentBytes(long bytes) {
        return new RaftOptions(sync, bytes, retainEntries, commitTimeoutMillis, maxEntriesPerRequest,
            maxBytesPerRequest, maxInflightRequests, maxReadLagMillis, retainMillis);
    }

    public RaftOptions withRetainEntries(long entries) {
        return new RaftOptions(sync, segmentBytes, entries, commitTimeoutMillis, maxEntriesPerRequest,
            maxBytesPerRequest, maxInflightRequests, maxReadLagMillis, retainMillis);
    }

    public RaftOptions withCommitTimeoutMillis(long millis) {
        return new RaftOptions(sync, segmentBytes, retainEntries, millis, maxEntriesPerRequest,
            maxBytesPerRequest, maxInflightRequests, maxReadLagMillis, retainMillis);
    }

    public RaftOptions withMaxReadLagMillis(long millis) {
        return new RaftOptions(sync, segmentBytes, retainEntries, commitTimeoutMillis, maxEntriesPerRequest,
            maxBytesPerRequest, maxInflightRequests, millis, retainMillis);
    }

    public RaftOptions withRetainMillis(long millis) {
        return new RaftOptions(sync, segmentBytes, retainEntries, commitTimeoutMillis, maxEntriesPerRequest,
            maxBytesPerRequest, maxInflightRequests, maxReadLagMillis, millis);
    }
}
