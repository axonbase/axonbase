package com.axonbase.core.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * Log Raft por nó, em segmentos com checksum por entrada.
 *
 * <p>Truncar apaga os segmentos posteriores e corta um só; compactar apaga segmentos inteiros. Nenhuma das
 * duas operações reescreve dados. A escrita ({@link #append}) vai para o cache do sistema operacional; a
 * durabilidade é pedida com {@link #sync()}, o que permite agrupar várias entradas num fsync.</p>
 *
 * <p>O lote é guardado como os bytes de {@link RaftPayload#encode}, sem reinterpretação: um campo novo do
 * codec persiste sem mudar o log.</p>
 */
public final class FileRaftLog implements AutoCloseable {

    /** Tamanho em que um segmento é fechado e o próximo começa. */
    public static final long DEFAULT_SEGMENT_BYTES = 64L << 20;
    private static final long DEFAULT_CACHE_BYTES = 32L << 20;
    private static final String DIRECTORY = "raft-log";
    private static final String MIGRATING = "raft-log.migrating";
    private static final String LEGACY = "raft.log";
    private static final String LEGACY_DONE = "raft.log.migrated";
    private static final String BASE_FILE = "base";
    /** Presente entre publicar os segmentos migrados e renomear o arquivo antigo. */
    private static final String MIGRATION_MARKER = "migrating";
    private static final int BASE_MAGIC = 0x41585242;
    private static final int BASE_BYTES = 24;

    private final Path directory;
    private final long segmentBytes;
    private final long cacheLimit;
    private final List<LogSegment> segments = new ArrayList<>();
    /** Segura o fsync fora do monitor; quem fecha ou apaga arquivos também precisa dela. */
    private final ReentrantLock syncLock = new ReentrantLock();
    /** Entradas recentes: os bytes para a replicação e, depois da primeira leitura, o lote já decodificado. */
    private final LinkedHashMap<Long, Cached> cache = new LinkedHashMap<>(256, 0.75f, true);
    private long cachedBytes;
    private long baseIndex;
    private long baseTerm;
    private boolean closed;

    public FileRaftLog(Path directory) {
        this(directory, DEFAULT_SEGMENT_BYTES);
    }

    public FileRaftLog(Path directory, long segmentBytes) {
        this(directory, segmentBytes, 0);
    }

    /**
     * @param committedFloor último índice que se sabe confirmado e durável; corrupção abaixo dele impede a
     *                       abertura
     */
    public FileRaftLog(Path directory, long segmentBytes, long committedFloor) {
        this(directory, segmentBytes, committedFloor, DEFAULT_CACHE_BYTES);
    }

    FileRaftLog(Path root, long segmentBytes, long committedFloor, long cacheLimit) {
        this.directory = root.resolve(DIRECTORY);
        this.segmentBytes = segmentBytes;
        this.cacheLimit = cacheLimit;
        try {
            Files.createDirectories(root);
            migrateLegacy(root);
            Files.createDirectories(directory);
            recover(committedFloor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- escrita ----

    private static final class Cached {
        final byte[] payload;
        Entry decoded;

        Cached(byte[] payload, Entry decoded) {
            this.payload = payload;
            this.decoded = decoded;
        }
    }

    /** Acrescenta a entrada. Não faz fsync. */
    public synchronized void append(Entry entry) {
        appendEncoded(entry.index(), entry.term(), RaftPayload.encode(entry.batch()));
        Cached cached = cache.get(entry.index());
        if (cached != null) {
            cached.decoded = entry;
        }
    }

    /** Acrescenta uma entrada cujo lote já está codificado. Não faz fsync. */
    public synchronized void appendEncoded(long index, long term, byte[] payload) {
        ensureOpen();
        try {
            long last = lastIndex();
            if (index != last + 1) {
                if (!isEmpty() || index <= last) {
                    throw new IllegalStateException("log Raft: entrada " + index + " depois da " + last);
                }
                // Log vazio começando adiante (líder antigo ou estado já presente): o anterior vira a base.
                dropSegments();
                saveBase(index - 1, 0);
            }
            LogSegment active = segments.isEmpty() ? null : segments.getLast();
            if (active == null || !active.isEmpty() && active.size() >= segmentBytes) {
                if (active != null) {
                    active.force();
                }
                active = LogSegment.create(directory, index);
                segments.add(active);
            }
            active.append(term, payload);
            remember(index, payload.clone());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * fsync de tudo que foi acrescentado até agora. Segmentos anteriores já foram sincronizados quando
     * fecharam, então só o ativo precisa de disco. Escritas continuam durante o fsync.
     */
    public void sync() {
        syncLock.lock();
        try {
            LogSegment active;
            synchronized (this) {
                ensureOpen();
                active = segments.isEmpty() ? null : segments.getLast();
            }
            if (active != null) {
                active.force();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            syncLock.unlock();
        }
    }

    /** Descarta a entrada {@code index} e as seguintes. */
    public void truncateFrom(long index) {
        syncLock.lock();
        try {
            synchronized (this) {
                ensureOpen();
                if (index <= baseIndex) {
                    long previous = Math.max(0, index - 1);
                    dropSegments();
                    saveBase(previous, previous == baseIndex ? baseTerm : 0);
                    return;
                }
                if (index > lastIndex()) {
                    return;
                }
                // Do último para o primeiro: uma queda no meio deixa um log contíguo.
                while (!segments.isEmpty() && segments.getLast().firstIndex() >= index) {
                    segments.removeLast().delete();
                }
                LogSegment.forceDirectory(directory);
                if (!segments.isEmpty() && segments.getLast().lastIndex() >= index) {
                    segments.getLast().truncateFrom(index);
                }
                forgetFrom(index);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            syncLock.unlock();
        }
    }

    /** Descarta as entradas até {@code index}, inclusive. O termo dele continua disponível em {@link #termAt}. */
    public void compact(long index) {
        syncLock.lock();
        try {
            synchronized (this) {
                ensureOpen();
                long target = Math.min(index, lastIndex());
                if (target <= baseIndex) {
                    return;
                }
                saveBase(target, termAt(target));
                // O ativo fica, mesmo todo coberto, para a próxima entrada continuar nele.
                while (segments.size() > 1 && segments.getFirst().lastIndex() <= baseIndex) {
                    segments.removeFirst().delete();
                }
                LogSegment.forceDirectory(directory);
                forgetThrough(target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            syncLock.unlock();
        }
    }

    /** Descarta o log inteiro e recomeça depois de {@code index} (instalação de snapshot). */
    public void reset(long index, long term) {
        syncLock.lock();
        try {
            synchronized (this) {
                ensureOpen();
                dropSegments();
                saveBase(index, term);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            syncLock.unlock();
        }
    }

    // ---- leitura ----

    /** Último índice conhecido: o da última entrada, ou a base num log vazio. */
    public synchronized long lastIndex() {
        return segments.isEmpty() ? baseIndex : Math.max(baseIndex, segments.getLast().lastIndex());
    }

    /** Primeiro índice ainda disponível (pode ser maior que {@link #lastIndex()} num log vazio). */
    public synchronized long firstIndex() {
        return baseIndex + 1;
    }

    /** Último índice descartado por compactação ou snapshot. */
    public synchronized long baseIndex() {
        return baseIndex;
    }

    /**
     * Último índice dos segmentos fechados cuja escrita mais recente é anterior a {@code epochMillis}; a base,
     * se não há nenhum. O segmento ativo nunca conta.
     */
    public synchronized long lastIndexWrittenBefore(long epochMillis) {
        long index = baseIndex;
        try {
            for (int i = 0; i < segments.size() - 1; i++) {
                if (segments.get(i).lastWrittenMillis() >= epochMillis) {
                    break;
                }
                index = Math.max(index, segments.get(i).lastIndex());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    /** Termo da entrada, ou 0 quando o índice não é conhecido. */
    public synchronized long termAt(long index) {
        if (index == baseIndex) {
            return baseTerm;
        }
        if (index < baseIndex || index > lastIndex()) {
            return 0;
        }
        return segmentOf(index).term(index);
    }

    /** A entrada, ou {@code null} se foi compactada ou ainda não existe. */
    public synchronized Entry entry(long index) {
        if (index <= baseIndex || index > lastIndex()) {
            return null;
        }
        Cached cached = load(index);
        if (cached.decoded == null) {
            cached.decoded = new Entry(index, termAt(index), RaftPayload.decode(cached.payload));
        }
        return cached.decoded;
    }

    /** Bytes do lote como foram gravados, ou {@code null} se a entrada não está disponível. */
    public synchronized byte[] encoded(long index) {
        if (index <= baseIndex || index > lastIndex()) {
            return null;
        }
        return load(index).payload.clone();
    }

    /** Todas as entradas disponíveis. Lê o log inteiro; para diagnóstico e testes. */
    public synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (long i = baseIndex + 1; i <= lastIndex(); i++) {
            out.add(entry(i));
        }
        return List.copyOf(out);
    }

    @Override
    public void close() throws IOException {
        syncLock.lock();
        try {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                IOException failure = null;
                for (LogSegment segment : segments) {
                    try {
                        segment.close();
                    } catch (IOException e) {
                        failure = e;
                    }
                }
                segments.clear();
                cache.clear();
                cachedBytes = 0;
                if (failure != null) {
                    throw failure;
                }
            }
        } finally {
            syncLock.unlock();
        }
    }

    // ---- interno ----

    private boolean isEmpty() {
        return lastIndex() == baseIndex;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("log Raft fechado: " + directory);
        }
    }

    private LogSegment segmentOf(long index) {
        int low = 0;
        int high = segments.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (segments.get(mid).firstIndex() <= index) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return segments.get(low);
    }

    private Cached load(long index) {
        Cached cached = cache.get(index);
        if (cached != null) {
            return cached;
        }
        try {
            return remember(index, segmentOf(index).read(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Cached remember(long index, byte[] payload) {
        Cached added = new Cached(payload, null);
        if (payload.length > cacheLimit) {
            return added;
        }
        Cached replaced = cache.put(index, added);
        cachedBytes += payload.length - (replaced == null ? 0 : replaced.payload.length);
        Iterator<Map.Entry<Long, Cached>> eldest = cache.entrySet().iterator();
        while (cachedBytes > cacheLimit && eldest.hasNext()) {
            cachedBytes -= eldest.next().getValue().payload.length;
            eldest.remove();
        }
        return added;
    }

    private void forgetFrom(long index) {
        forget(cached -> cached >= index);
    }

    private void forgetThrough(long index) {
        forget(cached -> cached <= index);
    }

    private void forget(java.util.function.LongPredicate obsolete) {
        Iterator<Map.Entry<Long, Cached>> entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Long, Cached> next = entries.next();
            if (obsolete.test(next.getKey())) {
                cachedBytes -= next.getValue().payload.length;
                entries.remove();
            }
        }
    }

    private void dropSegments() throws IOException {
        while (!segments.isEmpty()) {
            segments.removeLast().delete();
        }
        LogSegment.forceDirectory(directory);
        cache.clear();
        cachedBytes = 0;
    }

    private void saveBase(long index, long term) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BASE_BYTES);
        buffer.putInt(BASE_MAGIC).putLong(index).putLong(term);
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, BASE_BYTES - 4);
        buffer.putInt((int) crc.getValue());
        LogSegment.writeAtomically(directory.resolve(BASE_FILE), buffer.array());
        baseIndex = index;
        baseTerm = term;
    }

    private void loadBase() throws IOException {
        Path file = directory.resolve(BASE_FILE);
        if (!Files.exists(file)) {
            return;
        }
        byte[] raw = Files.readAllBytes(file);
        CRC32C crc = new CRC32C();
        crc.update(raw, 0, Math.max(0, raw.length - 4));
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        if (raw.length != BASE_BYTES || buffer.getInt(0) != BASE_MAGIC
            || buffer.getInt(BASE_BYTES - 4) != (int) crc.getValue()) {
            throw new CorruptRaftLogException("marcador de compactação do log Raft corrompido: " + file);
        }
        baseIndex = buffer.getLong(4);
        baseTerm = buffer.getLong(12);
    }

    private void recover(long committedFloor) throws IOException {
        loadBase();
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(p -> p.getFileName().toString().endsWith(LogSegment.SUFFIX))
                .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        try {
            for (int i = 0; i < files.size(); i++) {
                LogSegment segment = LogSegment.open(files.get(i), i == files.size() - 1, committedFloor);
                if (segment != null) {
                    segments.add(segment);
                }
            }
            for (int i = 1; i < segments.size(); i++) {
                long expected = segments.get(i - 1).lastIndex() + 1;
                if (segments.get(i).firstIndex() != expected) {
                    throw new CorruptRaftLogException("faltam entradas no log Raft entre os índices " + expected
                        + " e " + (segments.get(i).firstIndex() - 1) + " em " + directory);
                }
            }
            if (!segments.isEmpty() && segments.getLast().lastIndex() <= baseIndex) {
                dropSegments();
            }
            while (!segments.isEmpty() && segments.getFirst().lastIndex() <= baseIndex) {
                segments.removeFirst().delete();
            }
            if (!segments.isEmpty() && segments.getFirst().firstIndex() > baseIndex + 1) {
                throw new CorruptRaftLogException("faltam entradas no log Raft entre os índices " + (baseIndex + 1)
                    + " e " + (segments.getFirst().firstIndex() - 1) + " em " + directory);
            }
        } catch (RuntimeException | IOException failure) {
            for (LogSegment segment : segments) {
                segment.close();
            }
            throw failure;
        }
    }

    /**
     * Converte o {@code raft.log} das versões anteriores. Escreve num diretório temporário e troca o nome no
     * fim, então uma queda no meio só faz a migração recomeçar.
     */
    private void migrateLegacy(Path root) throws IOException {
        Path legacy = root.resolve(LEGACY);
        Path marker = directory.resolve(MIGRATION_MARKER);
        if (!Files.exists(legacy)) {
            Files.deleteIfExists(marker);
            return;
        }
        if (Files.exists(directory) && !Files.exists(marker)) {
            // Uma versão antiga rodou neste diretório depois da migração: os segmentos estão velhos.
            throw new CorruptRaftLogException("há um raft.log gravado por uma versão antiga ao lado de " + directory
                + ". Apague o diretório do Raft deste nó para o líder reenviar o estado");
        }
        if (!Files.exists(directory)) {
            Path building = root.resolve(MIGRATING);
            deleteTree(building);
            Path staging = building.resolve(DIRECTORY);
            Files.createDirectories(staging);
            try (FileRaftLog target = new FileRaftLog(building, segmentBytes, 0, 0)) {
                LegacyRaftLog.read(legacy, entry -> {
                    // Versões antigas podiam gravar um índice repetido depois de truncar: o último vale.
                    if (entry.index() <= target.lastIndex()) {
                        target.truncateFrom(entry.index());
                    } else if (entry.index() != target.lastIndex() + 1) {
                        // Buraco: o que veio antes já estava aplicado e não pode ser reenviado.
                        target.reset(entry.index() - 1, 0);
                    }
                    target.append(entry);
                });
                target.sync();
            }
            Files.createFile(staging.resolve(MIGRATION_MARKER));
            LogSegment.forceDirectory(staging);
            Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
            LogSegment.forceDirectory(root);
            deleteTree(building);
        }
        Files.move(legacy, root.resolve(LEGACY_DONE), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        LogSegment.forceDirectory(root);
        Files.deleteIfExists(marker);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    public record Entry(long index, long term, CommittedBatch batch) { }
}
