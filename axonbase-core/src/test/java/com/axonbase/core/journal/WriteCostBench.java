package com.axonbase.core.journal;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.BatchSink;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Medida do portão de custo da E2: vazão de escrita. Um processo por medida, para comparar classpaths diferentes
 * (veja {@code scripts/bench-pitr.py}). Só em máquina dedicada.
 *
 * <pre>java ... WriteCostBench &lt;variante&gt; &lt;kv|sql&gt; &lt;diretório&gt; &lt;segundos&gt; [threads]</pre>
 *
 * <p>{@code plain} usa só a API que existe desde a 0.10, então roda também contra as classes antigas (a base da
 * comparação). {@code journal} liga o journal de PITR com as opções padrão. {@code kv} grava direto no armazenamento
 * (o pior caso); {@code sql} passa pelo motor com {@code CREATE}.</p>
 *
 * <p>As demais variantes separam de onde vem o custo do journal ou experimentam outra política de sincronização:</p>
 * <ul>
 * <li>{@code copy-only}: só o trabalho do journal dentro do commit (contar, serializar e enfileirar) e uma thread
 *     que descarta. Nada é escrito.</li>
 * <li>{@code journal-tmpfs}: o journal inteiro, com os segmentos em memória ({@code AXON_BENCH_SHM}, padrão
 *     {@code /dev/shm}): serialização e escrita sem disco.</li>
 * <li>{@code journal-nosync}: no disco, sem sincronizar durante a medida. Não vale para produção.</li>
 * <li>{@code journal-sync4m}: sincroniza a cada 4 MB, além de a cada segundo.</li>
 * <li>{@code journal-sync200ms}: sincroniza a cada 200 ms.</li>
 * <li>{@code journal-pause50ms}: a thread de escrita dorme 50 ms, em vez de 10, quando a fila esvazia.</li>
 * </ul>
 */
public final class WriteCostBench {

    private static final long WARMUP_MS = 3_000;

    private WriteCostBench() {
    }

    /** Carregada só nas variantes com journal, para a variante simples rodar com classes sem ele. */
    private static final class Journaled {
        private final PitrJournal journal;
        private final Path memoryDirectory;

        Journaled(String variant, RocksDbBackend db, Path work) throws IOException {
            if (variant.equals("journal-tmpfs")) {
                String shm = System.getenv().getOrDefault("AXON_BENCH_SHM", "/dev/shm");
                this.memoryDirectory = Files.createTempDirectory(Path.of(shm), "axon-journal-");
            } else {
                this.memoryDirectory = null;
            }
            Path dir = memoryDirectory != null ? memoryDirectory : work.resolve("journal");
            this.journal = PitrJournal.attach(db, dir, options(variant));
        }

        private static JournalOptions options(String variant) {
            JournalOptions defaults = JournalOptions.defaults();
            return switch (variant) {
                // em memória, os segmentos não podem se acumular até encher o tmpfs
                case "journal-tmpfs" -> defaults.withRetention(Duration.ofSeconds(2));
                case "journal" -> defaults;
                case "journal-nosync" -> defaults.withSync(Duration.ofDays(1), 0)
                    .withMaxSegmentBytes(Long.MAX_VALUE).withMaxSegmentAge(Duration.ofDays(1));
                case "journal-sync4m" -> defaults.withSync(Duration.ofSeconds(1), 4L << 20);
                case "journal-sync200ms" -> defaults.withSync(Duration.ofMillis(200), 0);
                case "journal-pause50ms" -> defaults.withWriterPause(Duration.ofMillis(50));
                default -> throw new IllegalArgumentException("variante desconhecida: " + variant);
            };
        }

        String finish() throws IOException {
            journal.close();
            long archived = journal.segments().stream().mapToLong(SegmentInfo::batches).sum();
            String result = " dropped=" + journal.droppedBatches() + " archived=" + archived + " syncs="
                + journal.syncCount();
            if (memoryDirectory != null) {
                try (var files = Files.walk(memoryDirectory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
            return result;
        }
    }

    /** O custo dentro do commit, sem escrita nenhuma, com os mesmos contadores do journal. */
    private static final class CopyOnly implements BatchSink {
        private final ConcurrentLinkedQueue<PendingBatch> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queuedBatches = new AtomicInteger();
        private final AtomicLong queuedBytes = new AtomicLong();

        CopyOnly() {
            Thread.ofPlatform().daemon(true).start(() -> {
                while (true) {
                    int batches = 0;
                    long bytes = 0;
                    PendingBatch next;
                    while (batches < 1_000 && (next = queue.poll()) != null) {
                        batches++;
                        bytes += next.bytes();
                    }
                    queuedBatches.addAndGet(-batches);
                    queuedBytes.addAndGet(-bytes);
                    if (batches < 1_000) {
                        LockSupport.parkNanos(10_000_000);
                    }
                }
            });
        }

        @Override
        public void offer(long index, CommittedBatch batch) {
            committed(index, batch.stamp(), batch.puts(), batch.deletes());
        }

        @Override
        public void committed(long index, HlcTimestamp stamp, Map<String, byte[]> puts, Set<String> deletes) {
            long bytes = PendingBatch.sizeOf(puts, deletes);
            queuedBatches.incrementAndGet();
            queuedBytes.addAndGet(bytes);
            queue.add(PendingBatch.encode(index, stamp, puts, deletes, bytes));
        }
    }

    public static void main(String[] args) throws Exception {
        String variant = args[0];
        boolean sql = args[1].equals("sql");
        Path dir = Path.of(args[2]);
        long measureMs = Long.parseLong(args[3]) * 1_000;
        int threads = args.length > 4 ? Integer.parseInt(args[4]) : 8;

        RocksDbBackend db = new RocksDbBackend(dir.resolve("db").toString(), RocksDbBackend.SyncMode.BATCH);
        Journaled journaled = variant.startsWith("journal") ? new Journaled(variant, db, dir) : null;
        if (variant.equals("copy-only")) {
            db.commitSink(new CopyOnly());
        }
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
