package com.axonbase.core.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Troca o conteúdo de um diretório de dados por outro já pronto, sem renomear o diretório em si. Em Docker o
 * diretório de dados costuma ser a raiz de um volume montado, e uma raiz de montagem não pode ser renomeada;
 * mover os arquivos de dentro funciona em qualquer caso e, ficando tudo no mesmo sistema de arquivos, cada
 * movimento é uma renomeação (rápida, sem copiar bytes).
 *
 * <p>Convenção: nomes que começam com ponto são do mecanismo ({@code .restoring}, {@code .replaced-<instante>},
 * {@code .swap}) e nunca são movidos como dado. A troca grava o marcador {@code .swap} com a fase em curso, e
 * {@link #recover} desfaz uma troca interrompida devolvendo a base anterior.</p>
 */
final class DirectorySwap {

    static final String RESTORING = ".restoring";
    private static final String REPLACED_PREFIX = ".replaced-";
    private static final String MARKER = ".swap";

    private DirectorySwap() {
    }

    static Path restoringDir(Path dir) {
        return dir.resolve(RESTORING);
    }

    /**
     * Move o conteúdo de {@code prepared} (normalmente {@code dir/.restoring}) para {@code dir}, guardando o
     * conteúdo anterior em {@code dir/.replaced-<instante>}. Devolve esse diretório. Só a base mais recente
     * anterior é mantida: as {@code .replaced-*} mais antigas são apagadas ao fim.
     */
    static Path swap(Path dir, Path prepared) throws IOException {
        Path replaced = dir.resolve(REPLACED_PREFIX + System.currentTimeMillis());
        Files.createDirectories(replaced);
        Files.writeString(dir.resolve(MARKER), "1:" + replaced.getFileName());
        for (Path p : dataEntries(dir)) {
            move(p, replaced.resolve(p.getFileName()));
        }
        Files.writeString(dir.resolve(MARKER), "2:" + replaced.getFileName());
        for (Path p : entries(prepared)) {
            move(p, dir.resolve(p.getFileName()));
        }
        Files.deleteIfExists(dir.resolve(MARKER));
        deleteTree(prepared);
        for (Path old : replacedDirs(dir)) {
            if (!old.equals(replaced)) {
                deleteTree(old);
            }
        }
        return replaced;
    }

    /**
     * Se uma troca foi interrompida (o marcador {@code .swap} existe), devolve a base anterior ao lugar e
     * descarta o que sobrou da restaurada. Devolve {@code true} quando recuperou algo.
     */
    static boolean recover(Path dir) throws IOException {
        Path marker = dir.resolve(MARKER);
        if (!Files.exists(marker)) {
            return false;
        }
        String[] parts = Files.readString(marker).trim().split(":", 2);
        Path replaced = dir.resolve(parts.length > 1 ? parts[1] : "");
        if (parts.length > 1 && Files.isDirectory(replaced)) {
            if ("2".equals(parts[0])) {
                // fase 2: o que está em dir é a restaurada pela metade; a anterior inteira está em replaced
                for (Path p : dataEntries(dir)) {
                    deleteTree(p);
                }
            }
            for (Path p : entries(replaced)) {
                move(p, dir.resolve(p.getFileName()));
            }
            deleteTree(replaced);
        }
        deleteTree(restoringDir(dir));
        Files.deleteIfExists(marker);
        return true;
    }

    // ---- utilidades ----

    private static List<Path> dataEntries(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> !p.getFileName().toString().startsWith(".")).forEach(out::add);
        }
        return out;
    }

    private static List<Path> entries(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.forEach(out::add);
            }
        }
        return out;
    }

    private static List<Path> replacedDirs(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().startsWith(REPLACED_PREFIX)).forEach(out::add);
        }
        return out;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    static void deleteTree(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (IOException ignored) {
            // melhor esforço
        }
    }
}
