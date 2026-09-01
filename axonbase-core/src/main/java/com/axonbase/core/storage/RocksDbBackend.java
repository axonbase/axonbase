package com.axonbase.core.storage;

import com.axonbase.common.Messages;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backend persistente baseado em RocksDB (LSM-tree) que implementa
 * {@link VersionedKvBackend} con column families separadas para datos
 * e versións, e commits OCC (Optimistic Concurrency Control) cun
 * {@link WriteBatch} atómico.
 *
 * <p>Deseñado para soportar centos de millóns de rexistros grazas ó
 * LSM-tree maduro de RocksDB, compactación leveled, bloom filters e
 * compresión ZSTD. A interface {@code prefix_extractor} pode activarse
 * nunha futura optimización para acelerar {@link #keysWithPrefix(String)}.</p>
 */
public final class RocksDbBackend implements VersionedKvBackend, AutoCloseable {

    private static final String DATA_CF = "data";
    private static final String VERSIONS_CF = "versions";

    private final RocksDB db;
    private final ColumnFamilyHandle dataHandle;
    private final ColumnFamilyHandle versionsHandle;
    private final AtomicLong clock = new AtomicLong();

    public RocksDbBackend(String directory) {
        this(directory, defaultDbOptions());
    }

    RocksDbBackend(String directory, DBOptions dbOpts) {
        try {
            Path dir = Path.of(directory);
            boolean exists = Files.exists(dir.resolve("IDENTITY"));
            if (!exists) {
                Files.createDirectories(dir);
            }

            String pathStr = dir.toAbsolutePath().toString();
            ColumnFamilyOptions cfOpts = defaultCfOptions();

            if (exists) {
                List<byte[]> cfNames;
                try (Options listingOpts = new Options()) {
                    cfNames = RocksDB.listColumnFamilies(listingOpts, pathStr);
                }
                List<ColumnFamilyDescriptor> cfDescs = new ArrayList<>(cfNames.size());
                for (byte[] name : cfNames) {
                    cfDescs.add(new ColumnFamilyDescriptor(name, cfOpts));
                }
                List<ColumnFamilyHandle> handles = new ArrayList<>(cfNames.size());
                this.db = RocksDB.open(dbOpts, pathStr, cfDescs, handles);
                this.dataHandle = findHandle(handles, cfNames, DATA_CF);
                this.versionsHandle = findHandle(handles, cfNames, VERSIONS_CF);
            } else {
                List<ColumnFamilyDescriptor> bootstrap = List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));
                List<ColumnFamilyHandle> tmp = new ArrayList<>(1);
                this.db = RocksDB.open(dbOpts, pathStr, bootstrap, tmp);
                ColumnFamilyHandle def = tmp.get(0);
                this.dataHandle = db.createColumnFamily(
                    new ColumnFamilyDescriptor(DATA_CF.getBytes(StandardCharsets.UTF_8), cfOpts));
                this.versionsHandle = db.createColumnFamily(
                    new ColumnFamilyDescriptor(VERSIONS_CF.getBytes(StandardCharsets.UTF_8), cfOpts));
                def.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("rocksdb_open_failed", directory), e);
        }
    }

    // ---- options factory ----

    private static DBOptions defaultDbOptions() {
        return new DBOptions()
            .setCreateIfMissing(true)
            .setIncreaseParallelism(Runtime.getRuntime().availableProcessors())
            .setMaxBackgroundJobs(Runtime.getRuntime().availableProcessors());
    }

    private static ColumnFamilyOptions defaultCfOptions() {
        return new ColumnFamilyOptions()
            .setLevelCompactionDynamicLevelBytes(true)
            .setCompressionType(CompressionType.ZSTD_COMPRESSION);
    }

    private static ColumnFamilyHandle findHandle(List<ColumnFamilyHandle> handles,
                                                  List<byte[]> names,
                                                  String target) {
        for (int i = 0; i < names.size(); i++) {
            if (target.equals(new String(names.get(i), StandardCharsets.UTF_8))) {
                return handles.get(i);
            }
        }
        throw new IllegalStateException(Messages.get("rocksdb_column_family_missing", target));
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            byte[] v = db.get(dataHandle, key.getBytes(StandardCharsets.UTF_8));
            return v == null ? Optional.empty() : Optional.of(v);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void put(String key, byte[] value) {
        try {
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            db.put(dataHandle, raw, value);
            db.put(versionsHandle, raw, longToBytes(clock.incrementAndGet()));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized boolean putIfAbsent(String key, byte[] value) {
        try {
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            if (db.get(dataHandle, raw) != null) {
                return false;
            }
            db.put(dataHandle, raw, value);
            db.put(versionsHandle, raw, longToBytes(clock.incrementAndGet()));
            return true;
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized boolean delete(String key) {
        try {
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            if (db.get(dataHandle, raw) == null) {
                return false;
            }
            db.delete(dataHandle, raw);
            db.put(versionsHandle, raw, longToBytes(clock.incrementAndGet()));
            return true;
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized List<String> keysWithPrefix(String prefix) {
        List<String> keys = new ArrayList<>();
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        try (RocksIterator it = db.newIterator(dataHandle)) {
            it.seek(prefixBytes);
            while (it.isValid()) {
                byte[] keyBytes = it.key();
                if (!startsWith(keyBytes, prefixBytes)) {
                    break;
                }
                keys.add(new String(keyBytes, StandardCharsets.UTF_8));
                it.next();
            }
        }
        return keys;
    }

    // ---- VersionedKvBackend ----

    @Override
    public long versionOf(String key) {
        try {
            byte[] v = db.get(versionsHandle, key.getBytes(StandardCharsets.UTF_8));
            return v == null ? 0L : bytesToLong(v);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void commit(Map<String, Long> expectedVersions,
                                     Map<String, byte[]> puts,
                                     Set<String> deletes,
                                     Set<String> readPrefixes) {
        for (var expected : expectedVersions.entrySet()) {
            long current = versionOf(expected.getKey());
            if (current != expected.getValue()) {
                throw new VersionConflictException(Messages.get("storage_version_conflict", expected.getKey()));
            }
        }

        for (String prefix : readPrefixes) {
            byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
            try (RocksIterator it = db.newIterator(dataHandle)) {
                it.seek(prefixBytes);
                while (it.isValid()) {
                    byte[] keyBytes = it.key();
                    if (!startsWith(keyBytes, prefixBytes)) break;
                    String key = new String(keyBytes, StandardCharsets.UTF_8);
                    if (!expectedVersions.containsKey(key)
                        && !puts.containsKey(key)
                        && !deletes.contains(key)) {
                        throw new VersionConflictException(Messages.get("storage_phantom_read", prefix, key));
                    }
                    it.next();
                }
            }
        }

        try (WriteBatch batch = new WriteBatch()) {
            for (String key : deletes) {
                batch.delete(dataHandle, key.getBytes(StandardCharsets.UTF_8));
                batch.put(versionsHandle, key.getBytes(StandardCharsets.UTF_8),
                    longToBytes(clock.incrementAndGet()));
            }
            for (var entry : puts.entrySet()) {
                byte[] raw = entry.getKey().getBytes(StandardCharsets.UTF_8);
                batch.put(dataHandle, raw, entry.getValue());
                batch.put(versionsHandle, raw, longToBytes(clock.incrementAndGet()));
            }
            db.write(new WriteOptions(), batch);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void flush() {
        try {
            db.flush(new FlushOptions().setWaitForFlush(true));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (dataHandle != null) dataHandle.close();
            if (versionsHandle != null) versionsHandle.close();
            if (db != null) db.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- utilities ----

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        b[0] = (byte) (v >>> 56);
        b[1] = (byte) (v >>> 48);
        b[2] = (byte) (v >>> 40);
        b[3] = (byte) (v >>> 32);
        b[4] = (byte) (v >>> 24);
        b[5] = (byte) (v >>> 16);
        b[6] = (byte) (v >>> 8);
        b[7] = (byte) v;
        return b;
    }

    private static long bytesToLong(byte[] b) {
        return ((long) b[0] << 56)
            | ((long) b[1] & 0xFF) << 48
            | ((long) b[2] & 0xFF) << 40
            | ((long) b[3] & 0xFF) << 32
            | ((long) b[4] & 0xFF) << 24
            | ((long) b[5] & 0xFF) << 16
            | ((long) b[6] & 0xFF) << 8
            | ((long) b[7] & 0xFF);
    }
}
