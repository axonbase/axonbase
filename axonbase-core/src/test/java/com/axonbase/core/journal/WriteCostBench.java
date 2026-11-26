package com.axonbase.core.journal;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Medida do portão de custo da E2: vazão de escrita com 8 threads. Um processo por medida, para comparar classpaths
 * diferentes (veja {@code scripts/bench-pitr.sh}).
 *
 * <pre>java ... WriteCostBench &lt;plain|journal&gt; &lt;kv|sql&gt; &lt;diretório&gt; &lt;segundos&gt; [threads]</pre>
 *
 * <p>{@code plain} usa só a API que existe desde a 0.10, então roda também contra as classes antigas (a base da
 * comparação); {@code journal} liga o journal de PITR. {@code kv} grava direto no armazenamento (o pior caso);
 * {@code sql} passa pelo motor com {@code CREATE}.</p>
 */
public final class WriteCostBench {

    private static final long WARMUP_MS = 3_000;

    private WriteCostBench() {
    }

    /** Carregada só na variante com journal, para a variante simples rodar com classes sem ele. */
    private static final class Journaled {
        private final PitrJournal journal;

        Journaled(RocksDbBackend db, Path dir) {
            this.journal = PitrJournal.attach(db, dir, JournalOptions.defaults());
        }

        String finish() {
            journal.close();
            long archived = journal.segments().stream().mapToLong(SegmentInfo::batches).sum();
            return " dropped=" + journal.droppedBatches() + " archived=" + archived;
        }
    }

    public static void main(String[] args) throws Exception {
        boolean journal = args[0].equals("journal");
        boolean sql = args[1].equals("sql");
        Path dir = Path.of(args[2]);
        long measureMs = Long.parseLong(args[3]) * 1_000;
        int threads = args.length > 4 ? Integer.parseInt(args[4]) : 8;

        RocksDbBackend db = new RocksDbBackend(dir.resolve("db").toString(), RocksDbBackend.SyncMode.BATCH);
        Journaled journaled = journal ? new Journaled(db, dir.resolve("journal")) : null;
        Datastore ds = sql ? new Datastore(db) : null;
        if (ds != null) {
            ds.createDatabase("app", "main");
            ds.execute("DEFINE TABLE pessoa SCHEMALESS;", session(), null);
        }
        AtomicBoolean stop = new AtomicBoolean();
        LongAdder writes = new LongAdder();
        byte[] value = new byte[256];
        new java.util.Random(1).nextBytes(value);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int n = t;
            workers.add(Thread.ofPlatform().start(() -> {
                Session session = session();
                for (long i = 0; !stop.get(); i++) {
                    if (ds != null) {
                        ds.execute("CREATE pessoa:" + (i * threads + n) + " CONTENT {nome: 'pessoa', n: " + i + "};",
                            session, null);
                    } else {
                        // um registro e uma chave de índice no mesmo commit
                        db.commit(Map.of(), Map.of("r-" + n + "-" + i, value, "i-" + n + "-" + (i % 1000), value),
                            Set.of());
                    }
                    writes.increment();
                }
            }));
        }
        Thread.sleep(WARMUP_MS);
        long before = writes.sum();
        long start = System.nanoTime();
        Thread.sleep(measureMs);
        long done = writes.sum() - before;
        double seconds = (System.nanoTime() - start) / 1e9;
        stop.set(true);
        for (Thread w : workers) {
            w.join();
        }
        String extra = journaled == null ? "" : journaled.finish();
        db.close();
        System.out.printf(java.util.Locale.ROOT, "RESULT ops=%.0f%s%n", done / seconds, extra);
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }
}
