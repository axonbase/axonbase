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

    /** Cando a implementación é persistente, forza a escritura ao disco. */
    default void flush() {
    }

    /** Cando a implementación é persistente, pecha o backend. */
    default void close() {
    }
}