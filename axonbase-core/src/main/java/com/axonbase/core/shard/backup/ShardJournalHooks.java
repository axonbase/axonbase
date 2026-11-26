package com.axonbase.core.shard.backup;

import com.axonbase.core.cluster.ClockHooks;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.cluster.ReplicationHooks;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.journal.PitrJournal;
import com.axonbase.core.storage.CommitWatermark;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * O journal de PITR (Point-In-Time Recovery) de um nó de shard: cada entrada do Raft aplicada é arquivada com o
 * índice do log e o carimbo do líder. Faz também o que o {@link ClockHooks} faz com o relógio híbrido.
 *
 * <p>No cluster o índice do journal é o do log, e a ordem dos índices é a ordem dos carimbos. Um nó que recebe o
 * estado inteiro pela rede não vê as entradas puladas: fica um buraco, que a restauração se recusa a atravessar.</p>
 */
public final class ShardJournalHooks implements ReplicationHooks, AutoCloseable {

    /** Chave que marca uma entrada de barreira; o valor é a encarnação do shard. */
    static final String BARRIER_KEY = "__shard/barrier";
    /** Quantas barreiras recentes ficam lembradas para quem as pediu perguntar o carimbo. */
    private static final int REMEMBERED = 64;

    private final ClockHooks clockHooks;
    private final Path directory;
    private final PitrJournal journal;
    /** Carimbo de cada barreira recente, por índice. */
    private final Map<Long, HlcTimestamp> barriers = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, HlcTimestamp> eldest) {
            return size() > REMEMBERED;
        }
    };
    private volatile CommitWatermark applied = CommitWatermark.NONE;

    private ShardJournalHooks(HybridClock clock, Path directory, JournalOptions options) {
        this.clockHooks = new ClockHooks(clock);
        this.directory = directory;
        // a marca d'água é a última entrada já entregue ao journal
        this.journal = PitrJournal.open(directory, options, () -> applied);
    }

    /**
     * @param clock o relógio do armazenamento do nó
     */
    public static ShardJournalHooks open(HybridClock clock, Path directory, JournalOptions options) {
        return new ShardJournalHooks(clock, directory, options);
    }

    @Override
    public void recovered(CommittedBatch batch) {
        clockHooks.recovered(batch);
    }

    @Override
    public CommittedBatch stamp(CommittedBatch batch) {
        return clockHooks.stamp(batch);
    }

    @Override
    public boolean accept(CommittedBatch batch) {
        return clockHooks.accept(batch);
    }

    @Override
    public void committed(long index, long term, CommittedBatch batch) {
        // entra na fila antes de a marca d'água andar, para a marca nunca passar do que o journal recebeu
        journal.offer(index, batch);
        applied = new CommitWatermark(batch.stamp(), index);
        synchronized (barriers) {
            if (batch.puts().containsKey(BARRIER_KEY)) {
                barriers.put(index, batch.stamp());
            }
            barriers.notifyAll();
        }
    }

    public Path directory() {
        return directory;
    }

    public PitrJournal journal() {
        return journal;
    }

    /** Fecha o segmento aberto do journal. */
    public void seal() {
        journal.seal();
    }

    /** Espera a barreira do índice chegar ao journal e devolve o carimbo dela. */
    HlcTimestamp awaitBarrier(long index, Duration timeout) {
        await(() -> barriers.containsKey(index), timeout, index);
        synchronized (barriers) {
            return barriers.get(index);
        }
    }

    /** Espera o journal ter recebido a entrada do índice. */
    void awaitIndex(long index, Duration timeout) {
        await(() -> applied.index() >= index, timeout, index);
    }

    private void await(BooleanSupplier reached, Duration timeout, long index) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (barriers) {
            while (!reached.getAsBoolean()) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    throw ClusterBackup.unavailable("backup_lagging", "journal", index);
                }
                try {
                    barriers.wait(Math.min(left, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw ClusterBackup.unavailable("backup_lagging", "journal", index);
                }
            }
        }
    }

    @Override
    public void close() {
        journal.close();
    }
}
