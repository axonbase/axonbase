package com.axonbase.core.storage;

import com.axonbase.common.Messages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Backend persistente con WAL (Write-Ahead Logging), snapshot e crash recovery.
 * <p>
 * Cada {@code put}/{@code delete} atualiza un mapa en memoria e agrega un
 * rexistro ao log de append (WAL). {@code flush()} aplica fsync ao WAL. Cando
 * o WAL supera un límite, faise unha snapshot compacta e limpiase o log.
 * <p>
 * Ao abrir, leese a última snapshot e replícase o WAL; os rexistros truncados
 * no final descártanse (recovery tras caída).
 */
public final class WalBackend implements VersionedKvBackend {

    private static final byte OP_PUT = 1;
    private static final byte OP_DEL = 2;
    private static final byte OP_BATCH = 3;
    private static final long COMPACT_THRESHOLD = 1_048_576L; // 1 MiB

    private final TreeMap<String, byte[]> data = new TreeMap<>();
    private final TreeMap<String, Long> versions = new TreeMap<>();
    private final HistoryLedger history = new HistoryLedger();
    private long clock;
    private final Path dir;
    private final Path walPath;
    private FileChannel wal;
    private long walBytes = 0;
    private boolean walDirty = false;

    public WalBackend(String directory) {
        this.dir = Path.of(directory);
        try {
            Files.createDirectories(dir);
            this.walPath = dir.resolve("data.wal");
            restore();
            wal = FileChannel.open(walPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Restaura o estado: snapshot + replay do WAL. */
    private void restore() throws IOException {
        Path snap = dir.resolve("snapshot.bin");
        if (Files.exists(snap)) {
            loadSnapshot(snap);
        }
        if (Files.exists(walPath)) {
            replayWal(walPath);
        }
    }

    private void loadSnapshot(Path snap) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(snap));
        while (buf.remaining() >= 2) {
            int klen = buf.getShort() & 0xFFFF;
            if (buf.remaining() < klen + 2) {
                break;
            }
            byte[] k = new byte[klen];
            buf.get(k);
            int vlen = buf.getShort() & 0xFFFF;
            if (buf.remaining() < vlen) {
                break;
            }
            byte[] v = new byte[vlen];
            buf.get(v);
            String key = new String(k, StandardCharsets.UTF_8);
            data.put(key, v);
            versions.put(key, ++clock);
        }
    }

    private void replayWal(Path walFile) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(walFile));
        long validEnd = 0;
        while (buf.remaining() >= 1) {
            int mark = buf.position();
            byte op = buf.get();
            if (op == OP_BATCH) {
                if (buf.remaining() < 4) break;
                int count = buf.getInt();
                TreeMap<String, byte[]> puts = new TreeMap<>();
                java.util.Set<String> deletes = new java.util.HashSet<>();
                boolean complete = true;
                for (int i = 0; i < count; i++) {
                    if (buf.remaining() < 3) { complete = false; break; }
                    byte batchOp = buf.get();
                    int batchKeyLen = buf.getShort() & 0xFFFF;
                    if (buf.remaining() < batchKeyLen) { complete = false; break; }
                    byte[] batchKey = new byte[batchKeyLen]; buf.get(batchKey);
                    String batchName = new String(batchKey, StandardCharsets.UTF_8);
                    if (batchOp == OP_DEL) {
                        deletes.add(batchName);
                    } else if (batchOp == OP_PUT) {
                        if (buf.remaining() < 2) { complete = false; break; }
                        int batchValueLen = buf.getShort() & 0xFFFF;
                        if (buf.remaining() < batchValueLen) { complete = false; break; }
                        byte[] batchValue = new byte[batchValueLen]; buf.get(batchValue);
                        puts.put(batchName, batchValue);
                    } else { complete = false; break; }
                }
                if (!complete) { buf.position(mark); break; }
                for (String deleted : deletes) { data.remove(deleted); versions.put(deleted, ++clock); }
                for (var put : puts.entrySet()) { data.put(put.getKey(), put.getValue()); versions.put(put.getKey(), ++clock); }
                validEnd = buf.position();
                continue;
            }
            if (op != OP_PUT && op != OP_DEL) {
                break;
            }
            if (buf.remaining() < 2) {
                break;
            }
            int klen = buf.getShort() & 0xFFFF;
            if (buf.remaining() < klen) {
                break;
            }
            byte[] k = new byte[klen];
            buf.get(k);
            String key = new String(k, StandardCharsets.UTF_8);
            if (op == OP_DEL) {
                data.remove(key);
                versions.put(key, ++clock);
                validEnd = buf.position();
                continue;
            }
            if (buf.remaining() < 2) {
                break;
            }
            int vlen = buf.getShort() & 0xFFFF;
            if (buf.remaining() < vlen) {
                break;
            }
            byte[] v = new byte[vlen];
            buf.get(v);
            data.put(key, v);
            versions.put(key, ++clock);
            validEnd = buf.position();
        }
        if (validEnd == 0 && buf.position() == 0) {
            return;
        }
        if (validEnd < Files.size(walFile)) {
            try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
                ch.truncate(validEnd);
            }
        }
    }

    private void appendWal(byte op, byte[] key, byte[] value) throws IOException {
        int cap = 1 + 2 + key.length + (value == null ? 0 : 2 + value.length);
        ByteBuffer buf = ByteBuffer.allocate(cap);
        buf.put(op);
        buf.putShort((short) key.length);
        buf.put(key);
        if (value != null) {
            buf.putShort((short) value.length);
            buf.put(value);
        }
        buf.flip();
        wal.write(buf);
        walBytes += buf.limit();
        walDirty = true;
        maybeCompact();
    }

    /** Escreve todas as mudanças da transação em um único frame recuperável. */
    private void appendBatch(java.util.Map<String, byte[]> puts, java.util.Set<String> deletes) throws IOException {
        int count = puts.size() + deletes.size();
        int cap = 1 + 4;
        for (String key : deletes) cap += 1 + 2 + key.getBytes(StandardCharsets.UTF_8).length;
        for (var put : puts.entrySet()) cap += 1 + 2 + put.getKey().getBytes(StandardCharsets.UTF_8).length + 2 + put.getValue().length;
        ByteBuffer buf = ByteBuffer.allocate(cap);
        buf.put(OP_BATCH).putInt(count);
        for (String key : deletes) {
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            buf.put(OP_DEL).putShort((short) raw.length).put(raw);
        }
        for (var put : puts.entrySet()) {
            byte[] key = put.getKey().getBytes(StandardCharsets.UTF_8);
            buf.put(OP_PUT).putShort((short) key.length).put(key).putShort((short) put.getValue().length).put(put.getValue());
        }
        buf.flip(); wal.write(buf); walBytes += buf.limit(); walDirty = true; maybeCompact();
    }

    private void maybeCompact() throws IOException {
        if (walBytes >= COMPACT_THRESHOLD) {
            compact();
        }
    }

    private synchronized void compact() throws IOException {
        ByteBuffer snap = compactSnapshot();
        Path snapTmp = dir.resolve("snapshot.tmp");
        try (FileChannel sc = FileChannel.open(snapTmp,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            sc.write(snap);
            sc.force(true);
        }
        wal.close();
        Path walNew = dir.resolve("data.wal.new");
        Files.deleteIfExists(walNew);
        Files.createFile(walNew);
        Files.move(walNew, walPath, StandardCopyOption.REPLACE_EXISTING);
        Files.move(snapTmp, dir.resolve("snapshot.bin"), StandardCopyOption.REPLACE_EXISTING);
        wal = FileChannel.open(walPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        walBytes = 0;
        walDirty = false;
    }

    private ByteBuffer compactSnapshot() {
        int total = 0;
        for (var e : data.entrySet()) {
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            total += 2 + k.length + 2 + e.getValue().length;
        }
        ByteBuffer out = ByteBuffer.allocate(total);
        for (var e : data.entrySet()) {
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] v = e.getValue();
            out.putShort((short) k.length);
            out.put(k);
            out.putShort((short) v.length);
            out.put(v);
        }
        out.flip();
        return out;
    }

    @Override
    public synchronized Optional<byte[]> get(String key) {
        byte[] v = data.get(key);
        return v == null ? Optional.empty() : Optional.of(v.clone());
    }

    @Override
    public synchronized void put(String key, byte[] value) {
        data.put(key, value.clone());
        long ver = ++clock;
        history.record(key, value, ver, System.currentTimeMillis());
        versions.put(key, ver);
        try {
            appendWal(OP_PUT, key.getBytes(StandardCharsets.UTF_8), value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized boolean putIfAbsent(String key, byte[] value) {
        if (data.containsKey(key)) {
            return false;
        }
        put(key, value);
        return true;
    }

    @Override
    public synchronized boolean delete(String key) {
        if (data.remove(key) == null) {
            return false;
        }
        versions.put(key, ++clock);
        try {
            appendWal(OP_DEL, key.getBytes(StandardCharsets.UTF_8), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    @Override
    public synchronized List<String> keysWithPrefix(String prefix) {
        List<String> out = new ArrayList<>();
        for (String k : data.tailMap(prefix).keySet()) {
            if (k.startsWith(prefix)) {
                out.add(k);
            } else {
                break;
            }
        }
        return out;
    }

    @Override public synchronized long versionOf(String key) { return versions.getOrDefault(key, 0L); }

    public HistoryLedger history() { return history; }

    @Override
    public Optional<byte[]> snapshotAt(String key, long timestampEpochMillis) {
        Optional<byte[]> historical = history.snapshotAt(key, timestampEpochMillis);
        if (historical.isPresent()) return historical;
        return Optional.empty();
    }

@Override
    public synchronized void commit(java.util.Map<String, Long> expectedVersions,
                                    java.util.Map<String, byte[]> puts, java.util.Set<String> deletes) {
        commit(expectedVersions, puts, deletes, java.util.Set.of());
    }

    @Override
    public synchronized void commit(java.util.Map<String, Long> expectedVersions,
                                    java.util.Map<String, byte[]> puts, java.util.Set<String> deletes,
                                    java.util.Set<String> readPrefixes) {
        for (var expected : expectedVersions.entrySet()) {
            if (versionOf(expected.getKey()) != expected.getValue())
                throw new VersionConflictException(Messages.get("storage_version_conflict", expected.getKey()));
        }
        for (String prefix : readPrefixes) {
            for (String key : keysWithPrefix(prefix)) {
                if (expectedVersions.containsKey(key) || puts.containsKey(key) || deletes.contains(key)) {
                    continue;
                }
                throw new VersionConflictException(Messages.get("storage_phantom_read", prefix, key));
            }
        }
        try { appendBatch(puts, deletes); } catch (IOException e) { throw new UncheckedIOException(e); }
        long now = System.currentTimeMillis();
        for (String key : deletes) {
            data.remove(key);
            versions.put(key, ++clock);
        }
        for (var put : puts.entrySet()) {
            data.put(put.getKey(), put.getValue().clone());
            long ver = ++clock;
            versions.put(put.getKey(), ver);
            history.record(put.getKey(), put.getValue(), ver, now);
        }
    }

    @Override
    public void flush() {
        if (wal == null || !walDirty) {
            return;
        }
        try {
            wal.force(true);
            walDirty = false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (wal != null) {
                if (walDirty) {
                    wal.force(true);
                }
                wal.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
