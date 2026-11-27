package com.axonbase.cli;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.server.AxonServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A CLI rodada como processo: o que ela imprime e o código de saída são o contrato com quem a automatiza. */
class CliProcessTest {

    @TempDir
    Path dir;

    private Datastore ds;
    private AxonServer server;

    private record Saida(int codigo, String saida, String erros) {
    }

    @BeforeEach
    void setUp() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("loja", "ops");
        ds.createDatabase("loja", "copia");
        ds.execute("CREATE nota:1 SET texto = 'ação'", sessao("ops"), null);
        server = AxonServer.startRandomPort(ds, "secret-testing");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private static Session sessao(String db) {
        Session s = Session.create();
        s.namespace("loja");
        s.database(db);
        return s;
    }

    private String url(AxonServer alvo) {
        return "ws://127.0.0.1:" + alvo.port() + "/rpc/ws";
    }

    private Saida axon(String... args) throws Exception {
        List<String> comando = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), Main.class.getName()));
        comando.addAll(List.of(args));
        Path saida = Files.createTempFile(dir, "saida", ".txt");
        Path erros = Files.createTempFile(dir, "erros", ".txt");
        Process processo = new ProcessBuilder(comando)
            .redirectOutput(saida.toFile()).redirectError(erros.toFile()).start();
        assertTrue(processo.waitFor(90, TimeUnit.SECONDS), "a CLI não terminou");
        return new Saida(processo.exitValue(), Files.readString(saida, StandardCharsets.UTF_8),
            new String(Files.readAllBytes(erros), StandardCharsets.UTF_8));
    }

    @Test
    void exportGeraUmaCopiaQueOImportRestaura() throws Exception {
        Saida export = axon("export", url(server), "loja", "ops");

        assertEquals(0, export.codigo(), export.erros());
        assertTrue(export.saida().contains("nota"), export.saida());
        assertTrue(export.saida().contains("ação"), export.saida());

        Path dump = dir.resolve("dump.axql");
        Files.writeString(dump, export.saida());
        Saida restauro = axon("import", url(server), "loja", "copia", dump.toString());

        assertEquals(0, restauro.codigo(), restauro.erros());
        assertEquals(1, ds.execute("SELECT * FROM nota", sessao("copia"), null).asArray().size());
    }

    @Test
    void exportQueFalhaSaiComCodigoDiferenteDeZeroESemCopia() throws Exception {
        Saida export = axon("export", url(server), "loja", "nao_existe");

        assertNotEquals(0, export.codigo(), export.saida());
        assertEquals("", export.saida());
    }

    @Test
    void usuarioESenhaAutenticamASessao() throws Exception {
        AxonServer protegido = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            Saida export = axon("export", url(protegido), "loja", "ops", "--user", "root", "--password", "pw");
            Saida consulta = axon("sql", url(protegido), "SELECT * FROM nota", "--ns", "loja", "--db", "ops",
                "--user", "root", "--password", "pw");

            assertEquals(0, export.codigo(), export.erros());
            assertTrue(export.saida().contains("nota"), export.saida());
            assertEquals(0, consulta.codigo(), consulta.erros());
            assertTrue(consulta.saida().contains("nota:1"), consulta.saida());
        } finally {
            protegido.stop();
        }
    }

    @Test
    void variaveisDaLinhaDeComandoChegamAConsulta() throws Exception {
        Saida consulta = axon("sql", url(server), "RETURN $nome", "--vars", "{\"nome\":\"Ana\"}",
            "--ns", "loja", "--db", "ops");

        assertEquals(0, consulta.codigo(), consulta.erros());
        assertTrue(consulta.saida().contains("Ana"), consulta.saida());
    }
}
