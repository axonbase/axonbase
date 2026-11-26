package com.axonbase.core.dr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/** Arquivamento num diretório, local ou montado pela rede. */
public final class DirectoryArchiveStore implements ArchiveStore {

    private static final String PARTIAL = ".partial";

    private final Path root;

    public DirectoryArchiveStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (key.isEmpty() || !file.startsWith(root) || file.equals(root)) {
            throw new IllegalArgumentException("chave inválida: " + key);
        }
        return file;
    }

    @Override
    public void put(String key, Path file) {
        copy(file, resolve(key));
    }

    @Override
    public void get(String key, Path target) {
        copy(resolve(key), target);
    }

    /** Copia para um arquivo temporário e renomeia: um objeto pela metade nunca aparece. */
    private static void copy(Path source, Path target) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path partial = target.resolveSibling(target.getFileName() + PARTIAL);
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao copiar " + source + " para " + target, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                .map(file -> root.relativize(file).toString().replace('\\', '/'))
                .filter(key -> key.startsWith(prefix) && !key.endsWith(PARTIAL)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
