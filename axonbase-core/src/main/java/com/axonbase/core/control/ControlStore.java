package com.axonbase.core.control;

import com.axonbase.core.storage.KvBackend;

import java.nio.charset.StandardCharsets;

/**
 * Persistência do snapshot de controle no mesmo backend dos dados.
 *
 * <p>O snapshot vive em uma única chave para que uma alteração de DDL seja uma
 * única escrita. Quando essa escrita entra numa transação, ela viaja no mesmo
 * batch replicado das mudanças de dados e chega ao seguidor de forma atômica.</p>
 */
public final class ControlStore {

    /** Chave única do snapshot de controle no KV. */
    public static final String KEY = "__axon/control/snapshot";

    private final KvBackend backend;

    public ControlStore(KvBackend backend) {
        this.backend = backend;
    }

    /** Escreve o snapshot no destino indicado, que pode ser uma transação aberta. */
    public void save(ControlStateMachine state, KvBackend target) {
        target.put(KEY, CatalogCodec.encode(state.snapshot()).getBytes(StandardCharsets.UTF_8));
    }

    public ControlSnapshot load() {
        return backend.get(KEY).map(ControlStore::decode).orElseGet(ControlSnapshot::empty);
    }

    /** Decodifica um snapshot que chegou dentro de um batch replicado. */
    public static ControlSnapshot decode(byte[] raw) {
        return CatalogCodec.decode(new String(raw, StandardCharsets.UTF_8));
    }
}
