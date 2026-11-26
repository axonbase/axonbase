package com.axonbase.core.partition;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.catalog.PartitionSpec;
import com.axonbase.core.catalog.RecordId;
import com.axonbase.core.catalog.TtlSpec;
import com.axonbase.core.storage.ColumnarIndex;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.Transaction;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Vista de um banco que traduz as chaves lógicas das tabelas particionadas ({@code ns\0db\0tabela\0chave} e as dos
 * índices UNIQUE e COLUMNAR) para as chaves físicas dentro de cada partição, e que anota o prazo de validade das
 * linhas de tabelas com TTL. O executor continua enxergando só chaves lógicas.
 *
 * <p>O id não diz a partição: a leitura por id pergunta às partições, da mais nova para a mais antiga. Sem ORDER BY,
 * uma varredura devolve as linhas por partição e, dentro dela, por chave.</p>
 */
public final class PartitionedKv implements KvBackend {

    private static final byte[] EMPTY = new byte[0];

    private final Database db;
    private final KvBackend base;
    private final PartitionContext context;
    private final ColdRecords cold;
    private final String dbPrefix;
    private final String columnarPrefix;

    private enum Kind { TABLE, RECORD, UNIQUE, COLUMNAR }

    /** Uma chave (ou prefixo) lógica de uma tabela: {@code tail} é o que vem depois do nome da tabela. */
    private record Address(String table, Kind kind, String tail) {
    }

    private record Found(PartitionDescriptor partition, byte[] value) {
    }

    private PartitionedKv(Database db, KvBackend base, PartitionContext context, ColdRecords cold) {
        this.db = db;
        this.base = base;
        this.context = context;
        this.cold = cold;
        this.dbPrefix = PartitionKeys.dbPrefix(db.ns(), db.db());
        this.columnarPrefix = "CI|" + db.ns() + "|" + db.db() + "|";
    }

    /** A própria {@code base} quando o banco não tem partição nem TTL: o caminho comum não paga nada. */
    public static KvBackend wrap(Database db, KvBackend base, PartitionContext context, ColdRecords cold) {
        return db.catalog().hasTimeData() ? new PartitionedKv(db, base, context, cold) : base;
    }

    /** O armazenamento por baixo da vista. */
    public KvBackend base() {
        return base;
    }

    /**
     * Avisa para onde vai o registro que está prestes a ser escrito, antes de os índices dele serem gravados.
     *
     * @param generated o motor acabou de gerar o id, então ele não existe em partição alguma
     */
    public void bind(RecordId rid, AxonValue record, boolean generated) {
        PartitionSpec spec = db.catalog().partitioning(rid.table());
        if (spec == null) {
            return;
        }
        long start = startOf(spec, rid.table(), record);
        String recordKey = rid.key().keyString();
        if (!generated) {
            Found current = locate(rid.table(), recordKey);
            if (current != null) {
                requireWritable(rid.table(), current.partition());
            }
        }
        ensureWritable(rid.table(), spec, start);
        context.target(id(rid.table(), recordKey), start, generated);
    }

    /** O registro existe numa partição que agora não aceita escrita. */
    boolean readOnlyRow(String table, String recordKey) {
        if (db.catalog().partitioning(table) == null) {
            return false;
        }
        Found found = locate(table, recordKey);
        return found != null && !found.partition().writable();
    }

    // ---- leituras ----

    @Override
    public Optional<byte[]> get(String key) {
        Address address = partitioned(key);
        if (address == null || address.kind() == Kind.TABLE) {
            return base.get(key);
        }
        if (address.kind() == Kind.RECORD) {
            Found found = locate(address.table(), address.tail());
            return found == null ? Optional.empty() : Optional.of(found.value());
        }
        for (PartitionDescriptor partition : newestFirst(address.table())) {
            Optional<byte[]> value = base.get(physicalIndex(address, partition.start()));
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        if (partitioned(prefix) == null) {
            return base.keysWithPrefix(prefix);
        }
        List<String> keys = new ArrayList<>();
        scanPrefix(prefix, key -> {
            keys.add(key);
            return true;
        });
        return keys;
    }

    @Override
    public void scanPrefix(String prefix, Predicate<String> visitor) {
        Address address = partitioned(prefix);
        if (address == null) {
            base.scanPrefix(prefix, visitor);
            return;
        }
        scan(address, false, (key, value) -> visitor.test(key));
    }

    @Override
    public void scanEntries(String prefix, BiPredicate<String, byte[]> visitor) {
        Address address = partitioned(prefix);
        if (address == null) {
            base.scanEntries(prefix, visitor);
            return;
        }
        scan(address, true, visitor);
    }

    private void scan(Address address, boolean withValues, BiPredicate<String, byte[]> visitor) {
        String table = address.table();
        TimeRange range = address.kind() == Kind.RECORD || address.kind() == Kind.TABLE
            ? context.range(rangeKey(db, table)) : TimeRange.ALL;
        boolean[] stopped = {false};
        BiPredicate<String, byte[]> tracked = (key, value) -> {
            boolean go = visitor.test(key, value);
            stopped[0] = !go;
            return go;
        };
        for (PartitionDescriptor partition : PartitionStore.list(db, base, table)) {
            if (!range.overlaps(partition)) {
                continue;
            }
            if (!(address.kind() == Kind.RECORD && partition.coldData())) {
                scanLocal(address, partition, withValues, tracked);
            }
            if (!stopped[0] && partition.coldData()
                && (address.kind() == Kind.RECORD || address.kind() == Kind.TABLE)) {
                cold.scan(db, table, partition, address.tail(),
                    (recordKey, value) -> tracked.test(PartitionKeys.tablePrefix(db.ns(), db.db(), table) + recordKey,
                        value));
            }
            if (stopped[0]) {
                return;
            }
        }
    }

    private void scanLocal(Address address, PartitionDescriptor partition, boolean withValues,
                           BiPredicate<String, byte[]> visitor) {
        String table = address.table();
        String partitionPrefix = PartitionKeys.partitionPrefix(db.ns(), db.db(), table, partition.start());
        boolean columnar = address.kind() == Kind.COLUMNAR;
        String physical = partitionPrefix + (columnar ? PartitionKeys.COLUMNAR_MARK : "") + address.tail();
        int skip = partitionPrefix.length() + (columnar ? PartitionKeys.COLUMNAR_MARK.length() : 0);
        String logical = columnar ? columnarPrefix + table + "|" : PartitionKeys.tablePrefix(db.ns(), db.db(), table);
        BiPredicate<String, byte[]> translated = (key, value) -> {
            // o índice COLUMNAR mora em outro espaço de chaves lógicas: não aparece na varredura da tabela
            if (address.kind() == Kind.TABLE && key.startsWith(PartitionKeys.COLUMNAR_MARK, partitionPrefix.length())) {
                return true;
            }
            String tail = key.substring(skip);
            if (!withValues && !columnar && PartitionKeys.isRecordTail(tail)) {
                context.located(id(table, tail), partition.start()); // quem listou a chave vai ler o registro
            }
            return visitor.test(logical + tail, value);
        };
        if (withValues) {
            base.scanEntries(physical, translated);
        } else {
            base.scanPrefix(physical, key -> translated.test(key, null));
        }
    }

    // ---- escritas ----

    @Override
    public void put(String key, byte[] value) {
        Address address = address(key);
        if (address == null || address.kind() == Kind.TABLE) {
            base.put(key, value);
            return;
        }
        PartitionSpec spec = db.catalog().partitioning(address.table());
        if (address.kind() == Kind.RECORD) {
            if (spec == null) {
                base.put(key, value);
            } else {
                putRecord(spec, address, value);
            }
            trackExpiry(address.table(), address.tail(), value);
        } else if (spec == null) {
            base.put(key, value);
        } else {
            putIndex(address, value);
        }
    }

    @Override
    public boolean putIfAbsent(String key, byte[] value) {
        if (get(key).isPresent()) {
            return false;
        }
        put(key, value);
        return true;
    }

    @Override
    public boolean delete(String key) {
        Address address = partitioned(key);
        if (address == null || address.kind() == Kind.TABLE) {
            return base.delete(key);
        }
        String table = address.table();
        if (address.kind() == Kind.RECORD) {
            Found found = locate(table, address.tail());
            if (found == null) {
                return false;
            }
            long start = found.partition().start();
            requireWritable(table, found.partition());
            guard(table, start, false);
            base.delete(physicalRecord(table, start, address.tail()));
            context.forget(id(table, address.tail()));
            return true;
        }
        boolean deleted = false;
        for (PartitionDescriptor partition : newestFirst(table)) {
            String physical = physicalIndex(address, partition.start());
            if (base.get(physical).isPresent()) {
                deleted |= base.delete(physical);
            }
        }
        return deleted;
    }

    @Override
    public void flush() {
        base.flush();
    }

    private void putRecord(PartitionSpec spec, Address address, byte[] value) {
        String table = address.table();
        String recordKey = address.tail();
        long start = startOf(spec, table, AxonJson.decode(value));
        String id = id(table, recordKey);
        Found previous = context.takeFresh(id) ? null : locate(table, recordKey);
        if (previous != null && previous.partition().start() != start) {
            // o campo da partição mudou: a linha se muda
            long old = previous.partition().start();
            requireWritable(table, previous.partition());
            guard(table, old, false);
            base.delete(physicalRecord(table, old, recordKey));
        }
        ensureWritable(table, spec, start);
        base.put(physicalRecord(table, start, recordKey), value);
        context.target(id, start, false);
        context.located(id, start);
    }

    private void putIndex(Address address, byte[] value) {
        String table = address.table();
        String recordKey;
        if (address.kind() == Kind.UNIQUE) {
            recordKey = address.tail().substring(address.tail().lastIndexOf('\u0000') + 1);
        } else {
            ColumnarIndex.Entry entry = ColumnarIndex.decodeEntry(value);
            recordKey = entry == null ? "" : entry.rowKey();
        }
        String id = id(table, recordKey);
        Long start = context.target(id);
        if (start == null) {
            Found found = locate(table, recordKey);
            if (found == null) {
                throw AxonError.internal(Messages.get("partition_index_orphan", table, recordKey));
            }
            start = found.partition().start();
        }
        base.put(physicalIndex(address, start), value);
    }

    private void trackExpiry(String table, String recordKey, byte[] value) {
        TtlSpec ttl = db.catalog().ttl(table);
        if (ttl == null) {
            return;
        }
        OptionalLong expiry = expiryOf(ttl, AxonJson.decode(value));
        if (expiry.isPresent()) {
            PartitionStore.putBlind(base,
                PartitionKeys.ttlKey(db.ns(), db.db(), table, expiry.getAsLong(), recordKey), EMPTY);
        }
    }

    /** Quando a linha vence, em milissegundos, ou vazio quando não tem o campo (nunca vence). */
    static OptionalLong expiryOf(TtlSpec ttl, AxonValue record) {
        if (record == null || !record.isObject()) {
            return OptionalLong.empty();
        }
        AxonValue value = record.asObject().get(ttl.field());
        if (value == null || !value.isDatetime()) {
            return OptionalLong.empty();
        }
        long expiry = value.asInstant().toEpochMilli() + ttl.afterMillis();
        return OptionalLong.of(Math.max(0L, expiry));
    }

    // ---- partições ----

    private long startOf(PartitionSpec spec, String table, AxonValue record) {
        AxonValue value = record != null && record.isObject() ? record.asObject().get(spec.field()) : null;
        if (value == null || !value.isDatetime() || value.asInstant().toEpochMilli() < 0) {
            throw AxonError.internal(Messages.get("partition_field_required", table, spec.field()));
        }
        return spec.startOf(value.asInstant().toEpochMilli());
    }

    /** Cria a partição se faltar e exige que ela aceite escrita. */
    private void ensureWritable(String table, PartitionSpec spec, long start) {
        Optional<PartitionDescriptor> existing = PartitionStore.read(db, base, table, start);
        if (existing.isPresent()) {
            requireWritable(table, existing.get());
            guard(table, start, false);
            return;
        }
        if (PartitionStore.list(db, base, table).size() >= PartitionStore.MAX_PARTITIONS) {
            throw AxonError.internal(Messages.get("partition_limit", table, PartitionStore.MAX_PARTITIONS));
        }
        guard(table, start, true);
        PartitionStore.save(db, base, table, PartitionDescriptor.hot(start, start + spec.intervalMillis()));
    }

    private void requireWritable(String table, PartitionDescriptor partition) {
        if (!partition.writable()) {
            throw AxonError.internal(Messages.get("partition_readonly",
                table + " " + Instant.ofEpochMilli(partition.start()), partition.state()));
        }
    }

    private void guard(String table, long start, boolean created) {
        if (base instanceof Transaction tx) {
            PartitionGuard.writing(tx, db, table, start, created);
        }
    }

    private Found locate(String table, String recordKey) {
        List<PartitionDescriptor> partitions = PartitionStore.list(db, base, table);
        String id = id(table, recordKey);
        Long hint = context.located(id);
        if (hint != null) {
            for (PartitionDescriptor partition : partitions) {
                if (partition.start() == hint) {
                    Found found = lookIn(table, partition, recordKey);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        for (int i = partitions.size() - 1; i >= 0; i--) {
            PartitionDescriptor partition = partitions.get(i);
            if (hint != null && partition.start() == hint) {
                continue;
            }
            Found found = lookIn(table, partition, recordKey);
            if (found != null) {
                context.located(id, partition.start());
                return found;
            }
        }
        return null;
    }

    private Found lookIn(String table, PartitionDescriptor partition, String recordKey) {
        Optional<byte[]> value = partition.coldData()
            ? cold.get(db, base, table, partition, recordKey)
            : base.get(physicalRecord(table, partition.start(), recordKey));
        return value.map(v -> new Found(partition, v)).orElse(null);
    }

    private List<PartitionDescriptor> newestFirst(String table) {
        List<PartitionDescriptor> partitions = new ArrayList<>(PartitionStore.list(db, base, table));
        java.util.Collections.reverse(partitions);
        return partitions;
    }

    // ---- chaves ----

    /** A chave do escopo de poda de uma tabela em {@link PartitionContext#prune}. */
    public static String rangeKey(Database db, String table) {
        return db.ns() + "|" + db.db() + "|" + table;
    }

    private String id(String table, String recordKey) {
        return db.ns() + "|" + db.db() + "|" + table + "|" + recordKey;
    }

    private String physicalRecord(String table, long start, String recordKey) {
        return PartitionKeys.partitionPrefix(db.ns(), db.db(), table, start) + recordKey;
    }

    private String physicalIndex(Address address, long start) {
        return PartitionKeys.partitionPrefix(db.ns(), db.db(), address.table(), start)
            + (address.kind() == Kind.COLUMNAR ? PartitionKeys.COLUMNAR_MARK : "") + address.tail();
    }

    /** O endereço lógico quando a chave é de uma tabela particionada; senão {@code null}. */
    private Address partitioned(String key) {
        Address address = address(key);
        return address != null && db.catalog().partitioning(address.table()) != null ? address : null;
    }

    private Address address(String key) {
        if (key.startsWith(dbPrefix)) {
            int separator = key.indexOf('\u0000', dbPrefix.length());
            if (separator < 0) {
                return null;
            }
            String table = key.substring(dbPrefix.length(), separator);
            String tail = key.substring(separator + 1);
            if (tail.isEmpty()) {
                return new Address(table, Kind.TABLE, tail);
            }
            if (PartitionKeys.isRecordTail(tail)) {
                return new Address(table, Kind.RECORD, tail);
            }
            return tail.startsWith("!uidx") ? new Address(table, Kind.UNIQUE, tail) : null;
        }
        if (key.startsWith(columnarPrefix)) {
            int separator = key.indexOf('|', columnarPrefix.length());
            if (separator < 0) {
                return null;
            }
            return new Address(key.substring(columnarPrefix.length(), separator), Kind.COLUMNAR,
                key.substring(separator + 1));
        }
        return null;
    }
}
