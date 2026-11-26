package com.axonbase.core.partition;

import com.axonbase.core.catalog.Database;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.RangeDelete;
import com.axonbase.core.storage.Transaction;
import com.axonbase.core.storage.VersionedKvBackend;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** O lote de uma mudança de partição: marcas de remoção por faixa, descritores e a geração, tudo junto. */
public final class PartitionChanges {

    private final Map<String, byte[]> puts = new LinkedHashMap<>();
    private final Set<String> deletes = new LinkedHashSet<>();

    public void put(String key, byte[] value) {
        puts.put(key, value);
        deletes.remove(key);
    }

    public void delete(String key) {
        deletes.add(key);
        puts.remove(key);
    }

    /** Apaga todas as chaves do prefixo, com custo constante. */
    public void deletePrefix(String prefix) {
        deleteRange(RangeDelete.ofPrefix(prefix));
    }

    public void deleteRange(RangeDelete.Range range) {
        put(RangeDelete.key(range), RangeDelete.value(range));
    }

    /** Tira uma partição: os dados e índices dela, o descritor e o filtro. */
    public void drop(Database db, String table, long start) {
        deletePrefix(PartitionKeys.partitionPrefix(db.ns(), db.db(), table, start));
        delete(PartitionKeys.descriptorKey(db.ns(), db.db(), table, start));
        delete(PartitionKeys.bloomKey(db.ns(), db.db(), table, start));
        touch(db, table);
    }

    public void save(Database db, String table, PartitionDescriptor partition) {
        put(PartitionKeys.descriptorKey(db.ns(), db.db(), table, partition.start()), partition.encode());
        touch(db, table);
    }

    private void touch(Database db, String table) {
        put(PartitionKeys.generationKey(db.ns(), db.db(), table), PartitionStore.newGeneration());
    }

    /**
     * Grava a mudança. Numa transação, ela entra no commit, que passa a correr sozinho. Fora dela, grava um lote
     * atômico agora, e o chamador precisa segurar o lado de escrita da trava de índices.
     */
    void apply(Database db, String table, KvBackend kv) {
        if (kv instanceof Transaction tx) {
            PartitionGuard.changing(tx, db, table);
            deletes.forEach(tx::delete);
            puts.forEach(tx::put);
        } else if (kv instanceof VersionedKvBackend versioned) {
            versioned.commit(Map.of(), puts, deletes);
        } else {
            deletes.forEach(kv::delete);
            puts.forEach(kv::put);
        }
    }
}
