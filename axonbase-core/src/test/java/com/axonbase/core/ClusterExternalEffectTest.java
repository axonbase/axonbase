package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.audit.AiProviderClient;
import com.axonbase.core.cluster.CommitCoordinator;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.embedding.EmbeddingClient;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.ExternalCalls;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionConflictException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Cluster: comando avulso em conflito só é refeito sem efeito externo")
class ClusterExternalEffectTest {

    private final AtomicInteger providerCalls = new AtomicInteger();
    /** Quantas confirmações ainda vão falhar por conflito antes de passar. */
    private final AtomicInteger conflicts = new AtomicInteger();
    private final AtomicInteger confirmations = new AtomicInteger();
    /** A liderança saiu entre a execução do comando e a confirmação. */
    private final AtomicBoolean moved = new AtomicBoolean();
    private HttpServer provider;
    private Datastore ds;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            providerCalls.incrementAndGet();
            byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        provider.start();
        ds = new Datastore(new MemoryBackend());
        ds.createDatabase("n", "d");
        ds.useEmbeddingClient(new EmbeddingClient(HttpClient.newHttpClient(), name -> null, millis -> { }, 0, 4));
        session = Session.create();
        session.namespace("n");
        session.database("d");
        ds.execute("DEFINE EMBEDDING e PROVIDER http URL \"http://127.0.0.1:" + provider.getAddress().getPort()
            + "/v1/embeddings\" MODEL \"m\" DIMENSION 2; DEFINE TABLE artigo SCHEMAFULL; "
            + "DEFINE FIELD corpo ON TABLE artigo TYPE string; "
            + "DEFINE FIELD vetor ON TABLE artigo TYPE vector<2> EMBEDDING e FROM corpo; "
            + "DEFINE TABLE simples", session, null);
        ds.commitCoordinator(new CommitCoordinator() {
            @Override
            public long confirm(String node, CommittedBatch batch) {
                if (moved.get()) {
                    throw new NotLeaderException("n2");
                }
                if (conflicts.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new VersionConflictException("outro confirmou antes", "k");
                }
                return confirmations.incrementAndGet();
            }
        }, "n1");
    }

    @AfterEach
    void tearDown() {
        provider.stop(0);
    }

    @Test
    @DisplayName("sem chamada para fora, o motor refaz o comando e o cliente não vê o conflito")
    void pureStatementIsRetried() {
        conflicts.set(3);
        ds.execute("CREATE simples:1 CONTENT { v: 1 }", session, null);
        assertEquals(1, confirmations.get());
        assertEquals(0, conflicts.get());
    }

    @Test
    @DisplayName("um comando que chamou fora do banco devolve o conflito e a chamada acontece uma vez só")
    void statementWithExternalCallIsNotRetried() {
        conflicts.set(1);
        assertThrows(VersionConflictException.class,
            () -> ds.execute("CREATE artigo:1 CONTENT { corpo: \"grafo\" }", session, null));
        assertEquals(1, providerCalls.get());
        assertEquals(0, confirmations.get());
        assertEquals(false, session.inTransaction());
    }

    @Test
    @DisplayName("sem chamada para fora, a perda da liderança chega como redirecionamento")
    void lostLeadershipIsRedirected() {
        moved.set(true);
        assertThrows(NotLeaderException.class,
            () -> ds.execute("CREATE simples:1 CONTENT { v: 1 }", session, null));
        assertEquals(false, session.inTransaction());
    }

    @Test
    @DisplayName("depois de uma chamada para fora, a perda da liderança não manda o cliente repetir")
    void lostLeadershipAfterExternalCallIsNotRedirected() {
        moved.set(true);
        AxonError error = assertThrows(AxonError.class,
            () -> ds.execute("CREATE artigo:1 CONTENT { corpo: \"grafo\" }", session, null));
        assertInstanceOf(NotLeaderException.class, error.getCause());
        assertEquals(1, providerCalls.get());
        assertEquals(false, session.inTransaction());
    }

    @Test
    @DisplayName("a chamada de um comando anterior na mesma thread não impede refazer o seguinte")
    void earlierExternalCallDoesNotStick() {
        ds.execute("CREATE artigo:1 CONTENT { corpo: \"grafo\" }", session, null);
        conflicts.set(2);
        ds.execute("CREATE simples:2 CONTENT { v: 2 }", session, null);
        assertEquals(2, confirmations.get());
    }

    @Test
    @DisplayName("o provedor de IA e o link de banco remoto contam como chamada externa mesmo quando falham")
    void everyOutboundClientIsCounted() {
        long before = ExternalCalls.count();
        AiProviderClient ai = new AiProviderClient("openai", "m", "k", "http://127.0.0.1:9");
        try {
            ai.generateRules("aviso", "perigo", ds.findDatabase("n", "d"));
        } catch (RuntimeException unreachable) {
            // só a tentativa importa
        }
        assertEquals(before + 1, ExternalCalls.count());
        try {
            new com.axonbase.core.engine.DatabaseLinkClient().query(new Datastore.DatabaseLinkDef("l",
                "http://127.0.0.1:9", "n", "d", "u", "p"), "SELECT * FROM t");
        } catch (RuntimeException unreachable) {
            // só a tentativa importa
        }
        assertEquals(before + 2, ExternalCalls.count());
    }
}
