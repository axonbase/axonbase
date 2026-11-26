package com.axonbase.core.dr;

import com.axonbase.core.journal.JournalSegments;
import com.axonbase.core.journal.SegmentInfo;
import com.axonbase.core.storage.CommitWatermark;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.PhysicalFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copia para o arquivamento o que um nó produz: backups físicos completos e segmentos fechados do journal. Roda ao
 * lado do nó e só lê arquivos que não mudam mais; não abre o {@code BackupEngine}, que o servidor pode estar usando.
 * Cada chamada envia só o que falta.
 */
public final class DrArchiver {

    private final ArchiveStore store;
    private final ArchiveLayout layout;
    private final String node;

    public DrArchiver(ArchiveStore store, ArchiveLayout layout, String node) {
        this.store = store;
        this.layout = layout;
        this.node = ArchiveLayout.require(node);
    }

    /**
     * Envia os backups que o arquivamento ainda não tem. Um backup sem carimbo é ignorado: não serve de base para
     * restaurar a um instante.
     *
     * @return quantos backups foram enviados
     */
    public int pushBackups(Path backupDir) {
        Path meta = backupDir.resolve("meta");
        if (!Files.isDirectory(meta)) {
            return 0;
        }
        List<Integer> ids;
        try (Stream<Path> files = Files.list(meta)) {
            ids = files.map(f -> f.getFileName().toString()).filter(name -> name.matches("\\d{1,9}"))
                .map(Integer::parseInt).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> archived = new HashSet<>(store.list(layout.backups(node)));
        int sent = 0;
        for (int id : ids) {
            CommitWatermark mark = PhysicalBackups.watermarkOfMeta(meta.resolve(String.valueOf(id)));
            if (mark.equals(CommitWatermark.NONE)) {
                continue;
            }
            BackupDescriptor descriptor = new BackupDescriptor(node, id, mark.stamp());
            Optional<BackupDescriptor> existing = archived.stream().map(BackupDescriptor::parse)
                .flatMap(Optional::stream).filter(d -> d.id() == id).findFirst();
            if (existing.isPresent()) {
                if (!existing.get().stamp().equals(mark.stamp())) {
                    throw new IllegalStateException("o backup " + id + " já arquivado é outro: o diretório de "
                        + "backup recomeçou. Arquive numa época nova.");
                }
                continue;
            }
            send(backupDir, descriptor, archived);
            sent++;
        }
        return sent;
    }

    private void send(Path backupDir, BackupDescriptor descriptor, Set<String> archived) {
        StringBuilder manifest = new StringBuilder();
        // os dados primeiro e o manifesto do RocksDB por último, como o próprio BackupEngine grava
        for (PhysicalFiles.Entry file : PhysicalFiles.list(backupDir, descriptor.id())) {
            String key = layout.backupFile(node, file.path());
            // o nome de um arquivo compartilhado carrega o checksum, então o já enviado é o mesmo
            if (!file.shared() || !archived.contains(key)) {
                store.put(key, backupDir.resolve(file.path()));
            }
            manifest.append(file.path()).append('\n');
        }
        try {
            Path listing = Files.createTempFile("axon-dr-descriptor", ".txt");
            try {
                Files.writeString(listing, manifest, StandardCharsets.UTF_8);
                store.put(layout.descriptors(node) + descriptor.fileName(), listing);
            } finally {
                Files.deleteIfExists(listing);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return quantos segmentos fechados do journal foram enviados */
    public int pushSegments(Path journalDir) {
        List<SegmentInfo> segments;
        try {
            segments = JournalSegments.list(journalDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> archived = new HashSet<>(store.list(layout.journal(node)));
        int sent = 0;
        for (SegmentInfo segment : segments) {
            String key = layout.journal(node) + segment.name();
            Path file = journalDir.resolve(segment.name());
            // a retenção local pode apagar o segmento entre a listagem e o envio
            if (!archived.contains(key) && Files.exists(file)) {
                store.put(key, file);
                sent++;
            }
        }
        return sent;
    }
}
