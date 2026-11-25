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

    /** Visitante de entradas de {@link #snapshotScan}: devolve {@code false} para parar. */
    @FunctionalInterface
    interface EntryVisitor {
        boolean visit(String key, byte[] value, long version) throws java.io.IOException;
    }

    /** Visitante de chaves apagadas de {@link #snapshotScan}: devolve {@code false} para parar. */
    @FunctionalInterface
    interface KeyVisitor {
        boolean visit(String key) throws java.io.IOException;
    }

    /**
     * Varredura de ponto no tempo para backup. Lê a marca d'água ({@link #currentVersion()}) ANTES de fixar
     * a visão, e então percorre as entradas (chave, valor e versão) dessa visão única, de modo que um commit
     * atômico aparece inteiro ou não aparece. Com {@code since > 0} só entram entradas de versão maior que
     * {@code since}, e as chaves apagadas depois dele vão para {@code deleted} (pode ser {@code null}).
     *
     * <p>Esta implementação padrão NÃO é de ponto no tempo (lê chave a chave); backends reais a sobrescrevem.</p>
     *
     * @return a marca d'água lida antes da varredura
     */
    default long snapshotScan(long since, EntryVisitor entries, KeyVisitor deleted) throws java.io.IOException {
        long mark = currentVersion();
        java.io.IOException[] failure = new java.io.IOException[1];
        scanEntries("", (key, value) -> {
            try {
                long v = versionOf(key);
                return since > 0 && v <= since || entries.visit(key, value, v);
            } catch (java.io.IOException e) {
                failure[0] = e;
                return false;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        if (since > 0 && deleted != null) {
            scanDeletedSince(since, key -> {
                try {
                    return deleted.visit(key);
                } catch (java.io.IOException e) {
                    failure[0] = e;
                    return false;
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
        }
        return mark;
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