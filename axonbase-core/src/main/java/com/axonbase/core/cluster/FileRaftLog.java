package com.axonbase.core.cluster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Log Raft append-only por nó. Entradas truncadas no final são descartadas no recovery. */
public final class FileRaftLog implements AutoCloseable {
    private final Path path;
    private final List<Entry> entries = new ArrayList<>();

    public FileRaftLog(Path directory) {
        try {
            Files.createDirectories(directory);
            path = directory.resolve("raft.log");
            recover();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized void append(Entry entry) {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND))) {
            out.writeLong(entry.index()); out.writeLong(entry.term()); out.writeUTF(entry.batch().transactionId());
            out.writeInt(entry.batch().deletes().size()); for (String key : entry.batch().deletes()) out.writeUTF(key);
            out.writeInt(entry.batch().puts().size());
            for (var put : entry.batch().puts().entrySet()) { out.writeUTF(put.getKey()); out.writeInt(put.getValue().length); out.write(put.getValue()); }
            out.flush();
            entries.add(entry);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }
    public synchronized long lastIndex() { return entries.isEmpty() ? 0 : entries.getLast().index(); }
    public synchronized Entry entry(long index) {
        return entries.stream().filter(entry -> entry.index() == index).findFirst().orElse(null);
    }

    private void recover() throws IOException {
        if (!Files.exists(path)) return;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            while (true) {
                try {
                    long index = in.readLong(), term = in.readLong(); String tx = in.readUTF();
                    int dels = in.readInt(); java.util.Set<String> deletes = new java.util.LinkedHashSet<>();
                    for (int i=0;i<dels;i++) deletes.add(in.readUTF());
                    int count = in.readInt(); Map<String, byte[]> puts = new LinkedHashMap<>();
                    for (int i=0;i<count;i++) { String key=in.readUTF(); int len=in.readInt(); byte[] value=in.readNBytes(len); if(value.length!=len) throw new java.io.EOFException(); puts.put(key,value); }
                    entries.add(new Entry(index, term, new CommittedBatch(tx, puts, deletes)));
                } catch (java.io.EOFException end) { return; }
            }
        }
    }
    @Override public void close() { }
    public record Entry(long index, long term, CommittedBatch batch) { }
}
