package com.axonbase.core.cluster;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * Um arquivo do log Raft: cabeçalho e registros com checksum.
 *
 * <pre>
 * cabeçalho: magic (int) | versão (int) | primeiro índice (long)
 * registro:  magic (int) | tamanho do corpo (int) | CRC32C de tamanho+corpo (int) | corpo
 * corpo:     índice (long) | termo (long) | lote codificado (opaco)
 * </pre>
 *
 * <p>Não é thread-safe: o {@link FileRaftLog} serializa o acesso. Só {@link #force()} pode rodar em paralelo
 * com a escrita.</p>
 */
final class LogSegment {

    static final String SUFFIX = ".seg";
    static final int HEADER_BYTES = 16;
    private static final int FILE_MAGIC = 0x41585253;
    private static final int VERSION = 1;
    private static final int RECORD_MAGIC = 0x52454331;
    private static final int RECORD_PREFIX = 12;
    private static final int BODY_PREFIX = 16;

    private final Path path;
    private final long firstIndex;
    private final RandomAccessFile file;
    private volatile FileChannel syncChannel;
    private long[] offsets = new long[64];
    private long[] terms = new long[64];
    private int count;
    private long size;

    private LogSegment(Path path, long firstIndex) throws IOException {
        this.path = path;
        this.firstIndex = firstIndex;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        this.size = HEADER_BYTES;
    }

    static String fileName(long firstIndex) {
        return String.format("%020d%s", firstIndex, SUFFIX);
    }

    /** Cria um segmento vazio, já durável (arquivo e entrada no diretório). */
    static LogSegment create(Path directory, long firstIndex) throws IOException {
        Path path = directory.resolve(fileName(firstIndex));
        Files.deleteIfExists(path);
        LogSegment segment = new LogSegment(path, firstIndex);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(FILE_MAGIC).putInt(VERSION).putLong(firstIndex);
        segment.file.write(header.array());
        segment.force();
        forceDirectory(directory);
        return segment;
    }

    /**
     * Abre um segmento existente conferindo cada registro.
     *
     * <p>Um registro inválido no último segmento é uma cauda cortada por uma queda: o arquivo é truncado ali. Se
     * houver um registro íntegro depois do ruim, é corrupção no meio: abaixo de {@code committedFloor} o
     * segmento não abre; acima dele (cauda ainda não confirmada) os bytes descartados vão para um arquivo
     * {@code .corrupt} e o log é cortado.</p>
     *
     * @return o segmento, ou {@code null} se era um segmento recém-criado sem cabeçalho completo
     */
    static LogSegment open(Path path, boolean last, long committedFloor) throws IOException {
        long expectedFirst = parseFirstIndex(path);
        byte[] raw = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        if (raw.length < HEADER_BYTES || buffer.getInt(0) != FILE_MAGIC || buffer.getInt(4) != VERSION
            || buffer.getLong(8) != expectedFirst) {
            if (last && raw.length <= HEADER_BYTES) {
                Files.delete(path);
                return null;
            }
            throw new CorruptRaftLogException("cabeçalho inválido no segmento do log Raft " + path);
        }
        LogSegment segment = new LogSegment(path, expectedFirst);
        try {
            int position = HEADER_BYTES;
            while (position < raw.length) {
                long index = expectedFirst + segment.count;
                int length = validRecordAt(buffer, position, raw.length);
                if (length < 0 || buffer.getLong(position + RECORD_PREFIX) != index) {
                    break;
                }
                segment.remember(position, buffer.getLong(position + RECORD_PREFIX + 8));
                position += RECORD_PREFIX + length;
            }
            segment.size = position;
            if (position < raw.length) {
                segment.cutDamagedTail(raw, position, last, committedFloor);
            }
            return segment;
        } catch (RuntimeException | IOException failure) {
            segment.close();
            throw failure;
        }
    }

    private void cutDamagedTail(byte[] raw, int position, boolean last, long committedFloor) throws IOException {
        long damaged = firstIndex + count;
        if (!last) {
            throw new CorruptRaftLogException("registro " + damaged + " corrompido no segmento " + path
                + ", que não é o último");
        }
        if (hasValidRecordAfter(raw, position + 1, damaged)) {
            if (damaged <= committedFloor) {
                throw new CorruptRaftLogException("registro " + damaged + " (já confirmado) corrompido no segmento "
                    + path);
            }
            Path kept = path.resolveSibling(path.getFileName() + "." + System.currentTimeMillis() + ".corrupt");
            Files.write(kept, Arrays.copyOfRange(raw, position, raw.length));
            System.getLogger(LogSegment.class.getName()).log(System.Logger.Level.WARNING,
                "log Raft cortado no índice " + damaged + "; bytes descartados guardados em " + kept);
        }
        file.setLength(position);
        force();
    }

    private static boolean hasValidRecordAfter(byte[] raw, int from, long damaged) {
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        for (int p = from; p + RECORD_PREFIX + BODY_PREFIX <= raw.length; p++) {
            if (buffer.getInt(p) == RECORD_MAGIC && validRecordAt(buffer, p, raw.length) >= 0
                && buffer.getLong(p + RECORD_PREFIX) >= damaged) {
                return true;
            }
        }
        return false;
    }

    /** Tamanho do corpo do registro íntegro em {@code position}, ou -1. */
    private static int validRecordAt(ByteBuffer buffer, int position, int limit) {
        if (limit - position < RECORD_PREFIX + BODY_PREFIX || buffer.getInt(position) != RECORD_MAGIC) {
            return -1;
        }
        int length = buffer.getInt(position + 4);
        if (length < BODY_PREFIX || length > limit - position - RECORD_PREFIX) {
            return -1;
        }
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), position + 4, 4);
        crc.update(buffer.array(), position + RECORD_PREFIX, length);
        return (int) crc.getValue() == buffer.getInt(position + 8) ? length : -1;
    }

    private static long parseFirstIndex(Path path) {
        String name = path.getFileName().toString();
        try {
            return Long.parseLong(name.substring(0, name.length() - SUFFIX.length()));
        } catch (NumberFormatException e) {
            throw new CorruptRaftLogException("nome de segmento inválido: " + path);
        }
    }

    long firstIndex() {
        return firstIndex;
    }

    /** Último índice gravado; {@code firstIndex - 1} num segmento vazio. */
    long lastIndex() {
        return firstIndex + count - 1;
    }

    boolean isEmpty() {
        return count == 0;
    }

    long size() {
        return size;
    }

    long term(long index) {
        return terms[slot(index)];
    }

    /** Acrescenta o registro do índice {@code lastIndex() + 1}. Não faz fsync. */
    void append(long term, byte[] payload) throws IOException {
        long index = lastIndex() + 1;
        int length = BODY_PREFIX + payload.length;
        ByteBuffer record = ByteBuffer.allocate(RECORD_PREFIX + length);
        record.putInt(RECORD_MAGIC).putInt(length).putInt(0).putLong(index).putLong(term).put(payload);
        CRC32C crc = new CRC32C();
        crc.update(record.array(), 4, 4);
        crc.update(record.array(), RECORD_PREFIX, length);
        record.putInt(8, (int) crc.getValue());
        file.seek(size);
        file.write(record.array());
        remember(size, term);
        size += record.capacity();
    }

    /** Lê do disco os bytes do lote, conferindo o checksum. */
    byte[] read(long index) throws IOException {
        int slot = slot(index);
        long start = offsets[slot];
        long end = slot + 1 < count ? offsets[slot + 1] : size;
        byte[] raw = new byte[Math.toIntExact(end - start)];
        file.seek(start);
        file.readFully(raw);
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        int length = validRecordAt(buffer, 0, raw.length);
        if (length < 0 || buffer.getLong(RECORD_PREFIX) != index) {
            throw new CorruptRaftLogException("checksum do registro " + index + " não confere em " + path);
        }
        return Arrays.copyOfRange(raw, RECORD_PREFIX + BODY_PREFIX, RECORD_PREFIX + length);
    }

    /** Descarta o registro {@code index} e os seguintes. */
    void truncateFrom(long index) throws IOException {
        int slot = slot(index);
        size = offsets[slot];
        count = slot;
        file.setLength(size);
        force();
    }

    /**
     * fsync do arquivo. Usa um canal próprio: se a thread chamadora for interrompida, o Java fecha o canal, e
     * ele é reaberto sem afetar o descritor usado para escrever e ler.
     */
    void force() throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                FileChannel channel = syncChannel;
                if (channel == null || !channel.isOpen()) {
                    channel = FileChannel.open(path, StandardOpenOption.WRITE);
                    syncChannel = channel;
                }
                try {
                    channel.force(false);
                    return;
                } catch (ClosedChannelException closed) {
                    interrupted |= Thread.interrupted();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void close() throws IOException {
        FileChannel channel = syncChannel;
        if (channel != null) {
            channel.close();
        }
        file.close();
    }

    void delete() throws IOException {
        close();
        Files.deleteIfExists(path);
    }

    private int slot(long index) {
        long slot = index - firstIndex;
        if (slot < 0 || slot >= count) {
            throw new IndexOutOfBoundsException("índice " + index + " fora do segmento " + path);
        }
        return (int) slot;
    }

    private void remember(long offset, long term) {
        if (count == offsets.length) {
            offsets = Arrays.copyOf(offsets, count * 2);
            terms = Arrays.copyOf(terms, count * 2);
        }
        offsets[count] = offset;
        terms[count] = term;
        count++;
    }

    /** Torna duráveis a criação, a remoção e a troca de nome de arquivos do diretório. */
    static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException unsupported) {
            // Alguns sistemas de arquivos não permitem fsync de diretório.
        }
    }

    /** Grava um arquivo pequeno de forma atômica e durável. */
    static void writeAtomically(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(target.toAbsolutePath().getParent());
    }
}
