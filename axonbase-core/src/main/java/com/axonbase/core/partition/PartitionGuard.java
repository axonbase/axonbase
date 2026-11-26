package com.axonbase.core.partition;

import com.axonbase.common.Messages;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.storage.Transaction;
import com.axonbase.core.storage.VersionConflictException;

import java.time.Instant;
import java.util.concurrent.locks.Lock;

/**
 * Condições de commit que ordenam escritas e mudanças de partição. Usam a trava de índices do banco: escritas seguram
 * o lado de leitura e quem muda ou remove partição, o de escrita, até o lote estar gravado (em cluster, até ser
 * aplicado). Não há trava nova no caminho de escrita.
 */
final class PartitionGuard {

    private PartitionGuard() {
    }

    /**
     * A transação escreveu na partição: no commit ela precisa continuar aceitando escrita.
     *
     * @param created a própria transação criou a partição, então não existir ainda é esperado
     */
    static void writing(Transaction tx, Database db, String table, long start, boolean created) {
        String key = PartitionKeys.descriptorKey(db.ns(), db.db(), table, start);
        tx.guard("write:" + key, new Transaction.CommitGuard() {
            @Override
            public boolean exclusive() {
                return false;
            }

            @Override
            public Transaction.Held enter() {
                Lock lock = db.indexLock().readLock();
                lock.lock();
                var current = db.records().get(key).map(PartitionDescriptor::decode);
                boolean valid = current.map(PartitionDescriptor::writable).orElse(created);
                if (!valid) {
                    lock.unlock();
                    throw new VersionConflictException(Messages.get("partition_changed",
                        table + " " + Instant.ofEpochMilli(start)), key);
                }
                return lock::unlock;
            }
        });
    }

    /** A transação muda ou remove partições da tabela: o commit corre sem nenhuma escrita junto. */
    static void changing(Transaction tx, Database db, String table) {
        tx.guard("change:" + db.ns() + "|" + db.db() + "|" + table, new Transaction.CommitGuard() {
            @Override
            public boolean exclusive() {
                return true;
            }

            @Override
            public Transaction.Held enter() {
                Lock lock = db.indexLock().writeLock();
                lock.lock();
                return lock::unlock;
            }
        });
    }
}
