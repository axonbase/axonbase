package com.axonbase.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arquivos de instalação do repositório, lidos como texto: o que um usuário copia precisa apontar para esta versão. */
class InstallFilesTest {

    private static final Path ROOT = Path.of("..");

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file));
    }

    private static String version() throws IOException {
        return read("VERSION").trim();
    }

    private static List<String> lines(String file, String prefix) throws IOException {
        return read(file).lines().map(String::trim).filter(line -> line.startsWith(prefix)).toList();
    }

    @Test
    void composeDoClusterFixaAVersaoDoRepositorio() throws IOException {
        List<String> images = lines("docker-compose.cluster.yml", "image:");

        assertFalse(images.isEmpty());
        for (String image : images) {
            assertEquals("image: axonbase/axonbase:${AXONBASE_IMAGE_TAG:-" + version() + "}", image);
        }
    }

    @Test
    void exemploDoReadmeNaoUsaLatest() throws IOException {
        assertFalse(read("README.md").contains("axonbase/axonbase:latest"));
        assertTrue(read("README.md").contains("axonbase/axonbase:" + version()));
    }

    @Test
    void portasDoClusterSemAutenticacaoSaoPublicadasSoNoEnderecoLocal() throws IOException {
        List<String> ports = lines("docker-compose.cluster.yml", "ports:");

        assertEquals(3, ports.size());
        for (String port : ports) {
            assertTrue(port.startsWith("ports: [\"${AXON_PUBLISH_ADDRESS:-127.0.0.1}:1800"), port);
        }
    }

    @Test
    void autenticacaoDoClusterPodeSerLigadaPorVariaveis() throws IOException {
        String compose = read("docker-compose.cluster.yml");

        assertTrue(compose.contains("AXON_REQUIRE_AUTH: \"${AXON_REQUIRE_AUTH:-false}\""));
        assertTrue(compose.contains("AXON_ROOT_PASSWORD: \"${AXON_ROOT_PASSWORD:-}\""));
        assertTrue(compose.contains("AXON_SECRET: \"${AXON_SECRET:-}\""));
        // o teste do cluster chama as rotas sem token, então não pode herdar a autenticação do ambiente
        assertTrue(read("tests/cluster/cluster.sh").contains("export AXON_REQUIRE_AUTH=false"));
    }

    @Test
    void conferenciaDeVersoesIncluiOComposeEOReadme() throws IOException {
        String script = read("scripts/check-versions.sh");

        assertTrue(script.contains("require_version docker-compose.cluster.yml"));
        assertTrue(script.contains("require_version README.md"));
    }
}
