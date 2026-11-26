package com.axonbase.core.partition;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.catalog.PartitionSpec;
import com.axonbase.core.catalog.TtlSpec;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.Transaction;
import com.axonbase.core.storage.VersionConflictException;
import com.axonbase.parser.Render;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.PartitionCommand;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Executa os comandos de partição e TTL.
 *
 * @param records     a transação da sessão, quando há uma aberta, ou o armazenamento direto
 * @param insideWrite o comando veio de dentro de uma escrita (um evento), onde não dá para mudar partições
 * @param evaluate    avalia as expressões de instante
 */
public record PartitionRunner(Datastore ds, Session session, Database db, KvBackend records, boolean insideWrite,
                              Function<Expr, AxonValue> evaluate) {

    private static final long MIN_INTERVAL_MS = 1_000L;

    public AxonValue run(PartitionCommand command) {
        return switch (command) {
            case PartitionCommand.Define d -> define(d);
            case PartitionCommand.Drop d -> drop(d);
            case PartitionCommand.Move m -> move(m);
            case PartitionCommand.Show s -> show(s.table());
            case PartitionCommand.DefineTtl t -> defineTtl(t);
            case PartitionCommand.DropTtl t -> dropTtl(t.table());
        };
    }

    /** Recusa um índice que não pode ser local à partição. */
    public static void checkIndex(Database db, String table, String index, boolean global) {
        if (global && db.catalog().partitioning(table) != null) {
            throw AxonError.internal(Messages.get("partition_index_unsupported", index));
        }
    }

    /**
     * Apaga de uma vez os dados de uma tabela particionada.
     *
     * @return {@code false} quando a tabela não é particionada (o chamador apaga chave a chave)
     */
    public boolean dropTable(String table) {
        if (db.catalog().partitioning(table) == null) {
            return false;
        }
        exclusive(() -> {
            PartitionChanges changes = new PartitionChanges();
            changes.deletePrefix(PartitionKeys.tablePrefix(db.ns(), db.db(), table));
            for (PartitionDescriptor partition : PartitionStore.list(db, records, table)) {
                changes.delete(PartitionKeys.descriptorKey(db.ns(), db.db(), table, partition.start()));
                changes.delete(PartitionKeys.bloomKey(db.ns(), db.db(), table, partition.start()));
            }
            changes.delete(PartitionKeys.generationKey(db.ns(), db.db(), table));
            changes.apply(db, table, records);
            return null;
        });
        return true;
    }

    private AxonValue define(PartitionCommand.Define d) {
        PartitionSpec spec = new PartitionSpec(d.field(), d.intervalMillis(), d.retentionMillis(),
            d.coldAfterMillis());
        Runnable register = () -> {
            db.catalog().partitioning(d.table(), spec);
            control(ControlCommand.Kind.PARTITIONING, d.table(), d);
        };
        if (ds.isReplaying()) {
            register.run();
            return AxonValue.nul();
        }
        if (d.intervalMillis() < MIN_INTERVAL_MS) {
            throw AxonError.internal(Messages.get("partition_interval_invalid"));
        }
        if (d.retentionMillis() != null && d.coldAfterMillis() != null
            && d.coldAfterMillis() >= d.retentionMillis()) {
            throw AxonError.internal(Messages.get("partition_cold_after_invalid"));
        }
        return exclusive(() -> {
            PartitionSpec existing = db.catalog().partitioning(d.table());
            if (existing == null) {
                if (hasRecords(d.table())) {
                    throw AxonError.internal(Messages.get("partition_requires_empty", d.table()));
                }
            } else if ((!existing.field().equals(d.field()) || existing.intervalMillis() != d.intervalMillis())
                && !PartitionStore.list(db, records, d.table()).isEmpty()) {
                throw AxonError.internal(Messages.get("partition_redefine", d.table()));
            }
            Catalog.TableDef table = db.catalog().table(d.table());
            if (table != null) {
                table.indexes().values().forEach(ix -> {
                    if (ix.search() || ix.geo() || ix.vector() || ix.count()) {
                        throw AxonError.internal(Messages.get("partition_index_unsupported", ix.name()));
                    }
                });
            }
            register.run();
            if (existing == null && records instanceof Transaction tx) {
                publishOnCommit(tx, d.table());
            }
            return AxonValue.nul();
        });
    }

    /**
     * Numa transação a definição só vale no commit: até lá as outras sessões não escrevem na tabela, e o commit,
     * sozinho, confere que ela continua vazia.
     */
    private void publishOnCommit(Transaction tx, String table) {
        db.catalog().pendingPartitioning(table, tx);
        tx.guard("define:" + db.ns() + "|" + db.db() + "|" + table, new Transaction.CommitGuard() {
            @Override
            public boolean exclusive() {
                return true;
            }

            @Override
            public Transaction.Held enter() {
                Lock lock = db.indexLock().writeLock();
                lock.lock();
                String prefix = PartitionKeys.tablePrefix(db.ns(), db.db(), table);
                boolean[] found = {false};
                for (String lead : List.of("n", "s", "x", PartitionKeys.MARK)) {
                    db.records().scanPrefix(prefix + lead, key -> {
                        found[0] = true;
                        return false;
                    });
                }
                if (found[0]) {
                    lock.unlock();
                    throw new VersionConflictException(Messages.get("partition_requires_empty", table), prefix);
                }
                return () -> {
                    // com a transação confirmada, vale para todos; se ela falhou, o catálogo já foi refeito
                    db.catalog().publishPartitioning(table, tx);
                    lock.unlock();
                };
            }
        });
    }

    private boolean hasRecords(String table) {
        boolean[] found = {false};
        String prefix = PartitionKeys.tablePrefix(db.ns(), db.db(), table);
        for (String lead : List.of("n", "s", "x")) {
            records.scanPrefix(prefix + lead, key -> {
                found[0] = true;
                return false;
            });
        }
        return found[0];
    }

    private AxonValue drop(PartitionCommand.Drop d) {
        requirePartitioned(d.table());
        long instant = instant(d.at() != null ? d.at() : d.before());
        return exclusive(() -> {
            List<PartitionDescriptor> targets = new ArrayList<>();
            for (PartitionDescriptor p : PartitionStore.list(db, records, d.table())) {
                boolean selected = d.at() != null ? p.start() <= instant && instant < p.end() : p.end() <= instant;
                if (selected && PartitionStore.track(db, records, d.table(), p.start()).isPresent()) {
                    targets.add(p);
                }
            }
            if (d.at() != null && targets.isEmpty()) {
                throw AxonError.internal(Messages.get("partition_none_found", d.table(),
                    Instant.ofEpochMilli(instant)));
            }
            if (!targets.isEmpty()) {
                PartitionChanges changes = new PartitionChanges();
                targets.forEach(p -> changes.drop(db, d.table(), p.start()));
                changes.apply(db, d.table(), records);
            }
            return AxonValue.num((long) targets.size());
        });
    }

    private AxonValue move(PartitionCommand.Move m) {
        requirePartitioned(m.table());
        long instant = instant(m.at());
        return exclusive(() -> {
            PartitionDescriptor partition = PartitionStore.list(db, records, m.table()).stream()
                .filter(p -> p.start() <= instant && instant < p.end()).findFirst()
                .flatMap(p -> PartitionStore.track(db, records, m.table(), p.start()))
                .orElseThrow(() -> AxonError.internal(Messages.get("partition_none_found", m.table(),
                    Instant.ofEpochMilli(instant))));
            PartitionDescriptor next = m.cold() ? partition.requestCold() : partition.requestHot();
            if (!next.equals(partition)) {
                if (m.cold() && !ds.coldTier().configured()) {
                    throw AxonError.internal(Messages.get("cold_not_configured"));
                }
                PartitionChanges changes = new PartitionChanges();
                changes.save(db, m.table(), next);
                changes.apply(db, m.table(), records);
            }
            return AxonValue.str(next.state().name());
        });
    }

    private AxonValue show(String table) {
        requirePartitioned(table);
        List<AxonValue> out = new ArrayList<>();
        for (PartitionDescriptor p : PartitionStore.list(db, records, table)) {
            Map<String, AxonValue> row = new LinkedHashMap<>();
            row.put("start", AxonValue.datetime(Instant.ofEpochMilli(p.start())));
            row.put("end", AxonValue.datetime(Instant.ofEpochMilli(p.end())));
            row.put("state", AxonValue.str(p.state().name()));
            if (p.object() != null) {
                row.put("object", AxonValue.str(p.object()));
                row.put("rows", AxonValue.num(p.rows()));
                row.put("bytes", AxonValue.num(p.bytes()));
            }
            out.add(AxonValue.object(row));
        }
        return AxonValue.array(out);
    }

    private AxonValue defineTtl(PartitionCommand.DefineTtl t) {
        if (t.afterMillis() < 0) {
            throw AxonError.internal(Messages.get("ttl_field_invalid"));
        }
        TtlSpec spec = new TtlSpec(t.field(), t.afterMillis());
        boolean changed = !spec.equals(db.catalog().ttl(t.table()));
        db.catalog().ttl(t.table(), spec);
        control(ControlCommand.Kind.TTL, t.table(), t);
        if (changed && !ds.isReplaying()) {
            // as linhas que já existem ganham a entrada de vencimento em segundo plano
            PartitionStore.putBlind(records, PartitionKeys.ttlBackfillKey(db.ns(), db.db(), t.table()), new byte[0]);
        }
        return AxonValue.nul();
    }

    private AxonValue dropTtl(String table) {
        if (db.catalog().ttl(table) == null) {
            if (ds.isReplaying()) {
                return AxonValue.nul();
            }
            throw AxonError.internal(Messages.get("ttl_not_defined", table));
        }
        return exclusive(() -> {
            db.catalog().removeTtl(table);
            ds.removeControl(session, db.ns(), db.db(), ControlCommand.Kind.TTL, table);
            PartitionChanges changes = new PartitionChanges();
            changes.deletePrefix(PartitionKeys.ttlPrefix(db.ns(), db.db(), table));
            changes.delete(PartitionKeys.ttlBackfillKey(db.ns(), db.db(), table));
            changes.apply(db, table, records);
            return AxonValue.nul();
        });
    }

    private void control(ControlCommand.Kind kind, String table, PartitionCommand command) {
        ds.applyControl(session, ControlCommand.of(kind, db.ns(), db.db(), table,
            Render.stmt(new Statement.Partition(command))));
    }

    private void requirePartitioned(String table) {
        if (db.catalog().partitioning(table) == null) {
            throw AxonError.internal(Messages.get("partition_not_partitioned", table));
        }
    }

    private long instant(Expr expr) {
        AxonValue value = evaluate.apply(expr);
        if (value == null || !value.isDatetime()) {
            throw AxonError.internal(Messages.get("partition_time_invalid", value));
        }
        return value.asInstant().toEpochMilli();
    }

    /**
     * Roda sem nenhuma escrita junto. Numa transação, quem garante isso é o commit (veja {@link PartitionGuard});
     * fora dela, o lado de escrita da trava de índices.
     */
    private <T> T exclusive(Supplier<T> work) {
        if (insideWrite) {
            throw AxonError.internal(Messages.get("partition_in_write"));
        }
        if (records instanceof Transaction) {
            return work.get();
        }
        Lock lock = db.indexLock().writeLock();
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
