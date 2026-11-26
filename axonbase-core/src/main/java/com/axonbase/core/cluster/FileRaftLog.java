package com.axonbase.core.cluster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Log Raft append-only por nó com FileChannel persistente, fsync e compactação. */
public final class FileRaftLog implements AutoCloseable {
    private final Path path;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Long, Entry> byIndex = new ConcurrentHashMap<>();
    private FileChannel channel;

    public FileRaftLog(Path directory) {
        try {
            Files.createDirectories(directory);
            path = directory.resolve("raft.log");
            recover();
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized void append(Entry entry) {
        try {
            ByteBuffer buf = serialize(entry);
            channel.write(buf);
            channel.force(true);
            entries.add(entry);
            byIndex.put(entry.index(), entry);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }
    public synchronized long lastIndex() { return entries.isEmpty() ? 0 : entries.getLast().index(); }
    public synchronized Entry entry(long index) { return byIndex.get(index); }
    public synchronized long termAt(long index) { Entry entry = byIndex.get(index); return entry == null ? 0 : entry.term(); }

    public synchronized void compact(long appliedIndex) {
        entries.removeIf(entry -> entry.index() <= appliedIndex);
        for (long i = 1; i <= appliedIndex; i++) byIndex.remove(i);
        try {
            channel.close();
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            for (Entry entry : entries) channel.write(serialize(entry));
            channel.force(true);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized void truncateFrom(long index) {
        entries.removeIf(entry -> entry.index() >= index);
        for (Entry entry : entries) byIndex.put(entry.index(), entry);
        try {
            channel.close();
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            for (Entry entry : entries) channel.write(serialize(entry));
            channel.force(true);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static ByteBuffer serialize(Entry entry) throws IOException {
        byte[] txBytes = entry.batch().transactionId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<byte[]> dels = new ArrayList<>();
        for (String k : entry.batch().deletes()) dels.add(k.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<byte[]> pKeys = new ArrayList<>();
        List<byte[]> pVals = new ArrayList<>();
        for (var put : entry.batch().puts().entrySet()) {
            pKeys.add(put.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pVals.add(put.getValue());
        }
        int size = 8 + 8 + 2 + txBytes.length + 4;
        for (byte[] d : dels) size += 2 + d.length;
        size += 4;
        for (int i = 0; i < pKeys.size(); i++) size += 2 + pKeys.get(i).length + 4 + pVals.get(i).length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putLong(entry.index()); buf.putLong(entry.term());
        buf.putShort((short) txBytes.length); buf.put(txBytes);
        buf.putInt(dels.size());
        for (byte[] d : dels) { buf.putShort((short) d.length); buf.put(d); }
        buf.putInt(pKeys.size());
        for (int i = 0; i < pKeys.size(); i++) {
            buf.putShort((short) pKeys.get(i).length); buf.put(pKeys.get(i));
            buf.putInt(pVals.get(i).length); buf.put(pVals.get(i));
        }
        buf.flip();
        return buf;
    }

    private static CommittedBatch readBatch(ByteBuffer buf) {
        short txLen = buf.getShort(); byte[] txRaw = new byte[txLen]; buf.get(txRaw);
        String tx = new String(txRaw, java.nio.charset.StandardCharsets.UTF_8);
        int dels = buf.getInt(); java.util.Set<String> deletes = new java.util.LinkedHashSet<>();
        for (int i = 0; i < dels; i++) {
            short kLen = buf.getShort(); byte[] kRaw = new byte[kLen]; buf.get(kRaw);
            deletes.add(new String(kRaw, java.nio.charset.StandardCharsets.UTF_8));
        }
        int count = buf.getInt(); Map<String, byte[]> puts = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            short kLen = buf.getShort(); byte[] kRaw = new byte[kLen]; buf.get(kRaw);
            int vLen = buf.getInt(); byte[] value = new byte[vLen]; buf.get(value);
            puts.put(new String(kRaw, java.nio.charset.StandardCharsets.UTF_8), value);
        }
        return new CommittedBatch(tx, puts, deletes);
    }

    /**
     * Relê o log do começo: cada entrada completa é mantida e a primeira incompleta (cauda cortada por uma queda
     * no meio da escrita) é descartada junto com o que vier depois, truncando o arquivo nesse ponto.
     */
    private void recover() throws IOException {
        if (!Files.exists(path)) return;
        byte[] raw = Files.readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int validEnd = 0;
        while (buf.remaining() >= 16) {
            int start = buf.position();
            Entry entry;
            try {
                long index = buf.getLong();
                long term = buf.getLong();
                entry = new Entry(index, term, readBatchChecked(buf));
            } catch (java.nio.BufferUnderflowException | NegativeArraySizeException e) {
                buf.position(start);
                break;
            }
            entries.add(entry);
            byIndex.put(entry.index(), entry);
            validEnd = buf.position();
        }
        if (validEnd < raw.length) {
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                ch.truncate(validEnd);
            }
        }
    }

    /** Como {@link #readBatch}, mas sem alocar antes de saber que os bytes existem. */
    private static CommittedBatch readBatchChecked(ByteBuffer buf) {
        short txLen = buf.getShort();
        if (txLen < 0 || buf.remaining() < txLen) throw new java.nio.BufferUnderflowException();
        byte[] txRaw = new byte[txLen];
        buf.get(txRaw);
        String tx = new String(txRaw, java.nio.charset.StandardCharsets.UTF_8);
        int dels = buf.getInt();
        if (dels < 0 || dels > buf.remaining()) throw new java.nio.BufferUnderflowException();
        java.util.Set<String> deletes = new java.util.LinkedHashSet<>();
        for (int i = 0; i < dels; i++) {
            short kLen = buf.getShort();
            if (kLen < 0 || buf.remaining() < kLen) throw new java.nio.BufferUnderflowException();
            byte[] kRaw = new byte[kLen];
            buf.get(kRaw);
            deletes.add(new String(kRaw, java.nio.charset.StandardCharsets.UTF_8));
        }
        int count = buf.getInt();
        if (count < 0 || count > buf.remaining()) throw new java.nio.BufferUnderflowException();
        Map<String, byte[]> puts = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            short kLen = buf.getShort();
            if (kLen < 0 || buf.remaining() < kLen) throw new java.nio.BufferUnderflowException();
            byte[] kRaw = new byte[kLen];
            buf.get(kRaw);
            int vLen = buf.getInt();
            if (vLen < 0 || buf.remaining() < vLen) throw new java.nio.BufferUnderflowException();
            byte[] value = new byte[vLen];
            buf.get(value);
            puts.put(new String(kRaw, java.nio.charset.StandardCharsets.UTF_8), value);
        }
        return new CommittedBatch(tx, puts, deletes);
    }
    @Override public void close() throws IOException { if (channel != null) channel.close(); }
    public record Entry(long index, long term, CommittedBatch batch) { }
}
