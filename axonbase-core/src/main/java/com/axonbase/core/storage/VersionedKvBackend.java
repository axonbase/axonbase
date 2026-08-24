package com.axonbase.core.storage;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** KV com versões por chave e batch condicional para commits otimistas. */
public interface VersionedKvBackend extends KvBackend {

    long versionOf(String key);

    default Optional<VersionedValue> getVersioned(String key) {
        return get(key).map(value -> new VersionedValue(value, versionOf(key)));
    }

    /** Valida todas as versões antes de aplicar deletes e puts como uma unidade. */
    void commit(Map<String, Long> expectedVersions, Map<String, byte[]> puts, Set<String> deletes);

    record VersionedValue(byte[] value, long version) {
    }
}
