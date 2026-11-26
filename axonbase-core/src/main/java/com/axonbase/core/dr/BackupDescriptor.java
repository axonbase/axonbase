package com.axonbase.core.dr;

import com.axonbase.core.hlc.HlcTimestamp;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O marcador de um backup completo no arquivamento, gravado depois de todos os arquivos dele. O nome diz o número e o
 * carimbo do backup (para escolher sem baixar nada) e o conteúdo lista um arquivo por linha, com o manifesto por
 * último.
 */
record BackupDescriptor(String node, int id, HlcTimestamp stamp) {

    private static final Pattern NAME = Pattern.compile("(\\d{1,9})-(\\d{1,18})-(\\d{1,9})");

    String fileName() {
        return id + "-" + stamp.physical() + "-" + stamp.logical();
    }

    /** Lê o descritor de uma chave {@code .../backups/<nó>/descriptors/<nome>}; vazio para qualquer outra. */
    static Optional<BackupDescriptor> parse(String key) {
        String[] parts = key.split("/");
        if (parts.length < 3 || !parts[parts.length - 2].equals("descriptors")) {
            return Optional.empty();
        }
        Matcher name = NAME.matcher(parts[parts.length - 1]);
        if (!name.matches()) {
            return Optional.empty();
        }
        return Optional.of(new BackupDescriptor(parts[parts.length - 3], Integer.parseInt(name.group(1)),
            new HlcTimestamp(Long.parseLong(name.group(2)), Integer.parseInt(name.group(3)))));
    }
}
