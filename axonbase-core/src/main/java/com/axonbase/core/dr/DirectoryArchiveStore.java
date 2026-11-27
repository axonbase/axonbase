package com.axonbase.core.dr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
        // o que foi arquivado precisa sobreviver a uma queda de energia; o download pode ser refeito
        copy(file, resolve(key), true);
    }

    @Override
    public void get(String key, Path target) {
        copy(resolve(key), target, false);
    }

    /** Copia para um arquivo temporário e renomeia: um objeto pela metade nunca aparece. */
    private static void copy(Path source, Path target, boolean durable) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path partial = target.resolveSibling(target.getFileName() + PARTIAL);
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
            if (durable) {
                force(partial, StandardOpenOption.WRITE);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (durable) {
                forceDirectory(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao copiar " + source + " para " + target, e);
        }
    }

    private static void force(Path path, StandardOpenOption mode) throws IOException {
        try (FileChannel channel = FileChannel.open(path, mode)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try {
            force(directory, StandardOpenOption.READ);
        } catch (IOException unsupported) {
            // há sistemas de arquivos que não deixam sincronizar um diretório
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
