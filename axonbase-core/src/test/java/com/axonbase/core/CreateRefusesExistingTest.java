package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CREATE e INSERT recusam um registro existente, INSERT IGNORE o pula e UPSERT continua sobrescrevendo. */
@DisplayName("Criação sobre registro existente")
class CreateRefusesExistingTest {

    private final Datastore ds = new Datastore(new MemoryBackend());

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session(), null);
    }

    private String rows(String table) {
        return AxonJson.write(run("SELECT nome FROM " + table + " ORDER BY nome"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE p:ana SET cidade = 'SP'",
        "CREATE p CONTENT {id: 'ana', cidade: 'SP'}",
        "INSERT INTO p {id: 'ana', cidade: 'SP'}",
        "INSERT INTO p [{id: 'bia'}, {id: 'ana', cidade: 'SP'}]"})
    @DisplayName("CREATE e INSERT recusam o id que já existe, com erro que o cita")
    void createAndInsertRefuse(String write) {
        run("CREATE p:ana SET nome = 'Ana'");

        RuntimeException error = assertThrows(RuntimeException.class, () -> run(write));

        assertTrue(error.getMessage().contains("p:ana"), error.getMessage());
        assertEquals("Ana", run("SELECT * FROM p:ana").asObject().get("nome").asString());
    }

    @Test
    @DisplayName("INSERT IGNORE pula o existente e grava os demais")
    void insertIgnoreSkips() {
        run("CREATE p:ana SET nome = 'Ana'");

        AxonValue written = run("INSERT IGNORE INTO p [{id: 'ana', nome: 'Outra'}, {id: 'bia', nome: 'Bia'}]");

        assertEquals(1, written.asArray().size());
        assertEquals("[{\"nome\":\"Ana\"},{\"nome\":\"Bia\"}]", rows("p"));
    }

    @Test
    @DisplayName("UPSERT continua sobrescrevendo e criando")
    void upsertStillWrites() {
        run("UPSERT p:ana CONTENT {nome: 'Ana'}");
        run("UPSERT p:ana CONTENT {nome: 'Bia'}");

        assertEquals("[{\"nome\":\"Bia\"}]", rows("p"));
    }

    @Test
    @DisplayName("UPSERT cujo WHERE não casa regrava o registro sem deixar entradas antigas nos índices")
    void upsertReplacementKeepsIndexesRight() {
        run("DEFINE INDEX email ON TABLE p COLUMNS email UNIQUE");
        run("CREATE p:1 SET email = 'a@x', nome = 'A'");

        run("UPSERT p:1 CONTENT {email: 'b@x', nome: 'B'} WHERE nome = 'ninguem'");

        // o e-mail antigo ficou livre
        run("CREATE p:2 SET email = 'a@x', nome = 'C'");
        assertEquals("[{\"nome\":\"B\"},{\"nome\":\"C\"}]", rows("p"));
    }

    @Test
    @DisplayName("dentro de uma transação o registro criado nela também é recusado")
    void refusedInsideTransaction() {
        Session s = session();
        ds.execute("BEGIN", s, null);
        ds.execute("CREATE p:1", s, null);

        assertThrows(RuntimeException.class, () -> ds.execute("CREATE p:1", s, null));
        ds.execute("CANCEL", s, null);
    }

    @Test
    @DisplayName("importar sobre um banco que já tem os registros continua restaurando")
    void importStillOverwrites() {
        run("DEFINE INDEX email ON TABLE p COLUMNS email UNIQUE");
        run("CREATE p:1 SET email = 'a@x', nome = 'A'");
        String dump = ds.exportDatabase("n", "d");
        run("UPDATE p:1 SET email = 'b@x', nome = 'B'");

        ds.importDatabase("n", "d", dump);

        assertEquals("[{\"nome\":\"A\"}]", rows("p"));
        // a entrada do valor substituído saiu do índice
        run("CREATE p:2 SET email = 'b@x', nome = 'C'");
    }

    @Test
    @DisplayName("a saga continua restaurando o registro apagado e o alterado")
    void sagaCompensationStillRestores() {
        run("CREATE p:1 SET nome = 'A'; CREATE p:2 SET nome = 'B'");
        Session s = session();
        ds.execute("BEGIN SAGA troca WITH CORRELATION 'c1'", s, null);
        ds.execute("LET $saga_corr = 'c1'", s, null);
        ds.execute("UPDATE p:1 SET nome = 'X'", s, null);
        ds.execute("DELETE p:2", s, null);

        ds.execute("CANCEL SAGA troca WITH CORRELATION 'c1'", s, null);

        assertEquals("[{\"nome\":\"A\"},{\"nome\":\"B\"}]", rows("p"));
    }

    @Test
    @DisplayName("com RocksDB, de várias criações simultâneas do mesmo id só uma é aceita")
    void concurrentCreatesOnRocksDb(@TempDir Path dir) throws Exception {
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("rocks").toString())) {
            Datastore rocks = new Datastore(backend);
            rocks.execute("DEFINE TABLE p SCHEMALESS", session(), null);
            for (int round = 0; round < 50; round++) {
                String sql = "CREATE p:" + round + " SET n = 1";
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger accepted = new AtomicInteger();
                Thread[] writers = new Thread[4];
                for (int i = 0; i < writers.length; i++) {
                    writers[i] = new Thread(() -> {
                        try {
                            start.await();
                            rocks.execute(sql, session(), null);
                            accepted.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException refused) {
                            // recusado: é o esperado para todos menos um
                        }
                    });
                    writers[i].start();
                }
                start.countDown();
                for (Thread writer : writers) {
                    writer.join();
                }
                assertEquals(1, accepted.get(), sql);
            }
        }
    }
}
