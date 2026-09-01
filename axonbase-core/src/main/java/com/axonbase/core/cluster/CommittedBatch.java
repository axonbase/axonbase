package com.axonbase.core.cluster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Mutação lógica idempotente, replicada como uma única entrada de log. */
public record CommittedBatch(String transactionId, Map<String, byte[]> puts, Set<String> deletes,
                             boolean fullSnapshot) {
    public CommittedBatch {
        Map<String, byte[]> copied = new LinkedHashMap<>();
        puts.forEach((key, value) -> copied.put(key, value.clone()));
        puts = Map.copyOf(copied);
        deletes = Set.copyOf(deletes);
    }

    /** Batch comum (AppendEntries). */
    public CommittedBatch(String transactionId, Map<String, byte[]> puts, Set<String> deletes) {
        this(transactionId, puts, deletes, false);
    }
}