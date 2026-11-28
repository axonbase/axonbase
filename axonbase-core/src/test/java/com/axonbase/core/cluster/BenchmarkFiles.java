package com.axonbase.core.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Limpeza do diretório temporário que um medidor criou para si. */
final class BenchmarkFiles {

    private BenchmarkFiles() {
    }

    static void delete(Path created) throws IOException {
        try (Stream<Path> walk = Files.walk(created)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
