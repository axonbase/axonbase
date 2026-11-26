package com.axonbase.core.cluster;

import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Prepara a base de um learner a partir de um backup físico. O backup de um nó de cluster carrega o índice do
 * log em que foi tirado ({@link RaftApplier#APPLIED_KEY}); ao subir, o learner pede ao líder só o que veio
 * depois.
 */
public final class LearnerBootstrap {

    private LearnerBootstrap() {
    }

    /**
     * Restaura o backup mais novo de {@code backupDir} em {@code dataDir}, se este estiver vazio ou não existir.
     *
     * @return o ponto do log restaurado, ou vazio quando {@code dataDir} já tem uma base (que não é tocada)
     * @throws IllegalStateException se não há backup ou se ele não veio de um nó de cluster
     */
    public static Optional<RaftApplier.Applied> restore(Path backupDir, Path dataDir) {
        if (hasFiles(dataDir)) {
            return Optional.empty();
        }
        if (!Files.isDirectory(backupDir)) {
            throw new IllegalStateException("diretório de backup não existe: " + backupDir);
        }
        int latest;
        try (PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            List<PhysicalBackups.Info> all = backups.list();
            if (all.isEmpty()) {
                throw new IllegalStateException("nenhum backup em " + backupDir);
            }
            latest = all.getLast().id();
        }
        PhysicalBackups.restore(backupDir, latest, dataDir);
        Optional<RaftApplier.Applied> applied;
        try (RocksDbBackend restored = new RocksDbBackend(dataDir.toString(), RocksDbBackend.SyncMode.OFF)) {
            applied = RaftApplier.applied(restored);
        }
        if (applied.isEmpty()) {
            delete(dataDir);
            throw new IllegalStateException("o backup " + latest + " de " + backupDir
                + " não veio de um nó de cluster: não diz em que ponto do log foi tirado");
        }
        return applied;
    }

    private static boolean hasFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.findAny().isPresent();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
