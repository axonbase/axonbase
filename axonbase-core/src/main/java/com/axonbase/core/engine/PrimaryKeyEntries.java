package com.axonbase.core.engine;

import com.axonbase.core.Session;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.cluster.CommitCoordinator;
import com.axonbase.core.partition.PartitionContext;
import com.axonbase.core.partition.PartitionedKv;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.Transaction;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Em cluster, confere as entradas da chave primária antes de o líder confiar nelas.
 *
 * <p>As entradas de um nó podem não bater com os registros: um líder da rc1 grava sem mantê-las, e cada nó monta as
 * suas ao abrir, sobre o que já tinha aplicado. Por isso, na primeira escrita de cada tabela em cada mandato, o líder
 * espera aplicar o log, compara as entradas com os registros e replica só a diferença. Dentro do mandato todas as
 * escritas são dele e mantêm as entradas. Nada disso fica em disco: quem reinicia ou assume confere de novo.</p>
 */
final class PrimaryKeyEntries {

    /** Chaves corrigidas por lote replicado. */
    private static final int BATCH = 500;

    private final Datastore ds;
    /** Tabela para o mandato e as colunas com que as entradas dela foram conferidas. */
    private final ConcurrentMap<String, String> checked = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    PrimaryKeyEntries(Datastore ds) {
        this.ds = ds;
    }

    /** A base foi trocada por inteiro: nada do que foi conferido vale. */
    void forget() {
        checked.clear();
    }

    /** Chame antes de ler ou gravar entradas da tabela. Fora de cluster não faz nada. */
    void ensure(Session caller, Database db, String table, List<String> columns) {
        CommitCoordinator coordinator = ds.commitCoordinator();
        if (coordinator == null) {
            return;
        }
        // chave declarada na própria transação em curso: as entradas, inclusive as das linhas ainda não
        // confirmadas, estão nela
        Transaction open = caller.tx();
        if (open != null && open.isOpen() && open.writes(Executor.primaryKeyMarker(db, table))) {
            return;
        }
        String id = db.ns() + "|" + db.db() + "|" + table;
        if (stamp(coordinator, columns).equals(checked.get(id))) {
            return;
        }
        ReentrantLock lock = locks.computeIfAbsent(id, ignored -> new ReentrantLock());
        lock.lock();
        try {
            // falha fora do líder; no líder, espera aplicar o que os mandatos anteriores deixaram no log
            coordinator.beginValidation();
            String wanted = stamp(coordinator, columns);
            if (!wanted.equals(checked.get(id))) {
                reconcile(db, table, columns);
                checked.put(id, wanted);
            }
        } finally {
            lock.unlock();
        }
    }

    private static String stamp(CommitCoordinator coordinator, List<String> columns) {
        return coordinator.leadershipTerm() + "|" + String.join(",", columns);
    }

    private void reconcile(Database db, String table, List<String> columns) {
        PartitionContext context = new PartitionContext();
        KvBackend stored = PartitionedKv.wrap(db, db.records(), context, ds.coldTier().records());
        boolean contended = db.catalog().partitioning(table) == null;
        try (Fixes fixes = new Fixes(db, context)) {
            // entradas que o registro não confirma
            String entries = Executor.primaryKeyPrefix(db, table);
            stored.scanPrefix(entries, key -> {
                int cut = key.lastIndexOf('\u0000') + 1;
                String own = key.substring(cut);
                if (!own.isEmpty()
                    && !key.substring(0, cut).equals(Executor.storedKeySlot(stored, db, table, columns, own))) {
                    fixes.delete(key);
                }
                return true;
            });
            // entradas que faltam
            String records = db.ns() + "\u0000" + db.db() + "\u0000" + table + "\u0000";
            stored.scanPrefix(records, key -> {
                String own = key.substring(records.length());
                if (own.isEmpty() || own.charAt(0) == '!') {
                    return true;
                }
                String slot = Executor.storedKeySlot(stored, db, table, columns, own);
                if (slot != null) {
                    if (contended && stored.get(slot).isEmpty()) {
                        fixes.put(slot);
                    }
                    if (stored.get(slot + own).isEmpty()) {
                        fixes.put(slot + own);
                    }
                }
                return true;
            });
            fixes.commit();
        }
    }

    /** As correções, replicadas em lotes por transações comuns. Sem diferença, nada vai para o log. */
    private final class Fixes implements AutoCloseable {
        private final Database db;
        private final PartitionContext context;
        private Session session;
        private KvBackend target;
        private int pending;

        Fixes(Database db, PartitionContext context) {
            this.db = db;
            this.context = context;
        }

        void put(String key) {
            target().put(key, new byte[0]);
            count();
        }

        void delete(String key) {
            target().delete(key);
            count();
        }

        private KvBackend target() {
            if (session == null) {
                Session opened = new Session(db.ns(), db.db());
                ds.beginSession(opened);
                session = opened;
                target = PartitionedKv.wrap(db, opened.tx(), context, ds.coldTier().records());
            }
            return target;
        }

        private void count() {
            if (++pending >= BATCH) {
                commit();
            }
        }

        void commit() {
            Session open = session;
            if (open == null) {
                return;
            }
            session = null;
            target = null;
            pending = 0;
            try {
                ds.commitSession(open);
            } finally {
                if (open.inTransaction()) {
                    ds.cancelSession(open);
                }
            }
        }

        /** Uma varredura que falhou no meio deixa um lote aberto: ele é descartado. */
        @Override
        public void close() {
            if (session != null) {
                ds.cancelSession(session);
                session = null;
            }
        }
    }
}
