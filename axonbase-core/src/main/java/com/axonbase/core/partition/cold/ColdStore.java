package com.axonbase.core.partition.cold;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Armazenamento de objetos da camada fria. As chaves usam {@code /} como separador. Gravar é atômico: ninguém vê um
 * objeto pela metade.
 */
public interface ColdStore {

    /** Grava o arquivo como o objeto {@code key}. */
    void upload(String key, Path file) throws IOException;

    /**
     * Copia o objeto para {@code target}.
     *
     * @return {@code false} quando o objeto não existe
     */
    boolean download(String key, Path target) throws IOException;

    /** Grava um objeto pequeno (documentos de controle). */
    void write(String key, byte[] content) throws IOException;

    /** Lê um objeto pequeno, ou vazio quando não existe. */
    Optional<byte[]> read(String key) throws IOException;

    /** Apaga o objeto; apagar o que não existe não é erro. */
    void delete(String key) throws IOException;

    /** Todas as chaves que começam com {@code prefix}. */
    List<String> list(String prefix) throws IOException;

    /** Descrição para logs, sem credenciais. */
    String describe();
}
