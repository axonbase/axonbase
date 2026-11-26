package com.axonbase.core.shard;

import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * O mapa dos shards, imutável: quem são os shards, de quem é cada slot, quais tabelas são divididas e a movimentação em
 * curso. Toda mudança devolve um mapa novo com a época seguinte; o plano de controle só aceita a época {@code atual + 1}.
 */
public final class ShardMap {

    /** Uma tabela dividida. */
    public record TableKey(String namespace, String database, String table) implements Comparable<TableKey> {
        @Override
        public int compareTo(TableKey other) {
            return Comparator.comparing(TableKey::namespace).thenComparing(TableKey::database)
                .thenComparing(TableKey::table).compare(this, other);
        }
    }

    public enum MigrationPhase {
        /** O dono ainda é a origem; o destino recebe a cópia. */
        COPY,
        /** O dono já é o destino; falta apagar a cópia da origem. */
        CLEANUP
    }

    /** Uma movimentação de slots em curso (no máximo uma). */
    public record Migration(List<SlotRange> ranges, String source, String target, MigrationPhase phase) {
        public Migration {
            ranges = List.copyOf(ranges);
        }

        public boolean contains(int slot) {
            return ranges.stream().anyMatch(r -> r.contains(slot));
        }
    }

    private final long epoch;
    private final List<ShardInfo> shards;
    /** Índice em {@link #shards} do dono de cada slot. */
    private final int[] owner;
    private final Map<TableKey, String> tables;
    private final Migration migration;

    private ShardMap(long epoch, List<ShardInfo> shards, int[] owner, Map<TableKey, String> tables,
                     Migration migration) {
        this.epoch = epoch;
        this.shards = List.copyOf(shards);
        this.owner = owner;
        this.tables = new TreeMap<>(tables);
        this.migration = migration;
    }

    /** Mapa de um conjunto novo: os slots divididos em faixas contíguas e iguais; o primeiro shard é o base. */
    public static ShardMap initial(List<ShardInfo> shards) {
        if (shards.isEmpty() || shards.stream().map(ShardInfo::id).distinct().count() != shards.size()) {
            throw ShardException.refused("shard_map_invalid", shards);
        }
        int[] owner = new int[SlotHash.SLOTS];
        for (int slot = 0; slot < owner.length; slot++) {
            owner[slot] = (int) ((long) slot * shards.size() / SlotHash.SLOTS);
        }
        return new ShardMap(1, shards, owner, Map.of(), null);
    }

    public long epoch() {
        return epoch;
    }

    public List<ShardInfo> shards() {
        return shards;
    }

    public List<String> shardIds() {
        return shards.stream().map(ShardInfo::id).toList();
    }

    /** O shard base: o das tabelas sem {@code SHARD BY}. */
    public ShardInfo home() {
        return shards.get(0);
    }

    public String ownerOf(int slot) {
        return shards.get(owner[slot]).id();
    }

    public List<SlotRange> slotsOf(String shardId) {
        int index = indexOf(shardId);
        List<SlotRange> ranges = new ArrayList<>();
        int start = -1;
        for (int slot = 0; slot <= owner.length; slot++) {
            boolean mine = slot < owner.length && owner[slot] == index;
            if (mine && start < 0) {
                start = slot;
            } else if (!mine && start >= 0) {
                ranges.add(new SlotRange(start, slot - 1));
                start = -1;
            }
        }
        return ranges;
    }

    /** O campo de shard da tabela, ou vazio quando ela não é dividida. */
    public Optional<String> shardKey(TableKey table) {
        return Optional.ofNullable(tables.get(table));
    }

    public Map<TableKey, String> tables() {
        return java.util.Collections.unmodifiableMap(tables);
    }

    public boolean hasShardedTables(String namespace, String database) {
        return tables.keySet().stream()
            .anyMatch(t -> t.namespace().equals(namespace) && t.database().equals(database));
    }

    public Optional<Migration> migration() {
        return Optional.ofNullable(migration);
    }

    /** O slot está numa movimentação em curso (qualquer fase). */
    public boolean moving(int slot) {
        return migration != null && migration.contains(slot);
    }

    public ShardMap withTable(TableKey table, String field) {
        Map<TableKey, String> next = new TreeMap<>(tables);
        next.put(table, field);
        return new ShardMap(epoch + 1, shards, owner, next, migration);
    }

    public ShardMap withoutTable(TableKey table) {
        Map<TableKey, String> next = new TreeMap<>(tables);
        next.remove(table);
        return new ShardMap(epoch + 1, shards, owner, next, migration);
    }

    /** Acrescenta um shard sem slots; uma movimentação lhe entrega os primeiros. */
    public ShardMap withShard(ShardInfo shard) {
        if (shardIds().contains(shard.id())) {
            throw ShardException.refused("shard_map_invalid", "shard repetido: " + shard.id());
        }
        List<ShardInfo> next = new ArrayList<>(shards);
        next.add(shard);
        return new ShardMap(epoch + 1, next, owner, tables, migration);
    }

    public ShardMap startMigration(List<SlotRange> ranges, String target) {
        if (migration != null) {
            throw ShardException.refused("shard_map_invalid", "já há uma movimentação em curso");
        }
        if (ranges.isEmpty()) {
            throw ShardException.refused("shard_map_invalid", "nenhum slot para mover");
        }
        indexOf(target);
        String source = ownerOf(ranges.get(0).from());
        for (SlotRange range : ranges) {
            for (int slot = range.from(); slot <= range.to(); slot++) {
                if (!ownerOf(slot).equals(source)) {
                    throw ShardException.refused("shard_map_invalid", "os slots têm mais de um dono");
                }
            }
        }
        if (source.equals(target)) {
            throw ShardException.refused("shard_map_invalid", "os slots já são do destino");
        }
        return new ShardMap(epoch + 1, shards, owner, tables,
            new Migration(ranges, source, target, MigrationPhase.COPY));
    }

    /** Passa os slots ao destino; a movimentação entra na limpeza. */
    public ShardMap switchOwner() {
        requirePhase(MigrationPhase.COPY);
        int[] next = owner.clone();
        int target = indexOf(migration.target());
        for (SlotRange range : migration.ranges()) {
            Arrays.fill(next, range.from(), range.to() + 1, target);
        }
        return new ShardMap(epoch + 1, shards, next, tables,
            new Migration(migration.ranges(), migration.source(), migration.target(), MigrationPhase.CLEANUP));
    }

    public ShardMap finishMigration() {
        requirePhase(MigrationPhase.CLEANUP);
        return new ShardMap(epoch + 1, shards, owner, tables, null);
    }

    /** Desiste antes da troca de dono. */
    public ShardMap abortMigration() {
        requirePhase(MigrationPhase.COPY);
        return new ShardMap(epoch + 1, shards, owner, tables, null);
    }

    private void requirePhase(MigrationPhase phase) {
        if (migration == null || migration.phase() != phase) {
            throw ShardException.refused("shard_map_invalid", "não há movimentação na fase " + phase);
        }
    }

    private int indexOf(String shardId) {
        for (int i = 0; i < shards.size(); i++) {
            if (shards.get(i).id().equals(shardId)) {
                return i;
            }
        }
        throw ShardException.refused("shard_unknown", shardId);
    }

    // ---- codificação ----

    public AxonValue toValue() {
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("epoch", AxonValue.num(epoch));
        out.put("shards", AxonValue.array(shards.stream().map(shard -> {
            Map<String, AxonValue> entry = new LinkedHashMap<>();
            entry.put("id", AxonValue.str(shard.id()));
            entry.put("endpoints", AxonValue.array(shard.endpoints().stream().map(AxonValue::str).toList()));
            entry.put("slots", AxonValue.str(SlotRange.format(slotsOf(shard.id()))));
            return AxonValue.object(entry);
        }).toList()));
        out.put("tables", AxonValue.array(tables.entrySet().stream().map(table -> {
            Map<String, AxonValue> entry = new LinkedHashMap<>();
            entry.put("ns", AxonValue.str(table.getKey().namespace()));
            entry.put("db", AxonValue.str(table.getKey().database()));
            entry.put("table", AxonValue.str(table.getKey().table()));
            entry.put("field", AxonValue.str(table.getValue()));
            return AxonValue.object(entry);
        }).toList()));
        if (migration != null) {
            Map<String, AxonValue> entry = new LinkedHashMap<>();
            entry.put("slots", AxonValue.str(SlotRange.format(migration.ranges())));
            entry.put("source", AxonValue.str(migration.source()));
            entry.put("target", AxonValue.str(migration.target()));
            entry.put("phase", AxonValue.str(migration.phase().name()));
            out.put("migration", AxonValue.object(entry));
        }
        return AxonValue.object(out);
    }

    public static ShardMap fromValue(AxonValue value) {
        try {
            Map<String, AxonValue> in = value.asObject();
            List<ShardInfo> shards = new ArrayList<>();
            int[] owner = new int[SlotHash.SLOTS];
            Arrays.fill(owner, -1);
            for (AxonValue item : in.get("shards").asArray()) {
                Map<String, AxonValue> shard = item.asObject();
                for (SlotRange range : SlotRange.parse(shard.get("slots").asString())) {
                    Arrays.fill(owner, range.from(), range.to() + 1, shards.size());
                }
                shards.add(new ShardInfo(shard.get("id").asString(),
                    shard.get("endpoints").asArray().stream().map(AxonValue::asString).toList()));
            }
            if (Arrays.stream(owner).anyMatch(index -> index < 0)) {
                throw ShardException.refused("shard_map_invalid", "há slots sem dono");
            }
            Map<TableKey, String> tables = new TreeMap<>();
            for (AxonValue item : in.get("tables").asArray()) {
                Map<String, AxonValue> table = item.asObject();
                tables.put(new TableKey(table.get("ns").asString(), table.get("db").asString(),
                    table.get("table").asString()), table.get("field").asString());
            }
            Migration migration = null;
            AxonValue pending = in.get("migration");
            if (pending != null && pending.isObject()) {
                Map<String, AxonValue> entry = pending.asObject();
                migration = new Migration(SlotRange.parse(entry.get("slots").asString()),
                    entry.get("source").asString(), entry.get("target").asString(),
                    MigrationPhase.valueOf(entry.get("phase").asString()));
            }
            return new ShardMap(in.get("epoch").asLong(), shards, owner, tables, migration);
        } catch (ShardException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ShardException(ShardException.Code.REFUSED,
                ShardMessages.get("shard_map_invalid", e.getMessage()), e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShardMap other && epoch == other.epoch && shards.equals(other.shards)
            && Arrays.equals(owner, other.owner) && tables.equals(other.tables)
            && Objects.equals(migration, other.migration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, shards, Arrays.hashCode(owner), tables, migration);
    }

    @Override
    public String toString() {
        return "ShardMap[época " + epoch + ", shards " + shardIds() + ", tabelas " + tables.size()
            + (migration == null ? "" : ", movimentação " + migration) + "]";
    }
}
