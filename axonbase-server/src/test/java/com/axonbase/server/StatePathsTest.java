package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O estado do consenso e os truststores ficam no volume dos dados, sem perder o que já estava ao lado dele. */
class StatePathsTest {

    @TempDir
    Path temp;

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void instalacaoNovaGravaOEstadoDentroDoDiretorioDeDados() {
        Path data = temp.resolve("data");

        assertEquals(data.resolve(".raft"), StatePaths.raftDir(data.toString()));
        assertEquals(data.resolve(".truststores"), StatePaths.truststoreDir(data.toString()));
        assertEquals(data.resolve(".raft"), StatePaths.raftDir("rocksdb:" + data));
        assertEquals(data.resolve(".truststores"), StatePaths.truststoreDir("rocksdb:" + data));
    }

    @Test
    void estadoDoRaftNoLocalAntigoContinuaEmUsoSemSerMovido() throws Exception {
        Path data = temp.resolve("data");
        Path old = temp.resolve("data.raft");
        write(old.resolve("raft-log").resolve("base"), "estado");

        assertEquals(old, StatePaths.raftDir(data.toString()));
        assertTrue(Files.exists(old.resolve("raft-log").resolve("base")));
        assertFalse(Files.exists(data.resolve(".raft")));
    }

    @Test
    void diretorioAntigoSemArquivosNaoConta() throws Exception {
        Path data = temp.resolve("data");
        Files.createDirectories(temp.resolve("data.raft").resolve("lost+found"));

        assertEquals(data.resolve(".raft"), StatePaths.raftDir(data.toString()));
    }

    @Test
    void estadoNosDoisLocaisRecusaASubidaEmVezDeEscolher() throws Exception {
        Path data = temp.resolve("data");
        write(temp.resolve("data.raft").resolve("meta"), "antigo");
        write(data.resolve(".raft").resolve("meta"), "novo");

        IllegalStateException refused =
            assertThrows(IllegalStateException.class, () -> StatePaths.raftDir(data.toString()));

        assertTrue(refused.getMessage().contains(temp.resolve("data.raft").toString()), refused.getMessage());
        assertTrue(refused.getMessage().contains(data.resolve(".raft").toString()), refused.getMessage());
    }

    @Test
    void barraFinalNoCaminhoNaoViraConflito() throws Exception {
        Path data = temp.resolve("data");
        write(data.resolve(".raft").resolve("meta"), "estado");

        assertEquals(data.resolve(".raft"), StatePaths.raftDir(data + "/"));
    }

    @Test
    void baseEmMemoriaNaoMudaDeLugar() {
        assertEquals(Path.of("memory.raft"), StatePaths.raftDir("memory"));
    }

    @Test
    void truststoreDoLocalAntigoEhCopiadoParaOVolumeSemApagarNemSobrescrever() throws Exception {
        Path data = temp.resolve("data");
        Path old = temp.resolve("data.truststores");
        write(old.resolve("a.jks"), "a-antigo");
        write(old.resolve("b.jks"), "b-antigo");
        write(data.resolve(".truststores").resolve("b.jks"), "b-novo");

        StatePaths.adoptTruststores(data.toString());

        assertEquals("a-antigo", Files.readString(data.resolve(".truststores").resolve("a.jks")));
        assertEquals("b-novo", Files.readString(data.resolve(".truststores").resolve("b.jks")));
        assertTrue(Files.exists(old.resolve("a.jks")));
    }

    @Test
    void caminhoGravadoQueSumiuEhEncontradoPeloNomeDoArquivo() throws Exception {
        Path data = temp.resolve("data");
        String gone = temp.resolve("data.truststores").resolve("a.jks").toString();
        write(data.resolve(".truststores").resolve("a.jks"), "a");

        assertEquals(data.resolve(".truststores").resolve("a.jks").toString(),
            StatePaths.locateTruststore(data.toString(), gone));
    }

    @Test
    void caminhoGravadoQueExisteEhMantido() throws Exception {
        Path data = temp.resolve("data");
        Path elsewhere = temp.resolve("outro").resolve("a.jks");
        write(elsewhere, "do-operador");
        write(data.resolve(".truststores").resolve("a.jks"), "copia");

        assertEquals(elsewhere.toString(), StatePaths.locateTruststore(data.toString(), elsewhere.toString()));
    }

    @Test
    void avisaQuandoALinhaDeComandoVenceAVariavelAxonPath() {
        assertTrue(StatePaths.envPathIgnored("/data/db", "/data"));
        assertFalse(StatePaths.envPathIgnored("/data", "/data"));
        assertFalse(StatePaths.envPathIgnored(null, "/data"));
        assertFalse(StatePaths.envPathIgnored("  ", "/data"));
    }

    @Test
    void baseExistenteSemEstadoDoRaftEhDetectada() throws Exception {
        Path data = temp.resolve("data");
        assertFalse(StatePaths.dataWithoutRaftState(data.toString()));

        write(data.resolve("CURRENT"), "MANIFEST-1");
        assertTrue(StatePaths.dataWithoutRaftState(data.toString()));

        write(data.resolve(".raft").resolve("meta"), "estado");
        assertFalse(StatePaths.dataWithoutRaftState(data.toString()));
    }

    @Test
    void servidorComBaseEmDiscoGuardaTruststoresDentroDela() throws Exception {
        Path data = temp.resolve("data");
        ServerConfig config = ServerConfig.load(temp.resolve("nao-existe.conf"), Map.of(
            "AXON_PATH", data.toString(), "AXON_PORT", "0", "AXON_BIND", "127.0.0.1",
            "AXON_REQUIRE_AUTH", "false"));
        AxonServer server = AxonServer.start(new Datastore(new MemoryBackend()), "secret-testing", config);
        try {
            assertEquals(data.resolve(".truststores"), server.truststoreDir());
        } finally {
            server.stop();
        }
    }
}
