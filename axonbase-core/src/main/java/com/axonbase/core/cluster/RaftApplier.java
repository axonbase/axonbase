package com.axonbase.core.cluster;

import com.axonbase.core.storage.VersionedKvBackend;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Aplica uma entrada Raft confirmada ao storage como batch atômico e avisa o
 * listener depois que os bytes já estão visíveis.
 */
public final class RaftApplier {

    /**
     * Chave que guarda, no próprio armazenamento, o índice e o termo da última entrada aplicada. Vai no mesmo
     * lote atômico da entrada, então um backup físico diz exatamente de que ponto do log ele é.
     */
    public static final String APPLIED_KEY = "__raft/applied";

    private RaftApplier() {
    }

    /** Índice e termo da última entrada aplicada. */
    public record Applied(long index, long term) {
    }

    public static Optional<Applied> applied(VersionedKvBackend backend) {
        return backend.get(APPLIED_KEY).flatMap(RaftApplier::decodeMarker);
    }

    static Optional<Applied> decodeMarker(byte[] raw) {
        if (raw == null || raw.length != 16) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return Optional.of(new Applied(buffer.getLong(), buffer.getLong()));
    }

    static byte[] marker(long index, long term) {
        return ByteBuffer.allocate(16).putLong(index).putLong(term).array();
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
        backend.commit(Map.of(), batch.puts(), deletesOf(backend, batch));
        backend.flush();
        return new AppliedBatchListener.AppliedBatch(batch, previous);
    }

    /**
     * Aplica uma entrada do log junto com o marcador {@link #APPLIED_KEY}, sem esperar disco: a durabilidade
     * é a do log. O chamador faz o {@code flush} depois do grupo.
     */
    static AppliedBatchListener.AppliedBatch applyReplicated(VersionedKvBackend backend, CommittedBatch batch,
                                                             long index, long term) {
        Map<String, byte[]> previous = snapshotOf(backend, batch);
        Map<String, byte[]> puts = new LinkedHashMap<>(batch.puts());
        puts.put(APPLIED_KEY, marker(index, term));
        Set<String> deletes = deletesOf(backend, batch);
        deletes.remove(APPLIED_KEY);
        backend.applyReplicated(puts, deletes);
        return new AppliedBatchListener.AppliedBatch(batch, previous);
    }

    /**
     * Um snapshot é autoritativo: substitui o estado por inteiro, no mesmo lote atômico, para que chaves órfãs
     * presentes no seguidor (e ausentes no líder) sejam removidas.
     */
    private static Set<String> deletesOf(VersionedKvBackend backend, CommittedBatch batch) {
        Set<String> deletes = new LinkedHashSet<>(batch.deletes());
        if (batch.fullSnapshot()) {
            for (String key : backend.keysWithPrefix("")) {
                if (!batch.puts().containsKey(key)) {
                    deletes.add(key);
                }
            }
        }
        return deletes;
    }

    /** Lê o estado anterior das chaves tocadas, antes de sobrescrevê-las. */
    private static Map<String, byte[]> snapshotOf(VersionedKvBackend backend, CommittedBatch batch) {
        Map<String, byte[]> previous = new LinkedHashMap<>();
        batch.puts().keySet().forEach(key -> backend.get(key).ifPresent(v -> previous.put(key, v)));
        batch.deletes().forEach(key -> backend.get(key).ifPresent(v -> previous.put(key, v)));
        return previous;
    }
}
