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
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * O journal de PITR (Point-In-Time Recovery) de um nó de cluster, com um shard só ou vários: cada entrada do Raft
 * aplicada é arquivada com o índice do log e o carimbo do líder. Envolve os ganchos que já existiam (normalmente o
 * {@link ClockHooks}, do relógio híbrido), porque o consenso aceita um só. É o único journal de cluster: serve ao
 * backup de cluster com shards e à recuperação de desastre.
 *
 * <p>No cluster o índice do journal é o do log, e a ordem dos índices é a ordem dos carimbos. A marca d'água é a
 * última entrada entregue, retomada dos segmentos ao reabrir, e o que veio depois do arquivado é pedido de volta ao
 * nó ({@link #replayFrom}). Um nó que recebe o estado inteiro pela rede não vê as entradas puladas: fica um buraco,
 * que outro nó cobre ou que a restauração se recusa a atravessar.</p>
 */
public final class ShardJournalHooks implements ReplicationHooks, AutoCloseable {

    /** Chave que marca uma entrada de barreira; o valor é a encarnação do shard. */
    public static final String BARRIER_KEY = "__shard/barrier";
    /** Quantas barreiras recentes ficam lembradas para quem as pediu perguntar o carimbo. */
    private static final int REMEMBERED = 64;

    private final ReplicationHooks next;
    private final Path directory;
    private final PitrJournal journal;
    /** Carimbo de cada barreira recente, por índice. */
    private final Map<Long, HlcTimestamp> barriers = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, HlcTimestamp> eldest) {
            return size() > REMEMBERED;
        }
    };
    private final AtomicReference<CommitWatermark> applied = new AtomicReference<>(CommitWatermark.NONE);

    private ShardJournalHooks(Path directory, JournalOptions options, ReplicationHooks next) {
        this.next = Objects.requireNonNull(next, "next");
        this.directory = directory;
        // a marca d'água é a última entrada já entregue ao journal
        this.journal = PitrJournal.open(directory, options, applied::get);
        // ao reabrir ela continua de onde os segmentos pararam, em vez de recomeçar do zero
        applied.accumulateAndGet(journal.archived(), ShardJournalHooks::latest);
    }

    /**
     * @param clock o relógio do armazenamento do nó
     */
    public static ShardJournalHooks open(HybridClock clock, Path directory, JournalOptions options) {
        return open(directory, options, new ClockHooks(clock));
    }

    /**
     * @param directory onde ficam os segmentos deste nó
     * @param next      os ganchos que continuam valendo, normalmente {@link ClockHooks}
     */
    public static ShardJournalHooks open(Path directory, JournalOptions options, ReplicationHooks next) {
        return new ShardJournalHooks(directory, options, next);
    }

    /** O que veio depois do último lote arquivado pode ter ficado na fila quando o nó caiu. */
    @Override
    public long replayFrom() {
        long archived = journal.archived().index();
        // um journal novo não pede a história: quem liga o PITR começa a arquivar dali em diante
        long own = archived == 0 ? Long.MAX_VALUE : archived + 1;
        return Math.min(own, next.replayFrom());
    }

    @Override
    public void recovered(CommittedBatch batch) {
        next.recovered(batch);
    }

    @Override
    public CommittedBatch stamp(CommittedBatch batch) {
        return next.stamp(batch);
    }

    @Override
    public boolean accept(CommittedBatch batch) {
        return next.accept(batch);
    }

    @Override
    public void committed(long index, long term, CommittedBatch batch) {
        next.committed(index, term, batch);
        // entra na fila antes de a marca d'água andar, para a marca nunca passar do que o journal recebeu
        journal.offer(index, batch);
        applied.accumulateAndGet(new CommitWatermark(batch.stamp(), index), ShardJournalHooks::latest);
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
        await(() -> applied.get().index() >= index, timeout, index);
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

    private static CommitWatermark latest(CommitWatermark a, CommitWatermark b) {
        return new CommitWatermark(HlcTimestamp.max(a.stamp(), b.stamp()), Math.max(a.index(), b.index()));
    }

    @Override
    public void close() {
        journal.close();
    }
}
