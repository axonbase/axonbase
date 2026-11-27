package com.axonbase.core.dr;

import java.nio.file.Path;
import java.util.List;

/** Onde o arquivamento guarda os objetos. As chaves usam {@code /} e um objeto, uma vez gravado, não muda. */
public interface ArchiveStore {

    void put(String key, Path file);

    /** Grava o objeto em {@code target}, criando os diretórios. */
    void get(String key, Path target);

    /** As chaves que começam com {@code prefix}, em ordem. */
    List<String> list(String prefix);
}
