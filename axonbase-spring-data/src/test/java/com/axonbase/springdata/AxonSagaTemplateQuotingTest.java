package com.axonbase.springdata;

import com.axonbase.core.engine.Datastore;
import com.axonbase.sdk.Axon;
import com.axonbase.server.AxonServer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Nome de SAGA e correlação com aspa chegam inteiros ao servidor. */
class AxonSagaTemplateQuotingTest {

    @Test
    @SuppressWarnings("deprecation")
    void entraEmSagaComAspaNoNomeENaCorrelacao() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try (Axon axon = Axon.connect("ws://127.0.0.1:" + server.port() + "/rpc/ws")) {
            axon.use("test", "dev");
            axon.query("BEGIN SAGA 'pedido d\\'agua' WITH CORRELATION 'O\\'Brien'");
            AxonSagaTemplate template = new AxonSagaTemplate(axon, new NoOpTransactionManager());

            String result = template.execute("pedido d'agua", "O'Brien",
                status -> axon.query("RETURN $saga_corr").asString());

            assertEquals("O'Brien", result);
        } finally {
            server.stop();
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
