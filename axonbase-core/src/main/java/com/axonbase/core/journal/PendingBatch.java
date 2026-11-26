package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;

import java.util.Map;
import java.util.Set;

/**
 * Um lote à espera de escrita: a menor cópia que ainda deixa o chamador reaproveitar os mapas e os vetores dele. Feita
 * dentro do commit, então só duplica os valores e guarda as chaves (imutáveis) em vetores, sem montar mapas nem
 * serializar nada. Os vetores deste registro não saem do journal e ninguém os altera.
 */
record PendingBatch(long index, HlcTimestamp stamp, String[] putKeys, byte[][] values, String[] deletes, long bytes) {

    private static final long OVERHEAD_BYTES = 64L;

    /** Quanto o lote ocupa na fila, aproximadamente, sem copiar nada. */
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

    static PendingBatch copyOf(long index, HlcTimestamp stamp, Map<String, byte[]> puts, Set<String> deletes,
                               long bytes) {
        String[] keys = new String[puts.size()];
        byte[][] values = new byte[keys.length][];
        int i = 0;
        for (var put : puts.entrySet()) {
            keys[i] = put.getKey();
            values[i++] = put.getValue().clone();
        }
        return new PendingBatch(index, stamp, keys, values, deletes.toArray(new String[0]), bytes);
    }
}
