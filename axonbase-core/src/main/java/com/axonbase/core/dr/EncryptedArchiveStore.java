package com.axonbase.core.dr;

import com.axonbase.core.backup.StreamEncryption;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Cifra os objetos no caminho para o armazenamento, no mesmo formato dos backups do servidor. */
public final class EncryptedArchiveStore implements ArchiveStore {

    private final ArchiveStore store;
    private final String password;

    public EncryptedArchiveStore(ArchiveStore store, String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("a chave de cifra está vazia");
        }
        this.store = store;
        this.password = password;
    }

    @Override
    public void put(String key, Path file) {
        Path sealed = null;
        try {
            sealed = Files.createTempFile("axon-dr-", ".enc");
            try (OutputStream out = StreamEncryption.encrypting(Files.newOutputStream(sealed), password)) {
                Files.copy(file, out);
            }
            store.put(key, sealed);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao cifrar " + file, e);
        } finally {
            delete(sealed);
        }
    }

    @Override
    public void get(String key, Path target) {
        Path sealed = null;
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            sealed = Files.createTempFile(target.toAbsolutePath().getParent(), "axon-dr-", ".enc");
            store.get(key, sealed);
            Path plain = target.resolveSibling(target.getFileName() + ".partial");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(sealed), 1 << 16)) {
                if (!StreamEncryption.HEADER.equals(firstLine(in))) {
                    throw new IOException("o objeto " + key + " não está cifrado no formato esperado");
                }
                try (InputStream opened = StreamEncryption.decrypting(in, password)) {
                    Files.copy(opened, plain, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(plain, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao decifrar " + key, e);
        } finally {
            delete(sealed);
        }
    }

    private static String firstLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next = in.read();
        while (next >= 0 && next != '\n' && line.size() < StreamEncryption.HEADER.length() + 1) {
            line.write(next);
            next = in.read();
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static void delete(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // arquivo temporário: o sistema limpa depois
            }
        }
    }

    @Override
    public List<String> list(String prefix) {
        return store.list(prefix);
    }
}
