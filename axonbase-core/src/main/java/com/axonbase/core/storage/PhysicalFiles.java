package com.axonbase.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Os arquivos de um backup físico, para copiá-los a um armazenamento de objetos e trazê-los de volta (veja
 * {@code docs/backup-fisico.md}). Só enxerga três pastas do diretório de backup: {@code meta},
 * {@code private} e {@code shared_checksum}, e valida todo caminho recebido de fora.
 */
public final class PhysicalFiles {

    /** Um arquivo de um backup: caminho relativo ao diretório de backup, tamanho e se é compartilhado. */
    public record Entry(String path, long sizeBytes, boolean shared) {
    }

    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final List<String> TOP = List.of("meta", "private", "shared_checksum");
    private static final String SHARED = "shared_checksum/";

    private PhysicalFiles() {
    }

    /** O diretório de backup a usar: o próprio ({@code source} nulo) ou a área de importação da origem. */
    public static Path importRoot(Path backupDir, String source) {
        if (source == null) {
            return backupDir;
        }
        if (!SOURCE.matcher(source).matches() || source.equals(".") || source.equals("..")) {
            throw new IllegalArgumentException("origem inválida: " + source);
        }
        return backupDir.resolve("imports").resolve(source);
    }

    /** Apaga a área de importação da origem inteira. */
    public static void deleteImport(Path backupDir, String source) {
        if (source == null) {
            throw new IllegalArgumentException("origem obrigatória");
        }
        DirectorySwap.deleteTree(importRoot(backupDir, source));
    }

    /**
     * Resolve um caminho relativo recebido de fora para um arquivo dentro do diretório de backup (ou da área de
     * importação). Recusa tudo que sai de {@code meta/}, {@code private/} e {@code shared_checksum/}.
     */
    public static Path resolve(Path backupDir, String source, String relative) {
        if (relative == null || relative.isEmpty() || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0
            || relative.startsWith("/") || relative.endsWith("/")) {
            throw new IllegalArgumentException("caminho inválido: " + relative);
        }
        String[] parts = relative.split("/", -1);
        if (parts.length < 2 || !TOP.contains(parts[0])) {
            throw new IllegalArgumentException("caminho fora de meta, private e shared_checksum: " + relative);
        }
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("caminho inválido: " + relative);
            }
        }
        Path root = importRoot(backupDir, source).toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("caminho fora do diretório de backup: " + relative);
        }
        return resolved;
    }

    /**
     * Lista os arquivos do backup {@code id} (os que o manifesto {@code meta/<id>} referencia), com os de dados
     * primeiro e o manifesto por último: quem copia deve gravar o manifesto por último, porque sem ele o
     * backup não existe.
     */
    public static List<Entry> list(Path backupDir, int id) {
        Path meta = backupDir.resolve("meta").resolve(String.valueOf(id));
        List<String> lines;
        try {
            lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PhysicalBackups.BackupFailedException("manifesto do backup " + id + " ilegível", e);
        }
        int at = 0;
        // linha 1: instante; linha 2: sequência; opcional "metadata <hex>"; depois a contagem de arquivos
        at += 2;
        if (at < lines.size() && lines.get(at).startsWith("metadata")) {
            at++;
        }
        int count;
        try {
            count = Integer.parseInt(lines.get(at++).trim());
        } catch (RuntimeException e) {
            throw new PhysicalBackups.BackupFailedException("manifesto do backup " + id + " com formato inesperado", e);
        }
        List<Entry> data = new ArrayList<>();
        for (int i = 0; i < count && at < lines.size(); i++, at++) {
            String line = lines.get(at);
            int space = line.indexOf(' ');
            String path = space < 0 ? line : line.substring(0, space);
            Path file = resolve(backupDir, null, path);
            try {
                data.add(new Entry(path, Files.size(file), path.startsWith(SHARED)));
            } catch (IOException e) {
                throw new PhysicalBackups.BackupFailedException("arquivo ausente no backup " + id + ": " + path, e);
            }
        }
        if (data.size() != count) {
            throw new PhysicalBackups.BackupFailedException(
                "manifesto do backup " + id + " lista " + count + " arquivos e só " + data.size() + " foram lidos");
        }
        try {
            data.add(new Entry("meta/" + id, Files.size(meta), false));
        } catch (IOException e) {
            throw new PhysicalBackups.BackupFailedException("manifesto do backup " + id + " ilegível", e);
        }
        return data;
    }

    /**
     * Grava um arquivo recebido de fora na área de importação da origem, de forma atômica (temporário e
     * renomeação). Confere o tamanho declarado: se o corpo não tiver exatamente esse tamanho, recusa e não deixa
     * nada. Um arquivo compartilhado (o nome carrega o checksum) que já existe com o mesmo tamanho é aceito sem
     * regravar. Devolve os bytes gravados.
     */
    public static long write(Path backupDir, String source, String relative, InputStream body, long declaredSize)
            throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("origem obrigatória na gravação");
        }
        Path target = resolve(backupDir, source, relative);
        if (relative.startsWith(SHARED) && Files.isRegularFile(target) && Files.size(target) == declaredSize) {
            body.transferTo(OutputStream.nullOutputStream());
            return declaredSize;
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".incoming-", ".tmp");
        long written;
        try (OutputStream out = Files.newOutputStream(tmp)) {
            written = body.transferTo(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        if (declaredSize >= 0 && written != declaredSize) {
            Files.deleteIfExists(tmp);
            throw new IOException("tamanho recebido " + written + " difere do declarado " + declaredSize);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return written;
    }
}
