package com.axonbase.core.catalog;

import com.axonbase.value.AxonValue;

/**
 * Identidade dunha táboa: nome máis unha clave (número, string, uuid...).
 * Equivalente ao {@code RecordIdKey} do SurrealDB.
 */
public record RecordId(String table, AxonValue key) {

    public RecordId {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("táboa do record id non pode estar en branco");
        }
        if (key == null) {
            throw new IllegalArgumentException("chave do record id non pode ser null");
        }
    }

    @Override
    public String toString() {
        return table + ":" + key;
    }

    /** Chave física de almacenamento para o record (usada como clave do KV). */
    public String storageKey(String ns, String db) {
        return ns + "\u0000" + db + "\u0000" + table + "\u0000" + key.keyString();
    }
}