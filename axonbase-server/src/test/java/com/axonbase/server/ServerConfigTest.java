package com.axonbase.server;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerConfigTest {

    @Test
    void arquivoEVariaveisDeAmbienteCompoemConfiguracao() throws Exception {
        Path file = Files.createTempFile("axonbase", ".conf");
        Files.writeString(file, "# desenvolvimento\npath = data/axon\nport = 7777\n"
            + "bind = 0.0.0.0\nrequire_auth = false\nsecret = arquivo\n");
        try {
            ServerConfig config = ServerConfig.load(file, Map.of(
                "AXON_PORT", "8888", "AXON_SECRET", "ambiente", "AXON_USER", "admin"));
            assertEquals("data/axon", config.path());
            assertEquals(8888, config.port());
            assertEquals("0.0.0.0", config.bind());
            assertEquals("ambiente", config.secret());
            assertEquals("admin", config.user());
            assertFalse(config.requireAuth());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejeitaPortaInvalida() throws Exception {
        Path file = Files.createTempFile("axonbase", ".conf");
        Files.writeString(file, "port = 70000\n");
        try {
            assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file, Map.of()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void leConfiguracaoDeClusterDoArquivoEAmbiente() throws Exception {
        Path file = Files.createTempFile("axonbase", ".conf");
        Files.writeString(file, "cluster_id = primary\nnode_id = n1\nraft_bind = 127.0.0.1:9010\n"
            + "raft_peers = 127.0.0.1:9011\n");
        try {
            ServerConfig config = ServerConfig.load(file, Map.of("AXON_NODE_ID", "n2"));
            assertEquals("primary", config.clusterId());
            assertEquals("n2", config.nodeId());
            assertEquals("127.0.0.1:9010", config.raftBind());
            assertEquals("127.0.0.1:9011", config.raftPeers());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void leAiBaseUrlDoArquivoESobrescreveComAmbiente() throws Exception {
        Path file = Files.createTempFile("axonbase", ".conf");
        Files.writeString(file, "ai_provider = ollama\nai_model = qwen3-coder:latest\n"
            + "ai_api_key = ollama\nai_base_url = http://localhost:11434\n");
        try {
            ServerConfig config = ServerConfig.load(file, Map.of(
                "AXON_AI_BASE_URL", "https://ollama.example.com"));
            assertEquals("ollama", config.aiProvider());
            assertEquals("qwen3-coder:latest", config.aiModel());
            assertEquals("ollama", config.aiApiKey());
            assertEquals("https://ollama.example.com", config.aiBaseUrl());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
