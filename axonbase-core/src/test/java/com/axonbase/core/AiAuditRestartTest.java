package com.axonbase.core;

import com.axonbase.core.audit.AiProviderClient;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("AI AUDIT: o servidor volta a subir e a auditoria persiste")
class AiAuditRestartTest {

    private static final String CREATE = "CREATE AI AUDIT pa SET WARNING WHEN 'a' SET DANGER WHEN 'b'";

    private final MemoryBackend backend = new MemoryBackend();

    private static void run(Datastore ds, String sql) {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        ds.execute(sql, s, null);
    }

    private static com.axonbase.core.audit.AiAuditDef audit(Datastore ds) {
        return ds.findDatabase("n", "d").catalog().audit("pa");
    }

    private void storedByEarlierVersion(String definition) {
        Datastore ds = new Datastore(backend);
        ds.createDatabase("n", "d");
        ds.applyControl(ControlCommand.of(ControlCommand.Kind.AUDIT, "n", "d", "pa", definition));
    }

    @Test
    void baseComAuditoriaGravadaPorVersaoAnteriorVoltaAAbrir() {
        storedByEarlierVersion(CREATE);

        Datastore reopened = assertDoesNotThrow(() -> new Datastore(backend));

        assertNotNull(audit(reopened));
    }

    @Test
    void baseComRemocaoGravadaPorVersaoAnteriorVoltaAAbrir() {
        storedByEarlierVersion("DROP AI AUDIT pa");

        Datastore reopened = assertDoesNotThrow(() -> new Datastore(backend));

        assertNull(audit(reopened));
    }

    @Test
    void regrasGeradasSobrevivemAoReinicioSemProvedor() throws IOException {
        HttpServer provider = fakeProvider();
        try {
            Datastore first = new Datastore(backend);
            first.aiProvider(new AiProviderClient("ollama", "modelo", "chave-de-teste",
                "http://127.0.0.1:" + provider.getAddress().getPort()));
            run(first, CREATE);
        } finally {
            provider.stop(0);
        }

        Datastore reopened = new Datastore(backend);

        assertEquals(1, audit(reopened).dangerRules().size());
        run(reopened, "DROP AI AUDIT pa");
        assertNull(audit(new Datastore(backend)));
    }

    private static HttpServer fakeProvider() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"response\": \"{\\\"warning\\\":[],\\\"danger\\\":"
                + "[{\\\"type\\\":\\\"keyword\\\",\\\"words\\\":[\\\"DROP\\\"]}]}\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
