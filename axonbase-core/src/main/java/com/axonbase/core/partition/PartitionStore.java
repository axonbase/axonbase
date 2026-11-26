package com.axonbase.core.partition;

import com.axonbase.core.catalog.Database;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.Transaction;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lê e grava os descritores de partição. São chaves de dados, então entram no mesmo lote das linhas.
 *
 * <p>Numa transação as leituras não são registradas e as gravações são cegas: duas transações que escrevem na mesma
 * partição não conflitam por causa do descritor. Quem garante que a partição ainda aceita a escrita é a
 * {@link PartitionGuard}, no commit.</p>
 */
public final class PartitionStore {

    /** Teto de partições por tabela. */
    public static final int MAX_PARTITIONS = 4096;

    /** Lista já lida de cada tabela, válida enquanto a marca de geração não mudar. */
    public static final class Cache {
        private final ConcurrentMap<String, Listed> tables = new ConcurrentHashMap<>();

        private record Listed(byte[] generation, List<PartitionDescriptor> partitions) {
        }
    }

    private PartitionStore() {
    }

    static Optional<byte[]> peek(KvBackend kv, String key) {
        return kv instanceof Transaction tx ? tx.peek(key) : kv.get(key);
    }

    static List<String> peekKeys(KvBackend kv, String prefix) {
        return kv instanceof Transaction tx ? tx.peekKeys(prefix) : kv.keysWithPrefix(prefix);
    }

    static void putBlind(KvBackend kv, String key, byte[] value) {
        if (kv instanceof Transaction tx) {
            tx.putBlind(key, value);
        } else {
            kv.put(key, value);
        }
    }

    /** As partições da tabela, da mais antiga para a mais nova. */
    public static List<PartitionDescriptor> list(Database db, KvBackend kv, String table) {
        Optional<byte[]> generation = peek(kv, PartitionKeys.generationKey(db.ns(), db.db(), table));
        if (generation.isEmpty()) {
            return List.of();
        }
        Cache.Listed cached = db.partitionCache().tables.get(table);
        if (cached != null && Arrays.equals(cached.generation(), generation.get())) {
            return cached.partitions();
        }
        List<PartitionDescriptor> read = new ArrayList<>();
        for (String key : peekKeys(kv, PartitionKeys.descriptorPrefix(db.ns(), db.db(), table))) {
            peek(kv, key).ifPresent(raw -> read.add(PartitionDescriptor.decode(raw)));
        }
        List<PartitionDescriptor> partitions = List.copyOf(read);
        db.partitionCache().tables.put(table, new Cache.Listed(generation.get(), partitions));
        return partitions;
    }

    public static Optional<PartitionDescriptor> read(Database db, KvBackend kv, String table, long start) {
        return peek(kv, PartitionKeys.descriptorKey(db.ns(), db.db(), table, start)).map(PartitionDescriptor::decode);
    }

    /** Grava o descritor e muda a geração da tabela. */
    public static void save(Database db, KvBackend kv, String table, PartitionDescriptor partition) {
        putBlind(kv, PartitionKeys.descriptorKey(db.ns(), db.db(), table, partition.start()), partition.encode());
        putBlind(kv, PartitionKeys.generationKey(db.ns(), db.db(), table), newGeneration());
    }

    /** Um valor de geração nunca repetido. */
    static byte[] newGeneration() {
        UUID id = UUID.randomUUID();
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
            .array();
    }
}
