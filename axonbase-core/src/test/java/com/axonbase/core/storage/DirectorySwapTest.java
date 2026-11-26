package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Troca de diretório: recuperação de marcador ilegível")
class DirectorySwapTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("marcador vazio com a base antiga toda em .replaced devolve a base antiga")
    void emptyMarkerRestoresOldBase() throws IOException {
        Path replaced = Files.createDirectories(tmp.resolve(".replaced-100"));
        Files.writeString(replaced.resolve("CURRENT"), "antigo");
        Files.writeString(replaced.resolve("000001.sst"), "dados");
        Files.writeString(tmp.resolve(".swap"), "");
        assertTrue(DirectorySwap.recover(tmp));
        assertEquals("antigo", Files.readString(tmp.resolve("CURRENT")));
        assertTrue(Files.exists(tmp.resolve("000001.sst")));
        assertFalse(Files.exists(tmp.resolve(".swap")));
    }

    @Test
    @DisplayName("marcador ilegível nunca apaga arquivo que já está em dir")
    void garbledMarkerDeletesNothing() throws IOException {
        Path replaced = Files.createDirectories(tmp.resolve(".replaced-100"));
        Files.writeString(replaced.resolve("A"), "movido");
        Files.writeString(tmp.resolve("B"), "ainda aqui");
        Files.writeString(tmp.resolve(".swap"), "lixo");
        assertTrue(DirectorySwap.recover(tmp));
        assertEquals("ainda aqui", Files.readString(tmp.resolve("B")));
        assertEquals("movido", Files.readString(tmp.resolve("A")));
    }

    @Test
    @DisplayName("troca completa leva o conteúdo preparado e guarda o anterior")
    void swapKeepsPrevious() throws IOException {
        Files.writeString(tmp.resolve("antigo"), "a");
        Path prepared = Files.createDirectories(DirectorySwap.restoringDir(tmp));
        Files.writeString(prepared.resolve("novo"), "n");
        Path replaced = DirectorySwap.swap(tmp, prepared);
        assertEquals("n", Files.readString(tmp.resolve("novo")));
        assertFalse(Files.exists(tmp.resolve("antigo")));
        assertEquals("a", Files.readString(replaced.resolve("antigo")));
        assertFalse(Files.exists(tmp.resolve(".swap")));
    }
}
