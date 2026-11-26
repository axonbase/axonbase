package com.axonbase.core.journal;

import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.BatchSink;
import com.axonbase.core.storage.CommitWatermark;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * O journal de PITR (Point-In-Time Recovery): arquiva cada lote confirmado, em segmentos que fecham por idade ou
 * tamanho.
 *
 * <p><b>Nunca bloqueia o commit.</b> {@link #offer} só põe o lote numa fila limitada; uma thread própria escreve.
 * Com a fila cheia o lote é descartado e contado: o índice dele fica faltando, e a restauração não passa desse
 * ponto sem um backup físico mais novo.</p>
 *
 * <p><b>Marca d'água.</b> Ao fechar um segmento, a marca é lida primeiro e a fila é esvaziada depois, então todo
 * lote até a marca está no segmento ou nos anteriores. Parado, o journal fecha segmentos vazios para a marca
 * continuar andando.</p>
 */
public final class PitrJournal implements BatchSink, AutoCloseable {

    private static final int CHUNK = 1_000;
    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long SYNC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private record Item(JournalRecord record, long bytes) {
    }

    private final Path directory;
    private final JournalOptions options;
    private final Supplier<CommitWatermark> watermark;
    private final LinkedBlockingQueue<Item> queue;
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong writeErrors = new AtomicLong();
    private final CopyOnWriteArrayList<SegmentInfo> sealed = new CopyOnWriteArrayList<>();
    /** Dona da fila (só quem a tem retira lotes) e do segmento aberto. */
    private final ReentrantLock writing = new ReentrantLock(true);
    private final Thread writer;
    private SegmentWriter open;
    private long openedAtNanos;
    private long lastSealNanos = System.nanoTime();
    private long lastSyncNanos = System.nanoTime();
    private HlcTimestamp lastNamed = HlcTimestamp.NONE;
    private volatile int openBatches;
    private volatile long lastSealMillis;
    private volatile boolean closing;

    private PitrJournal(Path directory, JournalOptions options, Supplier<CommitWatermark> watermark)
            throws IOException {
        this.directory = directory;
        this.options = options;
        this.watermark = watermark;
        this.queue = new LinkedBlockingQueue<>(options.queueMaxBatches());
        Files.createDirectories(directory);
        JournalSegments.recover(directory);
        sealed.addAll(JournalSegments.list(directory));
        this.writer = Thread.ofPlatform().name("axon-pitr-journal").daemon(true).unstarted(this::run);
    }

    /** Abre o journal do diretório, recuperando o que uma queda tenha deixado aberto. */
    public static PitrJournal open(Path directory, JournalOptions options, Supplier<CommitWatermark> watermark) {
        try {
            PitrJournal journal = new PitrJournal(directory, options, watermark);
            journal.writer.start();
            return journal;
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível abrir o journal em " + directory, e);
        }
    }

    /**
     * Abre o journal e passa a arquivar os commits do armazenamento. Se a base estiver atrás do que o journal já
     * arquivou, os índices dela recomeçam depois (veja {@link RocksDbBackend#resumeAfter}).
     */
    public static PitrJournal attach(RocksDbBackend backend, Path directory, JournalOptions options) {
        PitrJournal journal = open(directory, options, backend::watermark);
        CommitWatermark archived = journal.archived();
        backend.resumeAfter(archived.index(), archived.stamp());
        backend.commitSink(journal);
        return journal;
    }

    @Override
    public void offer(long index, CommittedBatch batch) {
        if (closing) {
            writeLate(new Item(new JournalRecord(index, batch), 0));
            return;
        }
        long bytes = sizeOf(batch);
        long total = queuedBytes.addAndGet(bytes);
        // um lote maior que o limite ainda passa quando está sozinho
        if ((total > options.queueMaxBytes() && total != bytes)
            || !queue.offer(new Item(new JournalRecord(index, batch), bytes))) {
            queuedBytes.addAndGet(-bytes);
            if (dropped.getAndIncrement() % 10_000 == 0) {
                System.err.println("{\"event\":\"pitr_batch_dropped\",\"index\":" + index + ",\"reason\":\"queue_full\"}");
            }
        }
    }

    /**
     * Uma escrita que chega depois do fechamento (trabalho em segundo plano durante o desligamento) vai direto ao
     * disco, atrás do que ainda estiver na fila. A próxima abertura recupera o segmento.
     */
    private void writeLate(Item item) {
        writing.lock();
        try {
            List<Item> items = new ArrayList<>();
            queue.drainTo(items);
            items.add(item);
            write(items);
        } catch (IOException e) {
            abandon(e);
        } finally {
            writing.unlock();
        }
    }

    /** Fecha agora o segmento aberto (ou grava um vazio) com a marca d'água deste instante. */
    public SegmentInfo seal() {
        writing.lock();
        try {
            return sealLocked();
        } catch (IOException e) {
            abandon(e);
            throw new UncheckedIOException("falha ao fechar o segmento do journal", e);
        } finally {
            writing.unlock();
        }
    }

    /** Os segmentos fechados, em ordem de escrita. */
    public List<SegmentInfo> segments() {
        return List.copyOf(sealed);
    }

    /** Apaga um segmento. Devolve {@code false} quando não existe. */
    public boolean delete(String name) {
        Path file = JournalSegments.resolve(directory, null, name);
        writing.lock();
        try {
            boolean existed = Files.deleteIfExists(file);
            sealed.removeIf(s -> s.name().equals(name));
            return existed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writing.unlock();
        }
    }

    /** Até onde os segmentos fechados vão: o maior índice e o maior carimbo que eles citam. */
    public CommitWatermark archived() {
        HlcTimestamp stamp = HlcTimestamp.NONE;
        long index = 0;
        for (SegmentInfo s : sealed) {
            stamp = HlcTimestamp.max(stamp, newestStamp(s));
            index = Math.max(index, Math.max(s.lastIndex(), s.complete().index()));
        }
        return new CommitWatermark(stamp, index);
    }

    /** Apaga os segmentos mais velhos que a retenção, preservando sempre o mais novo. */
    public void applyRetention(long nowMillis) {
        Duration retention = options.retention();
        if (retention.isZero() || retention.isNegative()) {
            return;
        }
        long cutoff = nowMillis - retention.toMillis();
        List<SegmentInfo> current = segments();
        for (int i = 0; i < current.size() - 1; i++) {
            if (newestStamp(current.get(i)).physical() < cutoff) {
                delete(current.get(i).name());
            }
        }
    }

    public Path directory() {
        return directory;
    }

    /** Lotes descartados porque a fila estava cheia, desde a abertura. */
    public long droppedBatches() {
        return dropped.get();
    }

    /** Falhas de escrita em disco desde a abertura. */
    public long writeErrors() {
        return writeErrors.get();
    }

    public int pendingBatches() {
        return queue.size();
    }

    public int openSegmentBatches() {
        return openBatches;
    }

    /** Instante (relógio do sistema) do último segmento fechado nesta execução, ou 0. */
    public long lastSealMillis() {
        return lastSealMillis;
    }

    /** Escreve o que está na fila, fecha o segmento e para a thread. */
    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        LockSupport.unpark(writer);
        try {
            writer.join(TimeUnit.SECONDS.toMillis(60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writing.lock();
        try {
            if (open != null || !queue.isEmpty()) {
                sealLocked();
            }
        } catch (IOException e) {
            abandon(e);
        } finally {
            writing.unlock();
        }
    }

    // ---- thread de escrita ----

    private void run() {
        while (!closing) {
            boolean busy = false;
            writing.lock();
            try {
                busy = writeChunk();
                maintain();
            } catch (IOException e) {
                abandon(e);
            } catch (RuntimeException e) {
                writeErrors.incrementAndGet();
                System.err.println("{\"event\":\"pitr_journal_error\",\"error\":\"" + e.getMessage() + "\"}");
            } finally {
                writing.unlock();
            }
            if (!busy) {
                LockSupport.parkNanos(IDLE_PARK_NANOS);
            }
        }
    }

    private boolean writeChunk() throws IOException {
        List<Item> items = new ArrayList<>(CHUNK);
        queue.drainTo(items, CHUNK);
        write(items);
        return items.size() == CHUNK;
    }

    private void maintain() throws IOException {
        long now = System.nanoTime();
        if (open != null) {
            if (now - openedAtNanos >= options.maxSegmentAge().toNanos()) {
                sealLocked();
            }
        } else if (!options.idleHeartbeat().isZero() && now - lastSealNanos >= options.idleHeartbeat().toNanos()) {
            sealLocked();
        }
        if (open != null && now - lastSyncNanos >= SYNC_INTERVAL_NANOS) {
            open.sync();
            lastSyncNanos = now;
        }
    }

    /** Grava os lotes, virando de segmento pelo tamanho. Os intermediários não prometem cobertura nova. */
    private void write(List<Item> items) throws IOException {
        for (Item item : items) {
            queuedBytes.addAndGet(-item.bytes());
            if (open == null) {
                openSegment(watermark.get().stamp());
            }
            open.append(item.record());
            if (open.size() >= options.maxSegmentBytes()) {
                finish(lastComplete());
            }
        }
        if (open != null) {
            open.flush();
            openBatches = open.batches();
        }
    }

    private SegmentInfo sealLocked() throws IOException {
        // a ordem importa: primeiro a marca, depois a fila; todo lote até a marca já foi oferecido
        CommitWatermark mark = watermark.get();
        List<Item> items = new ArrayList<>();
        queue.drainTo(items);
        write(items);
        if (open == null) {
            openSegment(mark.stamp());
        }
        return finish(mark);
    }

    private void openSegment(HlcTimestamp created) throws IOException {
        HlcTimestamp name = HlcTimestamp.max(created, after(newestName()));
        while (Files.exists(directory.resolve(JournalSegments.nameFor(name)))) {
            name = after(name);
        }
        lastNamed = name;
        open = new SegmentWriter(directory, JournalSegments.nameFor(name));
        openedAtNanos = System.nanoTime();
        openBatches = 0;
    }

    /** O carimbo do nome mais novo já usado, para os nomes nunca voltarem atrás. */
    private HlcTimestamp newestName() {
        if (!lastNamed.isNone() || sealed.isEmpty()) {
            return lastNamed;
        }
        String name = sealed.get(sealed.size() - 1).name();
        return new HlcTimestamp(Long.parseLong(name.substring(4, 19)), Integer.parseInt(name.substring(20, 30)));
    }

    private static HlcTimestamp after(HlcTimestamp stamp) {
        if (stamp.isNone()) {
            return stamp;
        }
        return stamp.logical() == Integer.MAX_VALUE ? new HlcTimestamp(stamp.physical() + 1, 0)
            : new HlcTimestamp(stamp.physical(), stamp.logical() + 1);
    }

    private CommitWatermark lastComplete() {
        return sealed.isEmpty() ? CommitWatermark.NONE : sealed.get(sealed.size() - 1).complete();
    }

    private SegmentInfo finish(CommitWatermark complete) throws IOException {
        SegmentInfo info = open.seal(complete);
        open = null;
        openBatches = 0;
        sealed.add(info);
        lastSealNanos = System.nanoTime();
        lastSealMillis = System.currentTimeMillis();
        applyRetention(newestStamp(info).physical());
        return info;
    }

    /** Larga o segmento com defeito; o que não foi gravado aparece como índice faltando. */
    private void abandon(IOException cause) {
        writeErrors.incrementAndGet();
        lastSealNanos = System.nanoTime();
        System.err.println("{\"event\":\"pitr_journal_write_failed\",\"error\":\"" + cause.getMessage() + "\"}");
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // a próxima abertura recupera o que for legível
            }
            open = null;
            openBatches = 0;
        }
    }

    /** O carimbo mais novo que o segmento cita: a idade dele para a retenção. */
    private static HlcTimestamp newestStamp(SegmentInfo segment) {
        return HlcTimestamp.max(segment.lastStamp(), segment.complete().stamp());
    }

    private static long sizeOf(CommittedBatch batch) {
        long size = 64;
        for (var put : batch.puts().entrySet()) {
            size += put.getKey().length() * 2L + put.getValue().length;
        }
        for (String key : batch.deletes()) {
            size += key.length() * 2L;
        }
        return size;
    }
}
