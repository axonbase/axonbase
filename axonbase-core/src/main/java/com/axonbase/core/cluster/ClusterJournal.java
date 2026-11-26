package com.axonbase.core.cluster;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.journal.PitrJournal;
import com.axonbase.core.storage.CommitWatermark;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * O journal de PITR (Point-In-Time Recovery) de um nó de cluster: arquiva cada lote confirmado, em ordem de índice do
 * log e com o carimbo posto pelo líder. A marca d'água é o último lote confirmado entregue, então todo lote até ela
 * está nos segmentos deste nó, salvo o que ele recebeu por snapshot, que outro nó cobre.
 *
 * <p>Envolve os ganchos que já existiam (o carimbo de tempo), porque o consenso aceita um só.</p>
 */
public final class ClusterJournal implements ReplicationHooks, AutoCloseable {

    private final ReplicationHooks next;
    private final AtomicReference<CommitWatermark> committed;
    private final PitrJournal journal;

    private ClusterJournal(ReplicationHooks next, AtomicReference<CommitWatermark> committed, PitrJournal journal) {
        this.next = next;
        this.committed = committed;
        this.journal = journal;
    }

    /**
     * @param directory onde ficam os segmentos deste nó
     * @param next      os ganchos que continuam valendo, normalmente {@link ClockHooks}
     */
    public static ClusterJournal open(Path directory, JournalOptions options, ReplicationHooks next) {
        Objects.requireNonNull(next, "next");
        AtomicReference<CommitWatermark> committed = new AtomicReference<>(CommitWatermark.NONE);
        PitrJournal journal = PitrJournal.open(directory, options, committed::get);
        committed.accumulateAndGet(journal.archived(), ClusterJournal::latest);
        return new ClusterJournal(next, committed, journal);
    }

    public PitrJournal journal() {
        return journal;
    }

    /** O que veio depois do último lote arquivado pode ter ficado na fila quando o nó caiu. */
    @Override
    public long replayFrom() {
        long archived = journal.archived().index();
        // um journal novo não pede a história: quem liga o PITR começa a arquivar dali em diante
        return archived == 0 ? Long.MAX_VALUE : archived + 1;
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
        // a ordem importa: primeiro o lote na fila, depois a marca que o promete
        journal.offer(index, batch);
        committed.accumulateAndGet(new CommitWatermark(batch.stamp(), index), ClusterJournal::latest);
    }

    private static CommitWatermark latest(CommitWatermark a, CommitWatermark b) {
        return new CommitWatermark(HlcTimestamp.max(a.stamp(), b.stamp()), Math.max(a.index(), b.index()));
    }

    @Override
    public void close() {
        journal.close();
    }
}
