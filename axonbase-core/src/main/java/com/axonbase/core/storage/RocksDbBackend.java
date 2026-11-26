package com.axonbase.core.storage;

import com.axonbase.common.Messages;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Backend persistente baseado em RocksDB (LSM-tree) que implementa {@link VersionedKvBackend} com column
 * families separadas para dados e versões.
 *
 * <p>Concorrência: não há monitor global. Leituras e varreduras não travam (o iterador do RocksDB enxerga uma
 * visão estável desde que é criado). Cada escrita grava valor e versão num único {@link WriteBatch} atômico.
 * A verificação de versão do commit otimista e a atribuição de versão por chave usam travas por faixa de chave
 * (striped locks), sempre adquiridas em ordem crescente para evitar deadlock. Um commit que varreu prefixos
 * (detecção de leitura fantasma) pega a trava exclusiva do conjunto; os demais entram em modo compartilhado.</p>
 *
 * <p>Carimbo: todo lote recebe um carimbo do relógio híbrido (HLC) e um índice sem buracos depois de pegar as
 * travas e antes de gravar. Os dois vão, no mesmo lote atômico, para a column family padrão, numa chave
 * {@code !hlc/<faixa>} (uma por faixa de trava, para que escritas concorrentes nunca regravem um valor maior com um
 * menor). No reinício valem o maior carimbo e o maior índice entre elas. Veja {@link BatchSink}.</p>
 *
 * <p>Linha do tempo: continua a mesma só quando a abertura prova que nada foi gravado depois de um fechamento limpo
 * ({@link #markClean}). Numa queda, numa troca de base ou depois de escritas de uma versão sem carimbo, nasce outra e
 * o índice pula um número, para o journal de uma história nunca ser aplicado sobre outra.</p>
 *
 * <p>Durabilidade: o WAL (write-ahead log) fica sempre ligado. {@link SyncMode} escolhe quando ele é
 * sincronizado em disco; veja a variável opcional {@code AXON_STORAGE_SYNC}.</p>
 */
public final class RocksDbBackend implements VersionedKvBackend, AutoCloseable {

    /**
     * Quando o WAL é sincronizado (fsync). O WAL é gravado em todos os modos, então um processo que cai não
     * perde escritas já confirmadas; o modo só decide a janela de perda numa queda do sistema operacional ou
     * da energia.
     */
    public enum SyncMode {
        /** fsync em cada escrita (agrupado entre escritores concorrentes pelo RocksDB). Janela de perda zero. */
        ALWAYS,
        /** fsync em segundo plano a cada 1 s. Padrão: numa queda do sistema operacional perde, no máximo, esse intervalo. */
        BATCH,
        /** Sem fsync periódico: o sistema operacional decide quando descarrega. */
        OFF;

        /** Lê {@code AXON_STORAGE_SYNC} (ou a propriedade {@code axon.storage.sync}); padrão {@link #BATCH}. */
        public static SyncMode fromEnvironment() {
            String raw = System.getenv("AXON_STORAGE_SYNC");
            if (raw == null || raw.isBlank()) {
                raw = System.getProperty("axon.storage.sync");
            }
            if (raw == null || raw.isBlank()) {
                return BATCH;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return BATCH;
            }
        }
    }

    private static final String DATA_CF = "data";
    private static final String VERSIONS_CF = "versions";
    private static final int STRIPES = 1024;
    private static final long BATCH_SYNC_INTERVAL_MS = 1000L;
    private static final String HLC_PREFIX = "!hlc/";
    /** Sequência do RocksDB no fechamento limpo: ausente ou diferente na abertura, a história não é contínua. */
    private static final byte[] CLEAN_KEY = "!hlc-clean".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TIMELINE_KEY = "!hlc-timeline".getBytes(StandardCharsets.UTF_8);
    private static final long MARK_CLEAN_TIMEOUT_MS = 5_000L;

    private volatile RocksDB db;
    private volatile ColumnFamilyHandle dataHandle;
    private volatile ColumnFamilyHandle versionsHandle;
    /** Diretório da base ativa; a troca por restore físico renomeia este diretório. */
    private final Path directory;
    /** Segura as operações novas e espera as em curso durante {@link #replaceWith}. */
    private final QuiesceGate gate = new QuiesceGate();
    private final SyncMode syncMode;
    private final WriteOptions writeOptions;
    private final DBOptions dbOptions;
    private final ColumnFamilyOptions cfOptions;
    private final LRUCache blockCache;
    private final BloomFilter bloom;

    /** Último número de versão atribuído. Só muda dentro de {@link #versionGate}. */
    private final AtomicLong versionClock = new AtomicLong();
    private final Object versionGate = new Object();
    /** Primeira versão de cada lote ainda não gravado: a marca d'água de backup não passa dela. */
    private final TreeSet<Long> inFlight = new TreeSet<>();

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final ReentrantReadWriteLock rangeLock = new ReentrantReadWriteLock();

    private final HybridClock hlc;
    private final CommitSequencer sequencer;
    private final byte[][] hlcSlots = new byte[STRIPES][];
    private volatile BatchSink sink;
    /** Maior posição reaplicada do journal; só usada numa base preparada, sob a trava exclusiva. */
    private CommitWatermark replayed;

    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile boolean closed;
    private Thread syncThread;

    public RocksDbBackend(String directory) {
        this(directory, SyncMode.fromEnvironment());
    }

    public RocksDbBackend(String directory, SyncMode syncMode) {
        this(directory, null, syncMode, HybridClock.system());
    }

    /** Com um relógio próprio (testes com relógio falso e nós que compartilham o relógio com o cluster). */
    public RocksDbBackend(String directory, SyncMode syncMode, HybridClock hlc) {
        this(directory, null, syncMode, hlc);
    }

    RocksDbBackend(String directory, DBOptions dbOpts) {
        this(directory, dbOpts, SyncMode.fromEnvironment(), HybridClock.system());
    }

    private RocksDbBackend(String directory, DBOptions dbOpts, SyncMode syncMode, HybridClock hlc) {
        this.directory = Path.of(directory).toAbsolutePath();
        this.hlc = hlc;
        this.sequencer = new CommitSequencer(hlc);
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
            hlcSlots[i] = utf8(String.format("%s%04d", HLC_PREFIX, i));
        }
        this.syncMode = syncMode;
        this.dbOptions = dbOpts != null ? dbOpts : defaultDbOptions();
        long budget = memoryBudget();
        this.blockCache = new LRUCache(budget / 2);
        this.bloom = new BloomFilter(10, false);
        this.cfOptions = defaultCfOptions(budget, blockCache, bloom);
        this.writeOptions = new WriteOptions().setSync(syncMode == SyncMode.ALWAYS);
        try {
            Path dir = Path.of(directory);
            // IDENTITY nem sempre existe (um diretório restaurado pelo BackupEngine só traz CURRENT, MANIFEST e OPTIONS)
            if (Files.isDirectory(dir)) {
                DirectorySwap.recover(dir); // uma troca de base interrompida volta para a base anterior
            }
            boolean exists = Files.exists(dir.resolve("IDENTITY")) || Files.exists(dir.resolve("CURRENT"));
            if (!exists) {
                Files.createDirectories(dir);
            }

            openDatabase(dir.toAbsolutePath().toString(), exists);
            // O contador de versão não é gravado. O número de sequência do RocksDB é monotônico entre
            // restarts e nunca menor que uma versão já atribuída (cada versão é uma escrita), então
            // recomeçar dele impede que chaves novas ganhem versão menor que as já copiadas no backup.
            this.versionClock.set(db.getLatestSequenceNumber());
            resumeStamps();
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("rocksdb_open_failed", directory), e);
        }
        if (syncMode == SyncMode.BATCH) {
            startSyncThread();
        }
    }

    // ---- options factory (auto-ajuste pelo hardware/container) ----

    private static DBOptions defaultDbOptions() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new DBOptions()
            .setCreateIfMissing(true)
            .setIncreaseParallelism(cores)
            .setMaxBackgroundJobs(cores)
            .setEnablePipelinedWrite(true)
            .setBytesPerSync(1L << 20)
            .setWalBytesPerSync(1L << 20);
    }

    /** Memória que o RocksDB pode usar (cache de blocos e write buffers): 10% da memória visível, entre 128 MB e 2 GB. */
    static long memoryBudget() {
        long total = 4L << 30;
        try {
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
                total = os.getTotalMemorySize();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // sem a extensão do JDK, fica o padrão
        }
        return Math.max(128L << 20, Math.min(2L << 30, total / 10));
    }

    private static ColumnFamilyOptions defaultCfOptions(long budget, LRUCache cache, BloomFilter bloom) {
        long writeBuffer = Math.max(16L << 20, Math.min(128L << 20, budget / 12));
        BlockBasedTableConfig table = new BlockBasedTableConfig()
            .setBlockCache(cache)
            .setFilterPolicy(bloom)
            .setWholeKeyFiltering(true)
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true)
            .setBlockSize(16 * 1024);
        return new ColumnFamilyOptions()
            .setLevelCompactionDynamicLevelBytes(true)
            .setCompressionType(CompressionType.LZ4_COMPRESSION)
            .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setWriteBufferSize(writeBuffer)
            .setMaxWriteBufferNumber(3)
            .setTableFormatConfig(table);
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

    /** Abre o RocksDB em {@code pathStr} e preenche os três handles. Usado na abertura e depois da troca. */
    private void openDatabase(String pathStr, boolean exists) throws Exception {
        if (exists) {
            List<byte[]> cfNames;
            try (Options listingOpts = new Options()) {
                cfNames = RocksDB.listColumnFamilies(listingOpts, pathStr);
            }
            List<ColumnFamilyDescriptor> cfDescs = new ArrayList<>(cfNames.size());
            for (byte[] name : cfNames) {
                cfDescs.add(new ColumnFamilyDescriptor(name, cfOptions));
            }
            List<ColumnFamilyHandle> handles = new ArrayList<>(cfNames.size());
            this.db = RocksDB.open(this.dbOptions, pathStr, cfDescs, handles);
            this.dataHandle = findHandle(handles, cfNames, DATA_CF);
            this.versionsHandle = findHandle(handles, cfNames, VERSIONS_CF);
        } else {
            List<ColumnFamilyDescriptor> bootstrap = List.of(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions));
            List<ColumnFamilyHandle> tmp = new ArrayList<>(1);
            this.db = RocksDB.open(this.dbOptions, pathStr, bootstrap, tmp);
            ColumnFamilyHandle def = tmp.get(0);
            this.dataHandle = db.createColumnFamily(
                new ColumnFamilyDescriptor(DATA_CF.getBytes(StandardCharsets.UTF_8), cfOptions));
            this.versionsHandle = db.createColumnFamily(
                new ColumnFamilyDescriptor(VERSIONS_CF.getBytes(StandardCharsets.UTF_8), cfOptions));
            def.close();
        }
    }

    private void startSyncThread() {
        syncThread = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(BATCH_SYNC_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (dirty.compareAndSet(true, false)) {
                    gate.enter();
                    try {
                        db.syncWal();
                    } catch (RocksDBException | RuntimeException e) {
                        dirty.set(true);
                    } finally {
                        gate.exit();
                    }
                }
            }
        }, "axon-wal-sync");
        syncThread.setDaemon(true);
        syncThread.start();
    }

    // ---- leituras (sem trava) ----

    @Override
    public Optional<byte[]> get(String key) {
        gate.enter();
        try {
            return getGated(key);
        } finally {
            gate.exit();
        }
    }

    private Optional<byte[]> getGated(String key) {
        try {
            byte[] v = db.get(dataHandle, utf8(key));
            return v == null ? Optional.empty() : Optional.of(v);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    /** Valor e versão da mesma foto do banco: um commit concorrente não aparece pela metade. */
    @Override
    public Optional<VersionedValue> getVersioned(String key) {
        gate.enter();
        try {
            return getVersionedGated(key);
        } finally {
            gate.exit();
        }
    }

    private Optional<VersionedValue> getVersionedGated(String key) {
        byte[] raw = utf8(key);
        Snapshot snapshot = db.getSnapshot();
        try (ReadOptions ro = new ReadOptions().setSnapshot(snapshot)) {
            byte[] value = db.get(dataHandle, ro, raw);
            if (value == null) {
                return Optional.empty();
            }
            byte[] version = db.get(versionsHandle, ro, raw);
            return Optional.of(new VersionedValue(value, version == null ? 0L : bytesToLong(version)));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            db.releaseSnapshot(snapshot);
            snapshot.close();
        }
    }

    @Override
    public long versionOf(String key) {
        gate.enter();
        try {
            return versionOfGated(key);
        } finally {
            gate.exit();
        }
    }

    private long versionOfGated(String key) {
        try {
            byte[] v = db.get(versionsHandle, utf8(key));
            return v == null ? 0L : bytesToLong(v);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        List<String> keys = new ArrayList<>();
        scanPrefix(prefix, key -> {
            keys.add(key);
            return true;
        });
        return keys;
    }

    @Override
    public void scanPrefix(String prefix, java.util.function.Predicate<String> visitor) {
        gate.enter();
        try {
            scanPrefixGated(prefix, visitor);
        } finally {
            gate.exit();
        }
    }

    private void scanPrefixGated(String prefix, java.util.function.Predicate<String> visitor) {
        try (Scan scan = new Scan(dataHandle, utf8(prefix))) {
            while (scan.it.isValid()) {
                if (!visitor.test(new String(scan.it.key(), StandardCharsets.UTF_8))) {
                    break;
                }
                scan.it.next();
            }
        }
    }

    @Override
    public void scanEntries(String prefix, java.util.function.BiPredicate<String, byte[]> visitor) {
        gate.enter();
        try {
            scanEntriesGated(prefix, visitor);
        } finally {
            gate.exit();
        }
    }

    private void scanEntriesGated(String prefix, java.util.function.BiPredicate<String, byte[]> visitor) {
        try (Scan scan = new Scan(dataHandle, utf8(prefix))) {
            while (scan.it.isValid()) {
                if (!visitor.test(new String(scan.it.key(), StandardCharsets.UTF_8), scan.it.value())) {
                    break;
                }
                scan.it.next();
            }
        }
    }

    /**
     * Iterador limitado ao prefixo (limite superior exclusivo calculado), sem trava e sem poluir o cache de
     * blocos com varreduras longas.
     */
    private final class Scan implements AutoCloseable {
        private final ReadOptions options;
        private final Slice upper;
        final RocksIterator it;

        Scan(ColumnFamilyHandle handle, byte[] prefix) {
            this.options = new ReadOptions().setFillCache(false);
            byte[] bound = upperBound(prefix);
            this.upper = bound == null ? null : new Slice(bound);
            if (upper != null) {
                options.setIterateUpperBound(upper);
            }
            this.it = db.newIterator(handle, options);
            it.seek(prefix);
        }

        @Override
        public void close() {
            it.close();
            options.close();
            if (upper != null) {
                upper.close();
            }
        }
    }

    // ---- escritas ----

    @Override
    public void put(String key, byte[] value) {
        gate.enter();
        try {
            putGated(key, value);
        } finally {
            gate.exit();
        }
    }

    private void putGated(String key, byte[] value) {
        byte[] raw = utf8(key);
        rangeLock.readLock().lock();
        ReentrantLock stripe = stripeOf(raw);
        stripe.lock();
        try {
            writeOne(key, raw, value);
        } finally {
            stripe.unlock();
            rangeLock.readLock().unlock();
        }
    }

    @Override
    public boolean putIfAbsent(String key, byte[] value) {
        gate.enter();
        try {
            return putIfAbsentGated(key, value);
        } finally {
            gate.exit();
        }
    }

    private boolean putIfAbsentGated(String key, byte[] value) {
        byte[] raw = utf8(key);
        rangeLock.readLock().lock();
        ReentrantLock stripe = stripeOf(raw);
        stripe.lock();
        try {
            if (db.get(dataHandle, raw) != null) {
                return false;
            }
            writeOne(key, raw, value);
            return true;
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            stripe.unlock();
            rangeLock.readLock().unlock();
        }
    }

    @Override
    public boolean delete(String key) {
        gate.enter();
        try {
            return deleteGated(key);
        } finally {
            gate.exit();
        }
    }

    private boolean deleteGated(String key) {
        byte[] raw = utf8(key);
        rangeLock.readLock().lock();
        ReentrantLock stripe = stripeOf(raw);
        stripe.lock();
        try {
            if (db.get(dataHandle, raw) == null) {
                return false;
            }
            long first = allocate(1);
            CommitSequencer.Ticket ticket = sequencer.begin();
            try (WriteBatch batch = new WriteBatch()) {
                batch.delete(dataHandle, raw);
                batch.put(versionsHandle, raw, longToBytes(first));
                write(batch, ticket, stripeIndex(raw));
                deliver(ticket, Map.of(), Set.of(key));
            } finally {
                sequencer.end(ticket);
                release(first);
            }
            return true;
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            stripe.unlock();
            rangeLock.readLock().unlock();
        }
    }

    /** Valor e versão no mesmo lote. O chamador já tem a trava da faixa da chave. */
    private void writeOne(String key, byte[] raw, byte[] value) {
        long first = allocate(1);
        CommitSequencer.Ticket ticket = sequencer.begin();
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(dataHandle, raw, value);
            batch.put(versionsHandle, raw, longToBytes(first));
            write(batch, ticket, stripeIndex(raw));
            deliver(ticket, Map.of(key, value), Set.of());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            sequencer.end(ticket);
            release(first);
        }
    }

    @Override
    public void commit(Map<String, Long> expectedVersions,
                       Map<String, byte[]> puts,
                       Set<String> deletes,
                       Set<String> readPrefixes) {
        gate.enter();
        try {
            commitGated(expectedVersions, puts, deletes, readPrefixes);
        } finally {
            gate.exit();
        }
    }

    private void commitGated(Map<String, Long> expectedVersions,
                             Map<String, byte[]> puts,
                             Set<String> deletes,
                             Set<String> readPrefixes) {
        boolean exclusive = !readPrefixes.isEmpty();
        var lock = exclusive ? rangeLock.writeLock() : rangeLock.readLock();
        lock.lock();
        TreeSet<Integer> touched = exclusive ? new TreeSet<>()
            : stripeIndexes(expectedVersions.keySet(), puts.keySet(), deletes);
        List<ReentrantLock> held = lockStripes(touched);
        try {
            for (var expected : expectedVersions.entrySet()) {
                long current = versionOf(expected.getKey());
                if (current != expected.getValue()) {
                    throw new VersionConflictException(Messages.get("storage_version_conflict", expected.getKey()), expected.getKey());
                }
            }
            for (String prefix : readPrefixes) {
                checkPhantoms(prefix, expectedVersions, puts, deletes);
            }
            int n = puts.size() + deletes.size();
            if (n == 0) {
                return;
            }
            long first = allocate(n);
            // a faixa de menor índice entre as travadas; com a trava exclusiva ninguém mais grava
            int slot = touched.isEmpty() ? 0 : touched.first();
            CommitSequencer.Ticket ticket = sequencer.begin();
            try (WriteBatch batch = new WriteBatch()) {
                long version = first;
                for (String key : deletes) {
                    byte[] raw = utf8(key);
                    batch.delete(dataHandle, raw);
                    batch.put(versionsHandle, raw, longToBytes(version++));
                }
                for (var entry : puts.entrySet()) {
                    byte[] raw = utf8(entry.getKey());
                    batch.put(dataHandle, raw, entry.getValue());
                    batch.put(versionsHandle, raw, longToBytes(version++));
                }
                write(batch, ticket, slot);
                deliver(ticket, puts, deletes);
            } catch (RocksDBException e) {
                throw new RuntimeException(e);
            } finally {
                sequencer.end(ticket);
                release(first);
            }
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
            lock.unlock();
        }
    }

    private void checkPhantoms(String prefix, Map<String, Long> expectedVersions,
                               Map<String, byte[]> puts, Set<String> deletes) {
        try (Scan scan = new Scan(dataHandle, utf8(prefix))) {
            while (scan.it.isValid()) {
                String key = new String(scan.it.key(), StandardCharsets.UTF_8);
                if (!expectedVersions.containsKey(key) && !puts.containsKey(key) && !deletes.contains(key)) {
                    throw new VersionConflictException(Messages.get("storage_phantom_read", prefix, key), key);
                }
                scan.it.next();
            }
        }
    }

    /** Grava o lote junto com o carimbo e o índice dele. O chamador tem a trava da faixa {@code slot}. */
    private void write(WriteBatch batch, CommitSequencer.Ticket ticket, int slot) throws RocksDBException {
        batch.put(hlcSlots[slot], CommitSequencer.encode(ticket.stamp(), ticket.index()));
        write(batch);
    }

    private void write(WriteBatch batch) throws RocksDBException {
        db.write(writeOptions, batch);
        if (syncMode == SyncMode.BATCH) {
            dirty.set(true);
        }
    }

    /** Entrega o lote já gravado. Uma falha do destino não desfaz o commit: ela deixa um buraco no índice. */
    private void deliver(CommitSequencer.Ticket ticket, Map<String, byte[]> puts, Set<String> deletes) {
        BatchSink target = sink;
        if (target == null) {
            return;
        }
        try {
            target.offer(ticket.index(), new CommittedBatch("", puts, deletes, false, ticket.stamp()));
        } catch (RuntimeException e) {
            System.err.println("{\"event\":\"commit_sink_failed\",\"index\":" + ticket.index() + ",\"error\":\""
                + e.getMessage() + "\"}");
        }
    }

    // ---- carimbo e sequência ----

    /** O relógio híbrido deste armazenamento. */
    public HybridClock clock() {
        return hlc;
    }

    /** Passa a entregar cada lote confirmado a {@code target} ({@code null} desliga). */
    public void commitSink(BatchSink target) {
        this.sink = target;
    }

    /** Até onde os lotes já foram gravados e entregues. Emite um carimbo quando nada está em gravação. */
    public CommitWatermark watermark() {
        return sequencer.watermark();
    }

    /** O maior carimbo e o maior índice gravados na base. */
    public CommitWatermark persistedPosition() {
        gate.enter();
        try {
            return readPosition();
        } finally {
            gate.exit();
        }
    }

    /**
     * O journal já arquivou até {@code archivedIndex}. Se a base está atrás disso (voltou no tempo por um restore ou
     * por perda de escritas numa queda), os índices recomeçam depois do journal, com um buraco no meio.
     */
    public void resumeAfter(long archivedIndex, HlcTimestamp archivedStamp) {
        gate.enter();
        try {
            hlc.observe(archivedStamp);
            if (archivedIndex > sequencer.lastIndex()) {
                startTimeline(archivedIndex, hlc.now(), true);
            }
        } finally {
            gate.exit();
        }
    }

    /**
     * Reaplica um lote do journal com o carimbo e o índice originais, sem entregá-lo de volta. Usado pela
     * restauração a um instante numa base ainda fora de uso.
     */
    public void applyArchived(long index, CommittedBatch batch) {
        gate.enter();
        try {
            rangeLock.writeLock().lock();
            try {
                int n = batch.puts().size() + batch.deletes().size();
                long first = allocate(Math.max(1, n));
                try (WriteBatch writes = new WriteBatch()) {
                    long version = first;
                    for (String key : batch.deletes()) {
                        byte[] raw = utf8(key);
                        writes.delete(dataHandle, raw);
                        writes.put(versionsHandle, raw, longToBytes(version++));
                    }
                    for (var entry : batch.puts().entrySet()) {
                        byte[] raw = utf8(entry.getKey());
                        writes.put(dataHandle, raw, entry.getValue());
                        writes.put(versionsHandle, raw, longToBytes(version++));
                    }
                    // a ordem do journal não é a do índice: grava sempre o maior já visto
                    CommitWatermark before = replayed != null ? replayed : readPosition();
                    replayed = new CommitWatermark(HlcTimestamp.max(before.stamp(), batch.stamp()),
                        Math.max(before.index(), index));
                    writes.put(hlcSlots[0], CommitSequencer.encode(replayed.stamp(), replayed.index()));
                    write(writes);
                    hlc.observe(batch.stamp());
                    sequencer.advanceTo(index);
                } catch (RocksDBException e) {
                    throw new RuntimeException(e);
                } finally {
                    release(first);
                }
            } finally {
                rangeLock.writeLock().unlock();
            }
        } finally {
            gate.exit();
        }
    }

    /** Na abertura: o relógio e o índice continuam do que está gravado. */
    private void resumeStamps() throws RocksDBException {
        CommitWatermark persisted = readPosition();
        hlc.restore(persisted.stamp());
        sequencer.advanceTo(persisted.index());
        byte[] timeline = db.get(TIMELINE_KEY);
        byte[] clean = db.get(CLEAN_KEY);
        if (timeline != null && clean != null && bytesToLong(clean) == db.getLatestSequenceNumber()) {
            sequencer.timeline(bytesToLong(timeline));
            db.delete(CLEAN_KEY); // daqui em diante, só um novo fechamento limpo prova a continuidade
        } else {
            startTimeline(persisted.index(), persisted.stamp(), persisted.index() > 0);
        }
    }

    /**
     * Começa uma linha do tempo nova e, havendo história anterior, pula um índice. Só chamar sem escritas
     * concorrentes. A memória muda antes do disco: se a gravação falhar, a próxima abertura não prova continuidade.
     */
    private void startTimeline(long atLeast, HlcTimestamp stamp, boolean skip) {
        long index = atLeast;
        if (skip) {
            index = sequencer.skipIndex(atLeast);
        }
        long timeline = 0;
        while (timeline == 0) {
            timeline = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        }
        sequencer.timeline(timeline);
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(TIMELINE_KEY, longToBytes(timeline));
            batch.put(hlcSlots[0], CommitSequencer.encode(stamp, index));
            batch.delete(CLEAN_KEY);
            write(batch);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registra que, até aqui, tudo foi gravado com carimbo: a próxima abertura continua a mesma linha do tempo se
     * nada mais for escrito. Chamado no desligamento do servidor; {@link #close()} também o faz.
     */
    public void markClean() {
        if (closed || !gate.quiesce(MARK_CLEAN_TIMEOUT_MS)) {
            return; // com operações em curso não dá para provar nada: a próxima abertura abre outra linha do tempo
        }
        try {
            writeCleanMarker();
        } finally {
            gate.resume();
        }
    }

    private void writeCleanMarker() {
        try {
            db.put(writeOptions, CLEAN_KEY, longToBytes(db.getLatestSequenceNumber() + 1));
            db.syncWal();
        } catch (RocksDBException ignored) {
            // sem a marca, a próxima abertura só deixa de continuar a linha do tempo
        }
    }

    private CommitWatermark readPosition() {
        byte[] prefix = utf8(HLC_PREFIX);
        HlcTimestamp stamp = HlcTimestamp.NONE;
        long index = 0L;
        try (RocksIterator it = db.newIterator()) {
            for (it.seek(prefix); it.isValid() && startsWith(it.key(), prefix); it.next()) {
                CommitWatermark slot = CommitSequencer.decode(it.value());
                stamp = HlcTimestamp.max(stamp, slot.stamp());
                index = Math.max(index, slot.index());
            }
        }
        return new CommitWatermark(stamp, index);
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        return key.length >= prefix.length
            && java.util.Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** Reserva {@code n} versões consecutivas e registra o lote como em andamento. Devolve a primeira. */
    private long allocate(int n) {
        synchronized (versionGate) {
            long first = versionClock.get() + 1;
            versionClock.set(first + n - 1);
            inFlight.add(first);
            return first;
        }
    }

    private void release(long first) {
        synchronized (versionGate) {
            inFlight.remove(first);
        }
    }

    // ---- travas por faixa ----

    private ReentrantLock stripeOf(byte[] raw) {
        return stripes[stripeIndex(raw)];
    }

    private static int stripeIndex(byte[] raw) {
        int h = java.util.Arrays.hashCode(raw);
        h ^= (h >>> 16);
        return (h & 0x7fffffff) % STRIPES;
    }

    /** As faixas tocadas pelas chaves, em ordem crescente. */
    @SafeVarargs
    private static TreeSet<Integer> stripeIndexes(Collection<String>... keySets) {
        TreeSet<Integer> indexes = new TreeSet<>();
        for (Collection<String> keys : keySets) {
            for (String key : keys) {
                indexes.add(stripeIndex(utf8(key)));
            }
        }
        return indexes;
    }

    /** Pega, em ordem crescente de índice, a trava de cada faixa. */
    private List<ReentrantLock> lockStripes(TreeSet<Integer> indexes) {
        List<ReentrantLock> held = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            ReentrantLock lock = stripes[index];
            lock.lock();
            held.add(lock);
        }
        return held;
    }

    /** Cria um backup físico deste banco dentro do portão, para uma troca de base não fechar o banco no meio. */
    void createPhysicalBackup(org.rocksdb.BackupEngine engine, String metadata) throws RocksDBException {
        gate.enter();
        try {
            engine.createNewBackupWithMetadata(db, metadata, true);
        } finally {
            gate.exit();
        }
    }

    /** O diretório da base ativa. */
    public Path directory() {
        return directory;
    }

    /** A troca de base desistiu: havia operações que não terminaram a tempo. */
    public static final class SwapTimeoutException extends RuntimeException {
        public SwapTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * Troca a base ativa por {@code prepared}, um diretório RocksDB já restaurado e validado (veja
     * {@link PhysicalBackups#prepareRestore}). Segura as operações novas, espera as em curso (até
     * {@code quiesceTimeoutMs}), fecha o RocksDB, renomeia o diretório ativo para {@code .replaced-<instante>}
     * e o preparado para o lugar dele, e reabre. Devolve o caminho da base anterior. Se desistir por tempo, a
     * base fica como estava ({@link SwapTimeoutException}); se a renomeação falhar, a anterior é reaberta.
     * A marca d'água de versão nunca regride, então um token de versão já entregue a um cliente continua
     * sendo menor que as versões novas.
     */
    public Path replaceWith(Path prepared, long quiesceTimeoutMs) {
        if (!gate.quiesce(quiesceTimeoutMs)) {
            throw new SwapTimeoutException("há operações em curso que não terminaram em " + quiesceTimeoutMs + " ms");
        }
        long clockBefore = versionClock.get();
        long indexBefore = sequencer.lastIndex();
        try {
            BatchSink target = sink;
            if (target != null) {
                target.historyBreaking(); // o que já foi entregue é da linha do tempo que vai acabar
            }
            try {
                db.syncWal();
            } catch (RocksDBException ignored) {
                // o fechamento segue; o WAL já está no sistema operacional
            }
            dataHandle.close();
            versionsHandle.close();
            db.close();
            Path replaced;
            try {
                replaced = DirectorySwap.swap(directory, prepared);
            } catch (java.io.IOException e) {
                try {
                    DirectorySwap.recover(directory);
                } catch (java.io.IOException recoverFailed) {
                    e.addSuppressed(recoverFailed);
                }
                reopen();
                throw new RuntimeException("falha ao trocar a base: " + e.getMessage(), e);
            }
            reopen();
            synchronized (versionGate) {
                versionClock.set(Math.max(clockBefore, versionClock.get()));
            }
            // a história mudou: o relógio segue em frente e o índice ganha um buraco antes do próximo lote
            CommitWatermark restored = readPosition();
            hlc.observe(restored.stamp());
            startTimeline(Math.max(indexBefore, restored.index()), hlc.now(), true);
            return replaced;
        } finally {
            gate.resume();
        }
    }

    private void reopen() {
        try {
            openDatabase(directory.toString(), true);
            synchronized (versionGate) {
                versionClock.set(Math.max(versionClock.get(), db.getLatestSequenceNumber()));
            }
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("rocksdb_open_failed", directory.toString()), e);
        }
    }

    // ---- VersionedKvBackend ----

    /** Marca d'água de backup: não passa de um lote ainda em gravação, para o incremental não perdê-lo. */
    @Override
    public long currentVersion() {
        synchronized (versionGate) {
            return inFlight.isEmpty() ? versionClock.get() : inFlight.first() - 1;
        }
    }

    /**
     * Varredura de ponto no tempo: a marca d'água é lida antes de fixar UM snapshot do RocksDB, que vale para
     * as duas column families. Chave e valor vêm do mesmo iterador e a versão do iterador de versões do mesmo
     * snapshot (sem {@code get} por chave), então um lote atômico (registro e índices) nunca aparece pela
     * metade. As deleções também saem do snapshot. O snapshot é liberado ao fim.
     */
    @Override
    public long snapshotScan(long since, EntryVisitor entries, KeyVisitor deleted) throws java.io.IOException {
        gate.enter();
        try {
            return snapshotScanGated(since, entries, deleted);
        } finally {
            gate.exit();
        }
    }

    private long snapshotScanGated(long since, EntryVisitor entries, KeyVisitor deleted) throws java.io.IOException {
        long mark = currentVersion();
        Snapshot snapshot = db.getSnapshot();
        try (ReadOptions ro = new ReadOptions().setSnapshot(snapshot).setFillCache(false);
             RocksIterator data = db.newIterator(dataHandle, ro);
             RocksIterator vers = db.newIterator(versionsHandle, ro)) {
            data.seekToFirst();
            vers.seekToFirst();
            boolean go = true;
            while (go && data.isValid()) {
                byte[] key = data.key();
                while (vers.isValid() && java.util.Arrays.compareUnsigned(vers.key(), key) < 0) {
                    vers.next();
                }
                long version = vers.isValid() && java.util.Arrays.equals(vers.key(), key)
                    ? bytesToLong(vers.value()) : 0L;
                if (since <= 0 || version > since) {
                    go = entries.visit(new String(key, StandardCharsets.UTF_8), data.value(), version);
                }
                data.next();
            }
            if (go && since > 0 && deleted != null) {
                try (RocksIterator dels = db.newIterator(versionsHandle, ro)) {
                    dels.seekToFirst();
                    while (dels.isValid()) {
                        if (bytesToLong(dels.value()) > since && db.get(dataHandle, ro, dels.key()) == null
                            && !deleted.visit(new String(dels.key(), StandardCharsets.UTF_8))) {
                            break;
                        }
                        dels.next();
                    }
                }
            }
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            db.releaseSnapshot(snapshot);
            snapshot.close();
        }
        return mark;
    }

    /** Varre a column family de versões: chave com versão maior que {@code since} e sem dado é uma deleção. */
    @Override
    public void scanDeletedSince(long since, java.util.function.Predicate<String> visitor) {
        gate.enter();
        try {
            scanDeletedSinceGated(since, visitor);
        } finally {
            gate.exit();
        }
    }

    private void scanDeletedSinceGated(long since, java.util.function.Predicate<String> visitor) {
        try (ReadOptions ro = new ReadOptions().setFillCache(false);
             RocksIterator it = db.newIterator(versionsHandle, ro)) {
            it.seekToFirst();
            while (it.isValid()) {
                if (bytesToLong(it.value()) > since) {
                    byte[] k = it.key();
                    try {
                        if (db.get(dataHandle, k) == null
                            && !visitor.test(new String(k, StandardCharsets.UTF_8))) {
                            return;
                        }
                    } catch (RocksDBException e) {
                        throw new RuntimeException(e);
                    }
                }
                it.next();
            }
        }
    }

    /**
     * Antes forçava a descarga do memtable para um arquivo SST a cada statement, o que custava uma escrita de
     * arquivo por operação. As escritas já estão no WAL (que é reaplicado ao reabrir). Aqui apenas empurra o
     * buffer do WAL para o sistema operacional; o fsync segue {@link SyncMode}.
     */
    @Override
    public void flush() {
        gate.enter();
        try {
            flushGated();
        } finally {
            gate.exit();
        }
    }

    private void flushGated() {
        if (syncMode == SyncMode.ALWAYS) {
            return; // cada escrita já foi sincronizada
        }
        try {
            db.flushWal(false);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    /** Força o fsync do WAL agora, em qualquer modo. */
    public void syncWal() {
        gate.enter();
        try {
            syncWalGated();
        } finally {
            gate.exit();
        }
    }

    private void syncWalGated() {
        try {
            db.syncWal();
            dirty.set(false);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (syncThread != null) {
                syncThread.interrupt();
                syncThread.join(1000);
            }
            writeCleanMarker();
            if (dataHandle != null) dataHandle.close();
            if (versionsHandle != null) versionsHandle.close();
            if (db != null) db.close();
            writeOptions.close();
            cfOptions.close();
            dbOptions.close();
            blockCache.close();
            bloom.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- utilities ----

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Menor chave maior que todas as do prefixo, ou {@code null} se o prefixo é vazio ou só de 0xFF. */
    static byte[] upperBound(byte[] prefix) {
        int end = prefix.length;
        while (end > 0 && prefix[end - 1] == (byte) 0xFF) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        byte[] bound = java.util.Arrays.copyOf(prefix, end);
        bound[end - 1]++;
        return bound;
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
