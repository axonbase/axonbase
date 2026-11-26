package com.axonbase.core.storage;

import java.util.List;
import java.util.Optional;

/**
 * Backend de chave-valor pluggable do AxonBase, equivalente á interface
 * {@code Transactable} do SurrealDB.
 * <p>
 * Opera con chaves {@code String} (codificadas de xeito ordenado) e valores
 * {@code byte[]}. O patrón é o de un novo sistema de almacenamiento con
 * operacións de lectura, escritura e borrado, e varrido por prefixo.
 * {@code put} insere ou sobrescribe; {@code putIfAbsent} só insere.
 */
public interface KvBackend {

    /** Devolve o valor para a chave, ou {@code Optional.empty()} si non existe. */
    Optional<byte[]> get(String key);

    /** Inserta ou sobrescribe a chave co valor. */
    void put(String key, byte[] value);

    /** Insere só si a chave non existe. Devolve {@code true} se se insertou. */
    boolean putIfAbsent(String key, byte[] value);

    /** Borra a chave. Devolve {@code true} se existiu. */
    boolean delete(String key);

    /** Devolve todas as chaves con o prefixo dado, en orde lexicográfico. */
    List<String> keysWithPrefix(String prefix);

    /**
     * Percorre as chaves com o prefixo em ordem lexicográfica e para quando {@code visitor}
     * devolve {@code false}. A implementação padrão lista tudo antes; backends com iterador
     * sobrescrevem para ler só o que for consumido.
     */
    default void scanPrefix(String prefix, java.util.function.Predicate<String> visitor) {
        for (String key : keysWithPrefix(prefix)) {
            if (!visitor.test(key)) {
                return;
            }
        }
    }

    /**
     * Percorre as entradas (chave e valor) com o prefixo, em ordem lexicográfica, até {@code visitor} devolver
     * {@code false}. A implementação padrão lê cada valor com {@link #get}; backends com iterador sobrescrevem para
     * ler chave e valor numa passada só.
     */
    default void scanEntries(String prefix, java.util.function.BiPredicate<String, byte[]> visitor) {
        for (String key : keysWithPrefix(prefix)) {
            byte[] value = get(key).orElse(new byte[0]);
            if (!visitor.test(key, value)) {
                return;
            }
        }
    }

    /**
     * Como {@link #scanEntries}, mas a partir da chave {@code from} (inclusive). Backends com iterador sobrescrevem
     * para saltar direto até ela.
     */
    default void scanEntriesFrom(String prefix, String from, java.util.function.BiPredicate<String, byte[]> visitor) {
        scanEntries(prefix, (key, value) -> key.compareTo(from) < 0 || visitor.test(key, value));
    }

    /** Cando a implementación é persistente, forza a escritura ao disco. */
    default void flush() {
    }

    /** Cando a implementación é persistente, pecha o backend. */
    default void close() {
    }
}