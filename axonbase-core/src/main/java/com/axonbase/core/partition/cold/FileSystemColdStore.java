package com.axonbase.core.partition.cold;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Camada fria num diretório: para testes, um volume compartilhado entre os nós ou um disco barato. Grava num arquivo
 * temporário e renomeia.
 */
public final class FileSystemColdStore implements ColdStore {

    private static final String TEMP_PREFIX = ".tmp-";

    private final Path root;

    public FileSystemColdStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("o caminho da camada fria não é um diretório: " + this.root, e);
        } catch (IOException e) {
            throw new IllegalStateException("não foi possível criar o diretório da camada fria " + this.root, e);
        }
    }

    @Override
    public void upload(String key, Path file) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, ".part");
        try {
            Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
            publish(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public boolean download(String key, Path target) throws IOException {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            return false;
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    @Override
    public void write(String key, byte[] content) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), TEMP_PREFIX, ".part");
        try {
            Files.write(temp, content);
            publish(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public Optional<byte[]> read(String key) throws IOException {
        Path source = resolve(key);
        return Files.isRegularFile(source) ? Optional.of(Files.readAllBytes(source)) : Optional.empty();
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().startsWith(TEMP_PREFIX))
                .map(p -> root.relativize(p).toString().replace(root.getFileSystem().getSeparator(), "/"))
                .filter(k -> k.startsWith(prefix))
                .sorted()
                .toList();
        }
    }

    @Override
    public String describe() {
        return "dir:" + root;
    }

    private static void publish(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** O arquivo da chave, sem deixar ela sair do diretório. */
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("chave de objeto fora da camada fria: " + key);
        }
        return path;
    }
}
