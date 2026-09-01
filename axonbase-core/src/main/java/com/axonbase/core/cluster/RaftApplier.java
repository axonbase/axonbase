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
        AppliedBatchListener.AppliedBatch applied = applyAndCapture(backend, batch);
        if (listener != null) {
            listener.onApplied(applied);
        }
    }

    /** Aplica o batch e devolve o evento para despacho posterior, fora de locks Raft. */
    public static AppliedBatchListener.AppliedBatch applyAndCapture(VersionedKvBackend backend,
                                                                     CommittedBatch batch) {
        Map<String, byte[]> previous = snapshotOf(backend, batch);
        // Um snapshot é autoritativo: substitui o estado por inteiro para que chaves
        // órfãs presentes no seguidor (e ausentes no líder) sejam removidas.
        if (batch.fullSnapshot()) {
            for (String key : backend.keysWithPrefix("")) {
                backend.delete(key);
            }
        }
        backend.commit(Map.of(), batch.puts(), batch.deletes());
        backend.flush();
        return new AppliedBatchListener.AppliedBatch(batch, previous);
    }

    /** Lê o estado anterior das chaves tocadas, antes de sobrescrevê-las. */
    private static Map<String, byte[]> snapshotOf(VersionedKvBackend backend, CommittedBatch batch) {
        Map<String, byte[]> previous = new LinkedHashMap<>();
        batch.puts().keySet().forEach(key -> backend.get(key).ifPresent(v -> previous.put(key, v)));
        batch.deletes().forEach(key -> backend.get(key).ifPresent(v -> previous.put(key, v)));
        return previous;
    }
}
