package com.axonbase.core.partition.cold;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Objetos frios abertos em disco local, com teto de tamanho: quando passa, sai o usado há mais tempo. Os objetos são
 * imutáveis, então o cache nunca fica velho. Os arquivos vão para um subdiretório próprio deste processo.
 */
public final class ColdCache implements AutoCloseable {

    private static final class Entry {
        final ColdObject.Reader reader;
        final Path file;
        final long size;
        volatile long used;

        Entry(ColdObject.Reader reader, Path file, long size) {
            this.reader = reader;
            this.file = file;
            this.size = size;
        }
    }

    private final ColdStore store;
    private final Path directory;
    private final long maxBytes;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    /** Uma trava por objeto: um download lento não segura a leitura dos outros. */
    private final ConcurrentMap<String, Object> loading = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong();
    private final AtomicLong downloads = new AtomicLong();

    public ColdCache(ColdStore store, Path parent, long maxBytes) {
        this.store = store;
        this.maxBytes = maxBytes;
        try {
            Files.createDirectories(parent);
            this.directory = Files.createTempDirectory(parent, "axon-cold-");
        } catch (IOException e) {
            throw new IllegalStateException("não foi possível criar o cache da camada fria em " + parent, e);
        }
    }

    /** O objeto aberto, baixando-o se preciso. Se o armazenamento falhar, a leitura falha. */
    public ColdObject.Reader open(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            synchronized (loading.computeIfAbsent(key, k -> new Object())) {
                entry = entries.get(key);
                if (entry == null) {
                    entry = load(key);
                    entry.used = clock.incrementAndGet();
                    entries.put(key, entry);
                    evict(key);
                }
            }
        }
        entry.used = clock.incrementAndGet();
        return entry.reader;
    }

    /** Quantos downloads já foram feitos (testes e diagnóstico). */
    public long downloads() {
        return downloads.get();
    }

    private Entry load(String key) {
        String name = UUID.randomUUID().toString();
        Path compressed = directory.resolve(name + ".gz");
        Path inflated = directory.resolve(name + ".axc");
        boolean opened = false;
        try {
            downloads.incrementAndGet();
            if (!store.download(key, compressed)) {
                throw AxonError.internal(Messages.get("cold_unavailable", key, "o objeto não existe"));
            }
            ColdObject.inflate(compressed, inflated);
            ColdObject.Reader reader = ColdObject.open(inflated);
            Entry entry = new Entry(reader, inflated, Files.size(inflated));
            opened = true;
            return entry;
        } catch (ColdObject.CorruptException e) {
            throw ColdFailure.error(Messages.get("cold_corrupted", key));
        } catch (AxonError e) {
            throw ColdFailure.mark(e); // inclui o erro do cliente S3
        } catch (IOException | RuntimeException e) {
            throw ColdFailure.error(Messages.get("cold_unavailable", key, e.getMessage()));
        } finally {
            delete(compressed);
            if (!opened) {
                delete(inflated);
            }
        }
    }

    /** Tira os mais antigos até caber, sem tirar o que acabou de entrar. */
    private synchronized void evict(String keep) {
        long total = entries.values().stream().mapToLong(e -> e.size).sum();
        while (total > maxBytes) {
            var oldest = entries.entrySet().stream().filter(e -> !e.getKey().equals(keep))
                .min(Comparator.comparingLong(e -> e.getValue().used)).orElse(null);
            if (oldest == null) {
                return;
            }
            Entry removed = entries.remove(oldest.getKey());
            if (removed != null) {
                total -= removed.size;
                delete(removed.file); // quem ainda lê continua com o mapeamento válido
            }
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // sobra no diretório temporário do processo
        }
    }

    @Override
    public void close() {
        entries.values().forEach(e -> delete(e.file));
        entries.clear();
        delete(directory);
    }
}
