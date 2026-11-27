package com.axonbase.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Senha de root definida em branco é erro de configuração, nunca a senha padrão. */
class BlankRootPasswordTest {

    private static final Path SEM_ARQUIVO = Path.of("nao-existe.conf");

    @Test
    void senhaEmBrancoNoAmbienteImpedeASubida() {
        for (String branco : new String[] {"", "   "}) {
            assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.load(SEM_ARQUIVO, Map.of("AXON_ROOT_PASSWORD", branco)));
        }
    }

    @Test
    void senhaEmBrancoNoArquivoImpedeASubida(@TempDir Path dir) throws Exception {
        Path conf = Files.writeString(dir.resolve("axonbase.conf"), "root_password = \"  \"\n");

        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(conf, Map.of()));
    }

    @Test
    void senhaDoAmbienteSubstituiADoArquivo(@TempDir Path dir) throws Exception {
        Path conf = Files.writeString(dir.resolve("axonbase.conf"), "root_password = do-arquivo\n");

        assertEquals("do-ambiente",
            ServerConfig.load(conf, Map.of("AXON_ROOT_PASSWORD", "do-ambiente")).password());
    }
}
