package com.axonbase.sdk;

import com.axonbase.core.engine.Datastore;
import com.axonbase.server.AxonServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Uma resposta maior que o limite padrão do WebSocket (64 KiB) chega inteira ao cliente. */
class SdkLargeResponseTest {

    @Test
    void recebeResultadoMaiorQue64KiB() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try (Axon axon = Axon.connect("ws://127.0.0.1:" + server.port() + "/rpc/ws")) {
            axon.use("test", "dev");
            String texto = "x".repeat(1000);
            for (int i = 0; i < 100; i++) {
                axon.query("CREATE grande:" + i + " SET v = '" + texto + "'");
            }

            assertEquals(100, axon.query("SELECT * FROM grande").asArray().size());
        } finally {
            server.stop();
        }
    }
}
