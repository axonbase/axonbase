package com.axonbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.core.engine.Datastore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Portão 2: o gerador confere o que lê")
class RaftReadGateBenchmarkTest {

    private static final String NOTE = "x".repeat(200);
    /** A forma que o servidor devolve para um {@code SELECT} por id: o objeto, sem lista em volta. */
    private static final String ROW = "{\"n\":7,\"nota\":\"" + NOTE + "\",\"id\":\"item:i7\"}";

    private static RaftReadGateBenchmark.Answer answer(String body) {
        return RaftReadGateBenchmark.answer(body, 7);
    }

    @Test
    @DisplayName("só a linha pedida, com o conteúdo gravado, conta como leitura")
    void onlyTheRequestedRowCounts() {
        assertEquals(RaftReadGateBenchmark.Answer.ROW, answer(ROW));
        assertEquals(RaftReadGateBenchmark.Answer.ROW, answer("[" + ROW + "]"));
        assertEquals(RaftReadGateBenchmark.Answer.EMPTY, answer("[]"));
        assertEquals(RaftReadGateBenchmark.Answer.WRONG, answer("[" + ROW + "," + ROW + "]"));
        assertEquals(RaftReadGateBenchmark.Answer.WRONG, answer("null"));
        assertEquals(RaftReadGateBenchmark.Answer.WRONG, answer(ROW.replace("\"n\":7", "\"n\":71")));
        assertEquals(RaftReadGateBenchmark.Answer.WRONG, answer(ROW.replace(NOTE, "x")));
        assertEquals(RaftReadGateBenchmark.Answer.WRONG, answer("{\"status\":\"ERR\",\"detail\":\"falhou\"}"));
        assertEquals(RaftReadGateBenchmark.Answer.WRONG, answer("não é JSON"));
    }

    @Test
    @DisplayName("contra um nó real: carga conferida, leitura sem erros e falta de linhas reprovada")
    void checksRowsAgainstARealNode() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        AxonServer server = AxonServer.start(ds, java.util.UUID.randomUUID().toString(), ServerConfig.load(null,
            Map.of("AXON_BIND", "127.0.0.1", "AXON_PORT", "0", "AXON_REQUIRE_AUTH", "false")));
        try {
            List<String> urls = List.of("http://127.0.0.1:" + server.port());
            RaftReadGateBenchmark.load(urls, 50);

            RaftReadGateBenchmark.Measure good = RaftReadGateBenchmark.read(urls, 50, 4, 2, 0, 1, null);
            assertEquals(0, good.errors());
            assertTrue(good.opsPerSecond() > 0);

            RaftReadGateBenchmark.Measure missing = RaftReadGateBenchmark.read(urls, 5_000, 4, 2, 0, 1, null);
            assertTrue(missing.empty() > 0, missing.toString());

            Duration briefly = Duration.ofSeconds(1);
            assertThrows(IllegalStateException.class, () -> RaftReadGateBenchmark.awaitRows(urls, 51, briefly));
            assertThrows(IllegalStateException.class, () -> RaftReadGateBenchmark.awaitRows(urls, 49, briefly));
        } finally {
            server.stop();
        }
    }
}
