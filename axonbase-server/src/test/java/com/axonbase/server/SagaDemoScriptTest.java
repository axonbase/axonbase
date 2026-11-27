package com.axonbase.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** O script da demonstração de SAGA, lido como texto: o classpath precisa ter tudo o que o servidor carrega. */
class SagaDemoScriptTest {

    @Test
    void classpathTemTodosOsModulosEAsDependenciasDoServidor() throws IOException {
        String script = Files.readString(Path.of("..", "examples", "saga-microservices", "start_sqlalchemy_demo.sh"));
        String modules = script.lines().filter(line -> line.startsWith("for module in ")).findFirst().orElseThrow()
            .replace(";", " ");

        for (String module : List.of("axonbase-common", "axonbase-value", "axonbase-parser", "axonbase-pl",
            "axonbase-core", "axonbase-server")) {
            assertTrue((modules + " ").contains(" " + module + " "), module);
        }
        // RocksDB e H3 não estão sob as pastas do Jetty e do SLF4J: vêm da resolução do Maven
        assertTrue(script.contains("dependency:copy-dependencies"), script);
    }
}
