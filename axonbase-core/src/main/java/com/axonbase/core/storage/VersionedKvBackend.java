package com.axonbase.core.storage;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** KV con versións por chave e batch condicional para commits otimistas. */
public interface VersionedKvBackend extends KvBackend {

    long versionOf(String key);

    /**
     * Maior versão já atribuída a uma chave. Serve de marca d'água para backup incremental:
     * chaves alteradas depois dela têm versão maior. Backends sem contador devolvem 0, e o
     * backup cai no completo.
     */
    default long currentVersion() {
        return 0L;
    }

    /**
     * Percorre as chaves apagadas depois da versão {@code since} (têm versão registrada maior e já
     * não existem nos dados). Backends que descartam a versão ao apagar não reportam nada, e o backup
     * incremental fica sem deleções.
     */
    default void scanDeletedSince(long since, java.util.function.Predicate<String> visitor) {
    }

    default Optional<VersionedValue> getVersioned(String key) {
        return get(key).map(value -> new VersionedValue(value, versionOf(key)));
    }

    /**
     * Retorna o snapshot de uma chave no timestamp dado (time travel).
     * Implementações sem ledger histórico retornam Optional.empty().
     */
    default Optional<byte[]> snapshotAt(String key, long timestampEpochMillis) {
        return Optional.empty();
    }

    /**
     * Valida todas as versións antes de aplicar deletes e puts como unha unidade.
     * Sen rangos varridos, é o caso para chamadas independentes de commit.
     */
    default void commit(Map<String, Long> expectedVersions, Map<String, byte[]> puts,
                        Set<String> deletes) {
        commit(expectedVersions, puts, deletes, Set.of());
    }

    void commit(Map<String, Long> expectedVersions, Map<String, byte[]> puts, Set<String> deletes,
                Set<String> readPrefixes);

    record VersionedValue(byte[] value, long version) {
    }
}