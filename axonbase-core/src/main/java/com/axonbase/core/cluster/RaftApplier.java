package com.axonbase.core.cluster;

import com.axonbase.core.storage.VersionedKvBackend;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aplica uma entrada Raft confirmada ao storage como batch atômico e avisa o
 * listener depois que os bytes já estão visíveis.
 */
public final class RaftApplier {

    private RaftApplier() {
    }

    public static void apply(VersionedKvBackend backend, CommittedBatch batch) {
        apply(backend, batch, AppliedBatchListener.none());
    }

    public static void apply(VersionedKvBackend backend, CommittedBatch batch,
                             AppliedBatchListener listener) {
        boolean observed = listener != null && listener != AppliedBatchListener.NONE;
        Map<String, byte[]> previous = observed ? snapshotOf(backend, batch) : Map.of();
        backend.commit(Map.of(), batch.puts(), batch.deletes());
        backend.flush();
        if (listener != null) {
            listener.onApplied(new AppliedBatchListener.AppliedBatch(batch, previous));
        }
    }

    /** Lê o estado anterior das chaves tocadas, antes de sobrescrevê-las. */
    private static Map<String, byte[]> snapshotOf(VersionedKvBackend backend, CommittedBatch batch) {
        Map<String, byte[]> previous = new LinkedHashMap<>();
        batch.puts().keySet().forEach(key -> backend.get(key).ifPresent(v -> previous.put(key, v)));
        batch.deletes().forEach(key -> backend.get(key).ifPresent(v -> previous.put(key, v)));
        return previous;
    }
}
