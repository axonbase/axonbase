package com.axonbase.core.cluster;

import com.axonbase.core.hlc.HlcTimestamp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mutação lógica idempotente, replicada como uma única entrada de log.
 *
 * <p>{@code stamp} é o carimbo do relógio híbrido (HLC) posto por quem propõe o lote: o líder no cluster, o
 * próprio commit no nó único. {@link HlcTimestamp#NONE} marca um lote sem carimbo (versões anteriores à 0.11).
 * Quem copiar um lote precisa preservar o carimbo.</p>
 */
public record CommittedBatch(String transactionId, Map<String, byte[]> puts, Set<String> deletes,
                             boolean fullSnapshot, HlcTimestamp stamp) {
    public CommittedBatch {
        Map<String, byte[]> copied = new LinkedHashMap<>();
        puts.forEach((key, value) -> copied.put(key, value.clone()));
        puts = Map.copyOf(copied);
        deletes = Set.copyOf(deletes);
        stamp = stamp == null ? HlcTimestamp.NONE : stamp;
    }

    /** Batch comum (AppendEntries), ainda sem carimbo. */
    public CommittedBatch(String transactionId, Map<String, byte[]> puts, Set<String> deletes) {
        this(transactionId, puts, deletes, false, HlcTimestamp.NONE);
    }

    public CommittedBatch(String transactionId, Map<String, byte[]> puts, Set<String> deletes,
                          boolean fullSnapshot) {
        this(transactionId, puts, deletes, fullSnapshot, HlcTimestamp.NONE);
    }

    /** O mesmo lote com o carimbo dado. */
    public CommittedBatch withStamp(HlcTimestamp newStamp) {
        return new CommittedBatch(transactionId, puts, deletes, fullSnapshot, newStamp);
    }
}
