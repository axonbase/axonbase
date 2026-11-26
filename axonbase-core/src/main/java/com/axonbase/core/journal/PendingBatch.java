package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;

import java.util.Map;
import java.util.Set;

/**
 * Um lote à espera de escrita, já no formato do segmento. É serializado dentro do commit, cada escritor o seu e em
 * paralelo, o que também deixa o chamador reaproveitar os mapas e os vetores. À thread do journal sobra juntar bytes
 * prontos: trabalho dela atrasa os commits muito mais do que o mesmo trabalho repartido entre eles.
 *
 * @param record o registro completo (tamanho, tipo, corpo e CRC); não sai do journal e ninguém o altera
 * @param bytes  o espaço que o lote ocupa na fila, aproximadamente
 */
record PendingBatch(long index, HlcTimestamp stamp, byte[] record, long bytes) {

    private static final long OVERHEAD_BYTES = 64L;

    /** Quanto o lote ocupa na fila, aproximadamente, sem serializar nada. */
    static long sizeOf(Map<String, byte[]> puts, Set<String> deletes) {
        long size = OVERHEAD_BYTES;
        for (var put : puts.entrySet()) {
            size += put.getKey().length() * 2L + put.getValue().length;
        }
        for (String key : deletes) {
            size += key.length() * 2L;
        }
        return size;
    }

    static PendingBatch encode(long index, HlcTimestamp stamp, Map<String, byte[]> puts, Set<String> deletes,
                               long bytes) {
        return new PendingBatch(index, stamp, SegmentFormat.batchRecord(index, stamp, puts, deletes), bytes);
    }
}
