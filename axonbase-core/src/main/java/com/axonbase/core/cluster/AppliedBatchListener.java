package com.axonbase.core.cluster;

import java.util.Collections;
import java.util.Map;

/**
 * Observador de batches já confirmados e aplicados ao storage.
 *
 * <p>É por aqui que um seguidor descobre que precisa reconstruir o catálogo e
 * emitir notificações de live query: o consenso aplica os bytes, e o listener
 * traduz esses bytes de volta para estado em memória.</p>
 */
@FunctionalInterface
public interface AppliedBatchListener {

    /** Listener nulo compartilhado, para que ignorá-lo seja detectável por identidade. */
    AppliedBatchListener NONE = applied -> {
    };

    void onApplied(AppliedBatch applied);

    /** Listener nulo, usado quando o nó roda sem plano de controle acoplado. */
    static AppliedBatchListener none() {
        return NONE;
    }

    /**
     * Batch confirmado mais o valor que cada chave tocada tinha antes.
     *
     * <p>O estado anterior é indispensável para o fanout: sem ele não há como
     * distinguir um CREATE de um UPDATE, nem calcular o DIFF de uma live query,
     * porque o storage já foi sobrescrito quando o listener é chamado.</p>
     *
     * @param batch    a mutação confirmada
     * @param previous valor anterior das chaves tocadas; chave ausente do mapa
     *                 significa que ela não existia
     */
    record AppliedBatch(CommittedBatch batch, Map<String, byte[]> previous) {

        public AppliedBatch {
            previous = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(previous));
        }
    }
}
