package com.axonbase.core.dr;

import com.axonbase.core.cluster.ClusterRestore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.partition.ColdTier;
import com.axonbase.core.storage.PhysicalFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Refaz a base de um grupo a partir do arquivamento, depois de perdido o cluster: escolhe o backup, baixa o que
 * precisa em paralelo e reaplica o journal até o instante pedido ({@link ClusterRestore}).
 */
public final class DrRestore {

    /** Folga para escolher segmentos: um segmento cobre no máximo 30 s, e relógios divergem pouco. */
    private static final long MARGIN_MILLIS = 600_000;
    private static final Pattern SEGMENT = Pattern.compile("seg-(\\d{15})-\\d{10}\\.axj");

    /**
     * @param node        nó que arquivou o backup usado
     * @param backupId    número dele
     * @param backupStamp carimbo dele
     * @param segments    segmentos de journal baixados
     * @param bytes       bytes baixados
     * @param downloadMillis tempo de download
     */
    public record Result(ClusterRestore.Prepared prepared, String node, int backupId, HlcTimestamp backupStamp,
                         int segments, long bytes, long downloadMillis) {
    }

    private DrRestore() {
    }

    /**
     * @param work      diretório vazio para os downloads
     * @param target    base nova, vazia ou inexistente
     * @param until     instante pedido, inclusive, ou {@code null} para o último arquivado
     * @param transfers downloads em paralelo
     */
    public static Result restore(ArchiveStore store, ArchiveLayout layout, Path work, Path target,
                                 HlcTimestamp until, int transfers) {
        return restore(store, layout, work, target, until, transfers, ColdTier.NONE);
    }

    /**
     * @param cold a camada fria que a base referencia; o arquivamento não copia os objetos dela
     */
    public static Result restore(ArchiveStore store, ArchiveLayout layout, Path work, Path target,
                                 HlcTimestamp until, int transfers, ColdTier cold) {
        requireEmpty(work);
        List<BackupDescriptor> candidates = candidates(store, layout, until);
        long started = System.nanoTime();
        ClusterRestore.BaseTooNew tooNew = null;
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            Path directory = attempt == 0 ? work : work.resolve("tentativa-" + (attempt + 1));
            try {
                return restoreFrom(store, layout, candidates.get(attempt), directory, target, until, transfers,
                    started, cold);
            } catch (ClusterRestore.BaseTooNew newer) {
                tooNew = newer;
            }
        }
        throw tooNew;
    }

    private static Result restoreFrom(ArchiveStore store, ArchiveLayout layout, BackupDescriptor backup, Path work,
                                      Path target, HlcTimestamp until, int transfers, long started, ColdTier cold) {
        Path backupDir = work.resolve("backup");
        Map<String, Path> downloads = new LinkedHashMap<>();
        Path listing = work.resolve("descriptor.txt");
        store.get(layout.descriptors(backup.node()) + backup.fileName(), listing);
        for (String path : lines(listing)) {
            // o caminho vem do armazenamento: só vale dentro do diretório do backup
            Path file = PhysicalFiles.resolve(backupDir, null, path);
            downloads.put(layout.backupFile(backup.node(), path), file);
        }
        Map<String, Path> segments = segmentsAfter(store, layout, work.resolve("journal"), backup.stamp());
        downloads.putAll(segments);
        download(store, downloads, transfers);
        long bytes = downloads.values().stream().mapToLong(DrRestore::size).sum();
        long millis = (System.nanoTime() - started) / 1_000_000L;
        List<Path> journals = segments.values().stream().map(Path::getParent).distinct().toList();
        ClusterRestore.Prepared prepared =
            ClusterRestore.prepare(backupDir, backup.id(), journals, target, until, cold);
        return new Result(prepared, backup.node(), backup.id(), backup.stamp(), segments.size(), bytes, millis);
    }

    /** Os backups cujo carimbo não passa do instante pedido, do mais novo para o mais velho. */
    private static List<BackupDescriptor> candidates(ArchiveStore store, ArchiveLayout layout, HlcTimestamp until) {
        List<BackupDescriptor> found = store.list(layout.backupsRoot()).stream().map(BackupDescriptor::parse)
            .flatMap(Optional::stream)
            .filter(d -> until == null || d.stamp().compareTo(until) <= 0)
            .sorted(Comparator.comparing(BackupDescriptor::stamp).reversed())
            .toList();
        if (found.isEmpty()) {
            throw new IllegalStateException("nenhum backup arquivado em " + layout.backupsRoot()
                + (until == null ? "" : " é anterior ao instante pedido"));
        }
        return found;
    }

    /**
     * Os segmentos de cada nó que podem ter lotes depois do backup. Um segmento só fica de fora quando o seguinte do
     * mesmo nó já começou bem antes do backup: o nome dele é a marca d'água na abertura, que cobre o anterior.
     */
    private static Map<String, Path> segmentsAfter(ArchiveStore store, ArchiveLayout layout, Path directory,
                                                   HlcTimestamp backup) {
        Map<String, List<String>> byNode = new LinkedHashMap<>();
        for (String key : store.list(layout.journalRoot())) {
            String[] parts = key.substring(layout.journalRoot().length()).split("/");
            if (parts.length == 2 && SEGMENT.matcher(parts[1]).matches()) {
                byNode.computeIfAbsent(ArchiveLayout.require(parts[0]), n -> new ArrayList<>()).add(parts[1]);
            }
        }
        long cutoff = backup.physical() - MARGIN_MILLIS;
        Map<String, Path> out = new LinkedHashMap<>();
        byNode.forEach((node, names) -> {
            names.sort(Comparator.naturalOrder());
            for (int i = 0; i < names.size(); i++) {
                if (i + 1 < names.size() && createdMillis(names.get(i + 1)) < cutoff) {
                    continue;
                }
                out.put(layout.journal(node) + names.get(i), directory.resolve(node).resolve(names.get(i)));
            }
        });
        return out;
    }

    private static long createdMillis(String segment) {
        Matcher matcher = SEGMENT.matcher(segment);
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : Long.MAX_VALUE;
    }

    private static void download(ArchiveStore store, Map<String, Path> downloads, int transfers) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, transfers));
        try {
            List<Future<?>> running = new ArrayList<>(downloads.size());
            downloads.forEach((key, file) -> running.add(pool.submit(() -> store.get(key, file))));
            for (Future<?> task : running) {
                task.get();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("download interrompido", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("falha no download: " + failed.getCause().getMessage(),
                failed.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void requireEmpty(Path work) {
        try {
            Files.createDirectories(work);
            try (Stream<Path> entries = Files.list(work)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalStateException("o diretório de trabalho não está vazio: " + work);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
