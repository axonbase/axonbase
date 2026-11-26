package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.CommitWatermark;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Um segmento sendo escrito ({@code <nome>.open}). Não é seguro entre threads: o {@link PitrJournal} o usa sob a
 * própria trava. Fechar grava o rodapé, sincroniza e renomeia para o nome final.
 */
final class SegmentWriter implements AutoCloseable {

    private final Path directory;
    private final String name;
    private final Path openPath;
    private final long timeline;
    private final FileChannel channel;
    private final Stats stats = new Stats();
    /** Reaproveitado entre segmentos pelo dono, que o usa sob a própria trava. */
    private final ByteBuffer buffer;
    private final CRC32C crc = new CRC32C();
    private long size;
    private boolean unsynced;

    /** Faixa de carimbos e índices dos lotes de um segmento. */
    static final class Stats {
        private HlcTimestamp firstStamp = HlcTimestamp.NONE;
        private HlcTimestamp lastStamp = HlcTimestamp.NONE;
        private long[] indexes = new long[64];
        private int count;

        void add(long index, HlcTimestamp stamp) {
            if (count == indexes.length) {
                indexes = Arrays.copyOf(indexes, count * 2);
            }
            indexes[count++] = index;
            if (count == 1 || stamp.compareTo(firstStamp) < 0) {
                firstStamp = stamp;
            }
            lastStamp = HlcTimestamp.max(lastStamp, stamp);
        }

        int count() {
            return count;
        }

        SegmentFormat.Trailer trailer(CommitWatermark complete, boolean recovered) {
            long[] sorted = Arrays.copyOf(indexes, count);
            Arrays.sort(sorted);
            List<IndexRange> holes = new ArrayList<>();
            for (int i = 1; i < sorted.length; i++) {
                if (sorted[i] > sorted[i - 1] + 1) {
                    holes.add(new IndexRange(sorted[i - 1] + 1, sorted[i] - 1));
                }
            }
            long first = count == 0 ? 0 : sorted[0];
            long last = count == 0 ? 0 : sorted[count - 1];
            return new SegmentFormat.Trailer(complete, firstStamp, lastStamp, first, last, count, holes, recovered);
        }
    }

    SegmentWriter(Path directory, String name, long timeline, ByteBuffer buffer) throws IOException {
        this.buffer = buffer.clear();
        this.directory = directory;
        this.name = name;
        this.timeline = timeline;
        this.openPath = directory.resolve(JournalSegments.openName(name));
        this.channel = FileChannel.open(openPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            write(SegmentFormat.header(timeline));
            flush();
        } catch (IOException e) {
            // disco cheio, por exemplo: sem isto o descritor e o arquivo vazio ficariam para trás
            channel.close();
            Files.deleteIfExists(openPath);
            throw e;
        }
    }

    /** Acrescenta um lote. Ele só chega ao disco no próximo {@link #flush()}. */
    void append(PendingBatch batch) throws IOException {
        SegmentFormat.BatchBytes record = new SegmentFormat.BatchBytes(batch);
        int length = record.size();
        if (length > buffer.remaining()) {
            flush();
        }
        if (length > buffer.capacity()) {
            ByteBuffer large = ByteBuffer.allocate(length);
            record.writeTo(large, crc);
            drain(large.flip());
        } else {
            record.writeTo(buffer, crc);
        }
        size += length;
        stats.add(batch.index(), batch.stamp());
    }

    long size() {
        return size;
    }

    int batches() {
        return stats.count();
    }

    SegmentInfo seal(CommitWatermark complete) throws IOException {
        SegmentFormat.Trailer trailer = stats.trailer(complete, false);
        write(SegmentFormat.trailerAndFooter(trailer));
        flush();
        channel.force(true);
        channel.close();
        return publish(directory, openPath, name, size, timeline, trailer);
    }

    /** Renomeia o arquivo fechado para o nome final e devolve o índice dele. */
    static SegmentInfo publish(Path directory, Path openPath, String name, long size, long timeline,
                               SegmentFormat.Trailer t) throws IOException {
        Files.move(openPath, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(directory);
        return JournalSegments.toInfo(name, size, timeline, t);
    }

    private static void syncDirectory(Path directory) {
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException ignored) {
            // nem todo sistema de arquivos deixa sincronizar o diretório
        }
    }

    /** Entrega ao sistema operacional o que está em memória: a partir daqui sobrevive a um kill -9. */
    void flush() throws IOException {
        if (buffer.position() == 0) {
            return;
        }
        try {
            drain(buffer.flip());
        } finally {
            buffer.clear();
        }
    }

    private void drain(ByteBuffer from) throws IOException {
        while (from.hasRemaining()) {
            channel.write(from);
        }
        unsynced = true;
    }

    /** Sincroniza em disco o que já foi entregue: limita a perda numa queda de energia. */
    void sync() throws IOException {
        if (unsynced) {
            channel.force(false);
            unsynced = false;
        }
    }

    /** Cabeçalho e rodapé. */
    private void write(byte[] bytes) throws IOException {
        if (bytes.length > buffer.remaining()) {
            flush();
        }
        if (bytes.length > buffer.capacity()) {
            drain(ByteBuffer.wrap(bytes));
        } else {
            buffer.put(bytes);
        }
        size += bytes.length;
    }

    /** Larga o arquivo aberto como está; a próxima abertura do journal o recupera. */
    @Override
    public void close() throws IOException {
        channel.close();
    }
}
