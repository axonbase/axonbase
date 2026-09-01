package com.axonbase.sdk;

import com.axonbase.core.engine.Datastore;
import com.axonbase.server.AxonServer;
import com.axonbase.value.AxonValue;
import com.axonbase.value.AxonJson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SDK: Axon sobre WebSocket end-to-end")
class SdkEndToEndTest {

    @Test
    void startsCertificateChallengeOverWebSocket() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "sdk-secret");
        try (Axon axon = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws")) {
            axon.use("test", "dev");
            Axon.CertificateChallenge challenge = axon.certificateBegin("test_clients");
            assertNotNull(challenge.id());
            assertNotNull(challenge.challenge());
            assertNotNull(challenge.expiresAt());
        } finally {
            srv.stop();
        }
    }

    @Test
    void criaEseleccionaPorWebSocket() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "sdk-secret");
        try {
            Axon axon = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws");
            try {
                axon.use("test", "dev");

                AxonValue created = axon.create("person", AxonJson.parseDocument(
                    "{\"name\":\"Ana\",\"age\":30}"));
                assertTrue(created.isArray());
                assertEquals(1, created.asArray().size());

                AxonValue rows = axon.select("SELECT * FROM person");
                assertTrue(rows.isArray());
                assertEquals(1, rows.asArray().size());
                assertEquals("Ana", rows.asArray().get(0).asObject().get("name").asString());
            } finally {
                axon.close();
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void updateViaWebSocket() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "sdk-secret");
        try {
            Axon axon = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws");
            try {
                axon.use("test", "dev");
                axon.create("person", AxonJson.parseDocument("{\"name\":\"Ana\",\"age\":30}"));
                axon.update("person", "age = 31", "name = \"Ana\"");
                AxonValue rows = axon.select("SELECT * FROM person");
                assertEquals(31, rows.asArray().get(0).asObject().get("age").asLong());
            } finally {
                axon.close();
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void signinRequeridoENegado() throws Exception {
        Datastore ds = Datastore.memory();
        // servidor con auth obrigatoria; root/root rexistrado
        AxonServer srv = AxonServer.startRandomPort(ds, "sdk-secret", "root", "root", true);
        try {
            Axon axon = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws");
            try {
                axon.use("test", "dev");
                // sen signin, a query falla
                assertThrows(AxonSdkException.class, () -> axon.select("SELECT * FROM person"));
                // signin correcto e query passa
                String jwt = axon.signin("root", "root");
                assertTrue(jwt != null && !jwt.isBlank());
                axon.authenticate(jwt);
                AxonValue rows = axon.select("SELECT * FROM person");
                assertTrue(rows.isArray());
                assertEquals(0, rows.asArray().size());
            } finally {
                axon.close();
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void transactionWebSocketBeginCommit() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "sdk-secret");
        try {
            Axon axon = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws");
            try {
                axon.use("test", "dev");
                axon.query("BEGIN");
                axon.create("person", AxonJson.parseDocument("{\"name\":\"Ana\"}"));
                // outra conexión non ve os cambios non commiteados
                Axon autre = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws");
                try {
                    autre.use("test", "dev");
                    AxonValue rows = autre.select("SELECT * FROM person");
                    assertEquals(0, rows.asArray().size());
                } finally {
                    autre.close();
                }
                axon.query("COMMIT");
                AxonValue rows2 = axon.select("SELECT * FROM person");
                assertEquals(1, rows2.asArray().size());
            } finally {
                axon.close();
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    @DisplayName("Live query recebe as mudanças feitas por outra conexão")
    void liveQueryPorWebSocket() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "sdk-secret");
        try {
            Axon observer = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws");
            Axon writer = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws");
            try {
                observer.use("test", "dev");
                writer.use("test", "dev");

                java.util.concurrent.BlockingQueue<String> got =
                    new java.util.concurrent.LinkedBlockingQueue<>();
                String liveId = observer.live("person",
                    (id, action, result) -> got.add(action + ":"
                        + result.asObject().get("name").asString()));
                assertTrue(liveId != null && !liveId.isBlank());

                writer.create("person", AxonJson.parseDocument("{\"name\":\"Ana\"}"));
                assertEquals("CREATE:Ana", got.poll(5, java.util.concurrent.TimeUnit.SECONDS));

                assertTrue(observer.kill(liveId));
                writer.create("person", AxonJson.parseDocument("{\"name\":\"Bob\"}"));
                assertEquals(null, got.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally {
                writer.close();
                observer.close();
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    @DisplayName("Fechar a conexão cancela as live queries da sessão")
    void liveQueryLimpaNoFecho() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer srv = AxonServer.startRandomPort(ds, "sdk-secret");
        try {
            Axon observer = Axon.connect("ws://127.0.0.1:" + srv.port() + "/rpc/ws");
            observer.use("test", "dev");
            observer.live("person", (id, action, result) -> { });
            assertEquals(1, ds.liveBus().size());
            observer.close();

            long limite = System.currentTimeMillis() + 5000;
            while (ds.liveBus().size() > 0 && System.currentTimeMillis() < limite) {
                Thread.sleep(50);
            }
            assertEquals(0, ds.liveBus().size());
        } finally {
            srv.stop();
        }
    }
}
