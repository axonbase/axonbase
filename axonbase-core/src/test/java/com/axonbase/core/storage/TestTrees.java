package com.axonbase.core.storage;

import java.nio.file.Path;

/** Utilidade de teste: apaga uma árvore de diretórios. */
public final class TestTrees {

    private TestTrees() {
    }

    public static void delete(Path directory) {
        DirectorySwap.deleteTree(directory);
    }
}
