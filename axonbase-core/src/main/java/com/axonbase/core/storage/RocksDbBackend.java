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
 * travas e antes de gravar. O que vai para o disco não é cada carimbo, e sim um teto na chave {@code !hlc} da column
 * family padrão, gravado e sincronizado antes que algum carimbo ou índice acima dele seja usado (veja
 * {@link CeilingRecord}). No reinício, relógio e índice recomeçam acima do teto; um fechamento limpo o aperta ao
 * último valor exato. As chaves {@code !hlc/...} guardam o que uma base copiada ou reaplicada contém de verdade
 * (veja {@link #recordedPosition}). Veja também {@link BatchSink}.</p>
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
    private static final byte[] CEILING_KEY = "!hlc".getBytes(StandardCharsets.UTF_8);
    /** Maior posição registrada fora do caminho de commit: início de cópia, replay, desligamento. */
    private static final byte[] BASE_KEY = "!hlc/base".getBytes(StandardCharsets.UTF_8);
    /** Presente só numa cópia física (ou numa queda durante ela): as posições registradas são exatas. */
    private static final byte[] COPY_KEY = "!hlc-copy".getBytes(StandardCharsets.UTF_8);
    /** O teto vai no máximo isto à frente do carimbo: um reinício adianta o relógio em no máximo isso. */
    private static final long MAX_CEILING_LEAD_MS = 250L;
    private static final long MIN_CEILING_LEAD_MS = 50L;
    private static final long INDEX_BLOCK = 1_000_000L;
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
    /** Para lotes que o log replicado já torna duráveis. */
    private final WriteOptions unsyncedOptions;
    private final DBOptions dbOptions;
    private final ColumnFamilyOptions cfOptions;
    private final LRUCache blockCache;
    private final BloomFilter bloom;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final ReentrantReadWriteLock rangeLock = new ReentrantReadWriteLock();

    private final HybridClock hlc;
    private final CeilingRecord ceiling;
    /** Versões, carimbos e índices dos lotes. */
    private final CommitSequencer sequencer;
    private final byte[][] hlcSlots = new byte[STRIPES][];
    /** Criado no construtor: um {@code new} no campo rodaria antes de a biblioteca nativa ser carregada. */
    private final WriteOptions ceilingOptions;
    private final Object copyMonitor = new Object();
    private volatile BatchSink sink;

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
        long lead = Math.max(MIN_CEILING_LEAD_MS, Math.min(MAX_CEILING_LEAD_MS, hlc.maxDriftMs() / 2));
        this.ceiling = new CeilingRecord(this::storeCeiling, lead, INDEX_BLOCK);
        this.sequencer = new CommitSequencer(hlc, ceiling);
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
        this.ceilingOptions = new WriteOptions();
        this.unsyncedOptions = new WriteOptions().setSync(false);
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
            sequencer.versionsAtLeast(db.getLatestSequenceNumber());
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

    @Override
    public void scanEntriesFrom(String prefix, String from, java.util.function.BiPredicate<String, byte[]> visitor) {
        gate.enter();
        try (Scan scan = new Scan(dataHandle, utf8(prefix))) {
            if (from.compareTo(prefix) > 0) {
                scan.it.seek(utf8(from));
            }
            while (scan.it.isValid()) {
                if (!visitor.test(new String(scan.it.key(), StandardCharsets.UTF_8), scan.it.value())) {
                    break;
                }
                scan.it.next();
            }
        } finally {
            gate.exit();
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
        if (RangeDelete.isMarker(key)) {
            commit(Map.of(), Map.of(key, value), Set.of(), Set.of()); // a remoção por faixa pede a trava exclusiva
            return;
        }
        gate.enter();
        try {
            putGated(key, value);
        } finally {
            gate.exit();
        }
    }

    private void putGated(String key, byte[] value) {
        byte[] raw = utf8(key);
        CommitSequencer.Ticket ticket;
        rangeLock.readLock().lock();
        ReentrantLock stripe = stripeOf(raw);
        stripe.lock();
        try {
            ticket = writeOne(key, raw, value);
        } finally {
            stripe.unlock();
            rangeLock.readLock().unlock();
        }
        renewCeiling(ticket);
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
        CommitSequencer.Ticket ticket;
        rangeLock.readLock().lock();
        ReentrantLock stripe = stripeOf(raw);
        stripe.lock();
        try {
            if (db.get(dataHandle, raw) != null) {
                return false;
            }
            ticket = writeOne(key, raw, value);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            stripe.unlock();
            rangeLock.readLock().unlock();
        }
        renewCeiling(ticket);
        return true;
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
        CommitSequencer.Ticket ticket;
        rangeLock.readLock().lock();
        ReentrantLock stripe = stripeOf(raw);
        stripe.lock();
        try {
            if (db.get(dataHandle, raw) == null) {
                return false;
            }
            ticket = sequencer.begin(1);
            try (WriteBatch batch = new WriteBatch()) {
                batch.delete(dataHandle, raw);
                batch.put(versionsHandle, raw, longToBytes(ticket.firstVersion));
                write(batch, ticket, stripeIndex(raw), writeOptions);
                deliver(ticket, Map.of(), Set.of(key));
            } finally {
                sequencer.end(ticket);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            stripe.unlock();
            rangeLock.readLock().unlock();
        }
        renewCeiling(ticket);
        return true;
    }

    /** Valor e versão no mesmo lote. O chamador já tem a trava da faixa da chave. */
    private CommitSequencer.Ticket writeOne(String key, byte[] raw, byte[] value) {
        CommitSequencer.Ticket ticket = sequencer.begin(1);
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(dataHandle, raw, value);
            batch.put(versionsHandle, raw, longToBytes(ticket.firstVersion));
            write(batch, ticket, stripeIndex(raw), writeOptions);
            deliver(ticket, Map.of(key, value), Set.of());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            sequencer.end(ticket);
        }
        return ticket;
    }

    @Override
    public void commit(Map<String, Long> expectedVersions,
                       Map<String, byte[]> puts,
                       Set<String> deletes,
                       Set<String> readPrefixes) {
        gate.enter();
        try {
            commitGated(expectedVersions, puts, deletes, readPrefixes, writeOptions);
        } finally {
            gate.exit();
        }
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void applyReplicated(Map<String, byte[]> puts, Set<String> deletes) {
        gate.enter();
        try {
            commitGated(Map.of(), puts, deletes, Set.of(), unsyncedOptions);
        } finally {
            gate.exit();
        }
    }

    @Override
    public void syncDurable() {
        syncWal();
    }

    private void commitGated(Map<String, Long> expectedVersions,
                             Map<String, byte[]> puts,
                             Set<String> deletes,
                             Set<String> readPrefixes,
                             WriteOptions options) {
        List<RangeDelete.Range> ranges = RangeDelete.in(puts);
        // uma remoção por faixa não comuta com escritas dentro dela: roda sozinha, e o carimbo ordena de verdade
        boolean exclusive = !readPrefixes.isEmpty() || !ranges.isEmpty();
        var lock = exclusive ? rangeLock.writeLock() : rangeLock.readLock();
        lock.lock();
        TreeSet<Integer> touched = exclusive ? new TreeSet<>()
            : stripeIndexes(expectedVersions.keySet(), puts.keySet(), deletes);
        List<ReentrantLock> held = lockStripes(touched);
        CommitSequencer.Ticket ticket;
        try {
            for (var expected : expectedVersions.entrySet()) {
                long current = versionOf(expected.getKey());
                if (current != expected.getValue()) {
                    throw new VersionConflictException(Messages.get("storage_version_conflict", expected.getKey()), expected.getKey());
                }
            }
            for (String prefix : readPrefixes) {
                checkPhantoms(prefix, expectedVersions, puts, deletes, ranges);
            }
            int n = puts.size() + deletes.size();
            if (n == 0) {
                return;
            }
            // a faixa de menor índice entre as travadas; com a trava exclusiva ninguém mais grava
            int slot = touched.isEmpty() ? 0 : touched.first();
            ticket = sequencer.begin(n);
            try (WriteBatch batch = new WriteBatch()) {
                long version = ticket.firstVersion;
                deleteRanges(batch, ranges);
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
                write(batch, ticket, slot, options);
                deliver(ticket, puts, deletes);
            } catch (RocksDBException e) {
                throw new RuntimeException(e);
            } finally {
                sequencer.end(ticket);
            }
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
            lock.unlock();
        }
        renewCeiling(ticket);
    }

    /** Apaga as faixas nos dados e nas versões. Entra no lote antes dos {@code put}, que prevalecem. */
    private void deleteRanges(WriteBatch batch, List<RangeDelete.Range> ranges) throws RocksDBException {
        for (RangeDelete.Range range : ranges) {
            byte[] from = utf8(range.from());
            byte[] to = utf8(range.to());
            batch.deleteRange(dataHandle, from, to);
            batch.deleteRange(versionsHandle, from, to);
        }
    }

    private void checkPhantoms(String prefix, Map<String, Long> expectedVersions,
                               Map<String, byte[]> puts, Set<String> deletes, List<RangeDelete.Range> ranges) {
        try (Scan scan = new Scan(dataHandle, utf8(prefix))) {
            while (scan.it.isValid()) {
                String key = new String(scan.it.key(), StandardCharsets.UTF_8);
                // o que o próprio commit apaga por faixa não é leitura fantasma
                if (!expectedVersions.containsKey(key) && !puts.containsKey(key) && !deletes.contains(key)
                    && ranges.stream().noneMatch(r -> r.contains(key))) {
                    throw new VersionConflictException(Messages.get("storage_phantom_read", prefix, key), key);
                }
                scan.it.next();
            }
        }
    }

    /**
     * Grava o lote. Só durante uma cópia física a posição dele vai junto, numa chave por faixa de trava (assim uma
     * escrita concorrente nunca regrava um valor maior com um menor). O chamador tem a trava da faixa {@code slot}.
     */
    private void write(WriteBatch batch, CommitSequencer.Ticket ticket, int slot, WriteOptions options)
            throws RocksDBException {
        if (ticket.recordPosition) {
            batch.put(hlcSlots[slot], CommitSequencer.encode(ticket.stamp, ticket.index));
        }
        write(batch, options);
    }

    private void write(WriteBatch batch) throws RocksDBException {
        write(batch, writeOptions);
    }

    private void write(WriteBatch batch, WriteOptions options) throws RocksDBException {
        db.write(options, batch);
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
            target.committed(ticket.index, ticket.stamp, puts, deletes);
        } catch (RuntimeException e) {
            System.err.println("{\"event\":\"commit_sink_failed\",\"index\":" + ticket.index + ",\"error\":\""
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

    /** O teto gravado: nenhum carimbo nem índice usado por esta base passa dele. */
    public CommitWatermark persistedCeiling() {
        gate.enter();
        try {
            return readKey(CEILING_KEY);
        } finally {
            gate.exit();
        }
    }

    /**
     * O maior carimbo e o maior índice registrados para o conteúdo da base. É exato (no carimbo) numa base vinda de
     * um backup físico, numa base reaplicada e depois de um fechamento limpo; depois de uma queda é o teto, nunca
     * menos do que a base contém. Com a base em uso e sem cópia em curso, não acompanha os commits: para isso há
     * {@link #watermark}.
     */
    public CommitWatermark recordedPosition() {
        gate.enter();
        try {
            return readRecorded(db);
        } finally {
            gate.exit();
        }
    }

    /** Sobe o teto antes que alguém precise esperar por ele. Chamado já sem as travas do commit. */
    private void renewCeiling(CommitSequencer.Ticket ticket) {
        if (!ticket.renewCeiling) {
            return;
        }
        try {
            ceiling.renew(ticket.stamp.physical(), ticket.index);
        } catch (RuntimeException e) {
            // o lote já está gravado; sem teto novo, o próximo carimbo acima dele é que vai falhar
            System.err.println("{\"event\":\"hlc_ceiling_renewal_failed\",\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /** Grava o teto e sincroniza o WAL sem segurar o grupo de escrita dos outros escritores, em qualquer modo. */
    private void storeCeiling(byte[] value) {
        try {
            db.put(ceilingOptions, CEILING_KEY, value);
            db.syncWal();
        } catch (RocksDBException e) {
            throw new RuntimeException("falha ao gravar o teto do relógio: " + e.getMessage(), e);
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
                startTimeline(archivedIndex, null, true);
            }
        } finally {
            gate.exit();
        }
    }

    /**
     * Reaplica um lote do journal com o carimbo e o índice originais, sem entregá-lo de volta. Usado pela
     * restauração a um instante numa base ainda fora de uso, sincronizada ao fim.
     */
    public void applyArchived(long index, CommittedBatch batch) {
        gate.enter();
        try {
            rangeLock.writeLock().lock();
            try {
                int n = batch.puts().size() + batch.deletes().size();
                CommitSequencer.Ticket ticket = sequencer.beginReplay(Math.max(1, n), batch.stamp(), index);
                try (WriteBatch writes = new WriteBatch()) {
                    long version = ticket.firstVersion;
                    deleteRanges(writes, RangeDelete.in(batch.puts()));
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
                    writeReplayed(writes, new CommitWatermark(batch.stamp(), index));
                    hlc.observe(batch.stamp());
                } catch (RocksDBException e) {
                    throw new RuntimeException(e);
                } finally {
                    sequencer.end(ticket);
                }
            } finally {
                rangeLock.writeLock().unlock();
            }
        } finally {
            gate.exit();
        }
    }

    /**
     * Grava o lote reaplicado junto com a posição e o teto que o cobrem. Os valores nunca recuam: a ordem do journal
     * não é a do índice.
     */
    private void writeReplayed(WriteBatch writes, CommitWatermark applied) {
        ceiling.locked(() -> {
            try {
                writes.put(BASE_KEY, encodeMax(readKey(BASE_KEY), applied));
                HlcTimestamp top = new HlcTimestamp(applied.stamp().physical(), Integer.MAX_VALUE);
                writes.put(CEILING_KEY, encodeMax(readKey(CEILING_KEY), new CommitWatermark(top, applied.index())));
                write(writes);
            } catch (RocksDBException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Na abertura: o relógio e o índice recomeçam acima do teto gravado. A linha do tempo só continua quando a marca
     * de fechamento limpo prova que nada foi gravado depois dela.
     */
    private void resumeStamps() throws RocksDBException {
        CommitWatermark stored = readKey(CEILING_KEY);
        CommitWatermark recorded = readRecorded(db);
        byte[] timeline = db.get(TIMELINE_KEY);
        byte[] clean = db.get(CLEAN_KEY);
        boolean continuous = timeline != null && clean != null && bytesToLong(clean) == db.getLatestSequenceNumber();
        boolean exact = continuous || db.get(COPY_KEY) != null;
        long index = Math.max(stored.index(), recorded.index());
        // sem fechamento limpo nem marca de cópia, a base pode conter qualquer carimbo e índice até o teto
        CommitWatermark contains = exact ? recorded
            : new CommitWatermark(HlcTimestamp.max(recorded.stamp(), stored.stamp()), index);
        ceiling.load(stored);
        sequencer.resume(index, contains.stamp());
        hlc.restore(HlcTimestamp.max(stored.stamp(), recorded.stamp()));
        hlc.guard(ceiling);
        db.delete(COPY_KEY);
        if (continuous) {
            sequencer.timeline(bytesToLong(timeline));
            db.delete(CLEAN_KEY); // daqui em diante, só um novo fechamento limpo prova a continuidade
        } else {
            startTimeline(index, contains, index > 0);
        }
    }

    /**
     * Começa uma linha do tempo nova e, havendo história anterior, pula um índice. Só chamar sem escritas
     * concorrentes. A memória muda antes do disco: se a gravação falhar, a próxima abertura não prova continuidade.
     *
     * @param contains o que a base pode conter no começo dela; {@code null} quando é tudo até agora
     */
    private void startTimeline(long atLeast, CommitWatermark contains, boolean skip) {
        long index = breakHistory(atLeast, skip);
        persistTimeline(index, contains != null ? contains : new CommitWatermark(hlc.now(), index));
    }

    /** A parte em memória de uma linha do tempo nova. Devolve o índice de onde ela parte. */
    private long breakHistory(long atLeast, boolean skip) {
        long index = atLeast;
        if (skip) {
            index = sequencer.skipIndex(atLeast);
        }
        long timeline = 0;
        while (timeline == 0) {
            timeline = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        }
        sequencer.timeline(timeline);
        return index;
    }

    private void persistTimeline(long index, CommitWatermark upTo) {
        sequencer.contains(upTo.stamp());
        ceiling.raise(upTo.stamp().physical());
        ceiling.raiseIndex(index);
        recordPosition(upTo);
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(TIMELINE_KEY, longToBytes(sequencer.timeline()));
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
            // o teto desce ao último valor usado: o reinício não adianta o relógio nem pula índice
            CommitWatermark[] last = new CommitWatermark[1];
            sequencer.settle((clockMillis, content) -> {
                ceiling.lower(clockMillis, content.index());
                last[0] = content;
            });
            recordPosition(last[0]);
            db.put(writeOptions, CLEAN_KEY, longToBytes(db.getLatestSequenceNumber() + 1));
            db.syncWal();
        } catch (RocksDBException | RuntimeException e) {
            // sem a marca, a próxima abertura só deixa de continuar a linha do tempo e recomeça acima do teto
            System.err.println("{\"event\":\"clean_marker_failed\",\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Leva a posição registrada até pelo menos {@code upTo}. A trava do teto serializa quem escreve nela; quem a
     * segura não pede o sequenciador.
     */
    private void recordPosition(CommitWatermark upTo) {
        ceiling.locked(() -> {
            try {
                db.put(BASE_KEY, encodeMax(readKey(BASE_KEY), upTo));
            } catch (RocksDBException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static byte[] encodeMax(CommitWatermark a, CommitWatermark b) {
        return CommitSequencer.encode(HlcTimestamp.max(a.stamp(), b.stamp()), Math.max(a.index(), b.index()));
    }

    private CommitWatermark readKey(byte[] key) {
        try {
            return CommitSequencer.decode(db.get(key));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    private static CommitWatermark readRecorded(RocksDB source) {
        byte[] prefix = utf8(HLC_PREFIX);
        HlcTimestamp stamp = HlcTimestamp.NONE;
        long index = 0L;
        try (RocksIterator it = source.newIterator()) {
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

    /**
     * Cria um backup físico deste banco dentro do portão, para uma troca de base não fechar o banco no meio. Enquanto
     * copia, cada commit grava a própria posição, para a cópia saber exatamente até que carimbo ela vai.
     */
    void createPhysicalBackup(org.rocksdb.BackupEngine engine, String metadata) throws RocksDBException {
        gate.enter();
        try {
            beginCopy();
            try {
                engine.createNewBackupWithMetadata(db, metadata, true);
            } finally {
                endCopy();
            }
        } finally {
            gate.exit();
        }
    }

    private void beginCopy() throws RocksDBException {
        synchronized (copyMonitor) {
            CommitWatermark before = sequencer.beginCopy();
            boolean marked = false;
            try {
                recordPosition(before);
                db.put(COPY_KEY, longToBytes(1));
                marked = true;
            } finally {
                if (!marked) {
                    sequencer.endCopy();
                }
            }
        }
    }

    private void endCopy() {
        synchronized (copyMonitor) {
            // a marca sai antes de os commits pararem de se registrar
            if (sequencer.copies() == 1) {
                try {
                    db.delete(COPY_KEY);
                } catch (RocksDBException e) {
                    // com a marca no disco, os commits precisam continuar registrando a posição
                    System.err.println("{\"event\":\"copy_mark_not_removed\",\"error\":\"" + e.getMessage() + "\"}");
                    return;
                }
            }
            sequencer.endCopy();
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
        long versionsBefore = sequencer.lastVersion();
        long indexBefore = sequencer.lastIndex();
        try {
            BatchSink target = sink;
            if (target != null) {
                target.historyBreaking(); // o que já foi entregue é da linha do tempo que vai acabar
            }
            Path[] replaced = new Path[1];
            CommitWatermark[] restored = new CommitWatermark[1];
            // com a trava, o teto não muda entre ser entregue à base nova e a troca; aqui não se pede o sequenciador
            ceiling.locked(() -> {
                restored[0] = handCeilingTo(prepared);
                replaced[0] = swapDirectory(prepared);
            });
            // primeiro a memória, que não falha: a base nova nunca segue na linha do tempo antiga
            sequencer.versionsAtLeast(Math.max(versionsBefore, db.getLatestSequenceNumber()));
            hlc.observe(restored[0].stamp());
            long index = breakHistory(Math.max(indexBefore, restored[0].index()), true);
            try {
                persistTimeline(index, new CommitWatermark(hlc.now(), index));
            } catch (RuntimeException e) {
                // a troca já aconteceu; sem a marca de limpa na base nova, uma reabertura abre outra linha do tempo
                System.err.println("{\"event\":\"timeline_persist_failed\",\"error\":\"" + e.getMessage() + "\"}");
            }
            return replaced[0];
        } finally {
            gate.resume();
        }
    }

    /**
     * Antes da troca, grava na base preparada o teto atual e tira dela a marca de fechamento limpo. Assim uma queda em
     * qualquer ponto depois da troca reabre acima de tudo que já foi usado, em outra linha do tempo. Se falhar, nada
     * foi trocado.
     *
     * @return o maior carimbo que a base preparada contém e o maior índice que ela cita
     */
    private CommitWatermark handCeilingTo(Path prepared) {
        String path = prepared.toAbsolutePath().toString();
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try (Options listing = new Options()) {
            List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
            for (byte[] name : RocksDB.listColumnFamilies(listing, path)) {
                descriptors.add(new ColumnFamilyDescriptor(name, cfOptions));
            }
            try (RocksDB target = RocksDB.open(dbOptions, path, descriptors, handles);
                 WriteBatch batch = new WriteBatch();
                 WriteOptions durable = new WriteOptions().setSync(true)) {
                try {
                    CommitWatermark theirs = CommitSequencer.decode(target.get(CEILING_KEY));
                    CommitWatermark recorded = readRecorded(target);
                    CommitWatermark ours = new CommitWatermark(
                        new HlcTimestamp(ceiling.durableMillis(), Integer.MAX_VALUE), ceiling.durableIndex());
                    batch.put(CEILING_KEY, encodeMax(theirs, ours));
                    batch.delete(CLEAN_KEY);
                    target.write(durable, batch);
                    return new CommitWatermark(recorded.stamp(), Math.max(recorded.index(), theirs.index()));
                } finally {
                    handles.forEach(ColumnFamilyHandle::close);
                }
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("falha ao preparar a base para a troca: " + e.getMessage(), e);
        }
    }

    /** Fecha o RocksDB, renomeia os diretórios e reabre. Se a renomeação falhar, a base anterior volta. */
    private Path swapDirectory(Path prepared) {
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
        return replaced;
    }

    private void reopen() {
        try {
            openDatabase(directory.toString(), true);
        } catch (Exception e) {
            // sem base aberta, quem pede a marca d'água não pode tentar gravar o teto num RocksDB fechado
            ceiling.close(() -> { });
            throw new RuntimeException(Messages.get("rocksdb_open_failed", directory.toString()), e);
        }
    }

    // ---- VersionedKvBackend ----

    /** Marca d'água de backup: não passa de um lote ainda em gravação, para o incremental não perdê-lo. */
    @Override
    public long currentVersion() {
        return sequencer.currentVersion();
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
            // com operações em curso a marca poderia cobrir um lote que a posição registrada não conta
            if (gate.quiesce(MARK_CLEAN_TIMEOUT_MS)) {
                try {
                    writeCleanMarker();
                } finally {
                    gate.resume();
                }
            }
            ceiling.close(() -> {
                if (dataHandle != null) dataHandle.close();
                if (versionsHandle != null) versionsHandle.close();
                if (db != null) db.close();
            });
            ceilingOptions.close();
            writeOptions.close();
            unsyncedOptions.close();
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
