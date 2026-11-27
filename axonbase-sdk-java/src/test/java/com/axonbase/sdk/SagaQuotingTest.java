package com.axonbase.sdk;

import com.axonbase.core.engine.Datastore;
import com.axonbase.server.AxonServer;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Nome de SAGA e correlação são dados: com aspa funcionam, e nunca viram comando. */
class SagaQuotingTest {

    private static final String CORRELATION = "O'Brien \\ 1";

    private AxonServer server;
    private Axon axon;

    @BeforeEach
    void setUp() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        server = AxonServer.startRandomPort(ds, "secret-testing");
        axon = Axon.connect("ws://127.0.0.1:" + server.port() + "/rpc/ws");
        axon.use("test", "dev");
        axon.query("CREATE alvo:1 SET v = 1");
    }

    @AfterEach
    void tearDown() throws Exception {
        axon.close();
        server.stop();
    }

    @Test
    void sagaComAspaEBarraCompletaOCiclo() {
        SagaTransaction saga = axon.saga("pedido d'agua", CORRELATION);

        saga.begin();
        AxonValue correlation = saga.step("RETURN $saga_corr");
        saga.describe();
        saga.commit();

        assertEquals(CORRELATION, correlation.asString());
    }

    @Test
    void nomeMaliciosoNaoViraComando() {
        SagaTransaction saga = axon.saga("z WITH CORRELATION 'k'; DELETE alvo; --", "k");
        try {
            saga.begin();
            saga.rollback();
        } catch (RuntimeException recusada) {
            // o que importa é o que o servidor executou
        }

        assertEquals(1, axon.query("SELECT * FROM alvo").asArray().size());
    }

    @Test
    void participanteAceitaCorrelacaoComAspa() {
        try (SagaParticipantTransaction participant = axon.sagaParticipant(CORRELATION)) {
            participant.begin();

            assertEquals(CORRELATION, participant.step("RETURN $saga_corr").asString());
        }
    }
}
