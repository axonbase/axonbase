package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No RocksDB, o CREATE de uma tabela sem nada a conferir deixa a procura do id para o commit. O contador
 * {@link RocksDbBackend#occupiedKeyRefusals()} diz qual caminho recusou: só o atalho o incrementa.
 */
@DisplayName("CREATE com a ausência do id conferida no commit")
class CreateAbsenceAtCommitTest {

    /** {@code at} no futuro: a tabela com TTL não vence a linha durante o teste. */
    private static final String FIRST = "CREATE p:ana CONTENT {nome: 'Ana', at: d\"2099-01-01T10:00:00Z\"}";
    private static final String SECOND = "CREATE p:ana CONTENT {nome: 'Outra', at: d\"2099-01-01T10:00:00Z\"}";

    @TempDir
    Path dir;

    private RocksDbBackend backend;
    private Datastore ds;

    @BeforeEach
    void open() {
        backend = new RocksDbBackend(dir.resolve("db").toString());
        ds = new Datastore(backend);
    }

    @AfterEach
    void close() {
        backend.close();
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session(), null);
    }

    private static String name(Datastore target) {
        return target.execute("SELECT * FROM p:ana", session(), null).asObject().get("nome").asString();
    }

    private String refusal(String sql, Session s) {
        return assertThrows(RuntimeException.class, () -> ds.execute(sql, s, null)).getMessage();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE p:ana SET nome = 'Outra'",
        "CREATE p CONTENT {id: 'ana', nome: 'Outra'}",
        "CREATE ONLY p:ana SET nome = 'Outra'",
        "CREATE p:ana SET nome = 'Outra' RETURN NONE"})
    @DisplayName("o duplicado é recusado pelo commit, com a mesma mensagem do caminho completo")
    void duplicateIsRefusedLikeBefore(String duplicate) {
        run("CREATE p:ana SET nome = 'Ana'");
        assertEquals(0, backend.occupiedKeyRefusals());
        Session inTransaction = session();
        ds.execute("BEGIN", inTransaction, null);
        String fullPath = refusal(duplicate, inTransaction);
        ds.execute("CANCEL", inTransaction, null);
        assertEquals(0, backend.occupiedKeyRefusals(), "a transação procura o registro antes de escrever");

        String shortcut = refusal(duplicate, session());

        assertEquals(fullPath, shortcut);
        assertTrue(shortcut.contains("p:ana"), shortcut);
        assertEquals(1, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("tabela definida sem cláusulas também usa o atalho")
    void plainDefinedTableTakesTheShortcut() {
        run("DEFINE TABLE p SCHEMALESS");
        run(FIRST);

        refusal(SECOND, session());

        assertEquals(1, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("depois do DELETE o mesmo id é criado de novo")
    void recreatesAfterDelete() {
        run(FIRST);
        run("DELETE p:ana");

        run(SECOND);

        assertEquals("Outra", name(ds));
        assertEquals(0, backend.occupiedKeyRefusals());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DEFINE INDEX i ON TABLE p COLUMNS nome UNIQUE",
        "DEFINE FIELD nome ON TABLE p TYPE string",
        "CREATE TABLE p (nome STRING PRIMARY KEY)",
        "CREATE TABLE p (nome STRING, CHECK (nome != ''))",
        "DEFINE TABLE p SCHEMALESS; DEFINE EVENT e ON TABLE p WHEN true THEN (CREATE trilha CONTENT {ok: true})",
        "DEFINE TABLE p SCHEMALESS PERMISSIONS FOR create WHERE true",
        "DEFINE PARTITION ON TABLE p FIELD at INTERVAL 1d",
        "DEFINE TTL ON TABLE p FIELD at"})
    @DisplayName("tabela com algo a conferir continua no caminho completo")
    void constrainedTablesKeepTheFullPath(String definition) {
        run(definition);
        run(FIRST);

        String message = refusal(SECOND, session());

        assertTrue(message.contains("p:ana"), message);
        assertEquals(0, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("INSERT e CREATE dentro de evento continuam no caminho completo")
    void otherStatementsKeepTheFullPath() {
        run(FIRST);
        run("DEFINE TABLE origem SCHEMALESS; "
            + "DEFINE EVENT copia ON TABLE origem WHEN true THEN (CREATE p:ana SET nome = 'Evento')");

        refusal("INSERT INTO p {id: 'ana', nome: 'Outra'}", session());
        refusal("CREATE origem:1", session());

        assertEquals(0, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
        assertEquals(0, run("SELECT * FROM origem").asArray().size());
    }

    @Test
    @DisplayName("com LIVE na tabela o caminho é o completo")
    void liveQueryKeepsTheFullPath() {
        run(FIRST);
        run("LIVE SELECT * FROM p");

        refusal(SECOND, session());

        assertEquals(0, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("com auditoria ligada o caminho é o completo")
    void auditKeepsTheFullPath() {
        ds.auditConfig(true, "auth", false, 65536);
        run(FIRST);

        refusal(SECOND, session());

        assertEquals(0, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("numa SAGA o caminho é o completo")
    void sagaKeepsTheFullPath() {
        run(FIRST);
        Session s = session();
        ds.execute("BEGIN SAGA troca WITH CORRELATION 'c1'", s, null);
        ds.execute("LET $saga_corr = 'c1'", s, null);

        refusal(SECOND, s);

        assertEquals(0, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("com Data Rules o caminho é o completo")
    void dataRulesKeepTheFullPath() {
        run(FIRST);
        run("CREATE DATA RULE meus APPLY nome = 'Ana'");
        Session restricted = session();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("v"));
        claims.put("scope", AxonValue.str("NAMESPACE"));
        claims.put("ns", AxonValue.str("n"));
        claims.put("roles", AxonValue.str("editor"));
        claims.put("data_rules", AxonValue.str("meus"));
        restricted.auth(AxonValue.object(claims));

        // o registro novo passa pela regra: a recusa só pode vir do id que já existe
        String message = refusal("CREATE p:ana SET nome = 'Ana', extra = 1", restricted);

        assertTrue(message.contains("p:ana"), message);
        assertEquals(0, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("base restaurada de backup recusa o id que veio no backup")
    void restoredBaseRefusesRestoredIds() throws Exception {
        run(FIRST);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ds.backupTo(out, 0);
        Path file = dir.resolve("full.axbak");
        Files.write(file, out.toByteArray());

        try (RocksDbBackend other = new RocksDbBackend(dir.resolve("restored").toString())) {
            Datastore target = new Datastore(other);
            target.restoreFile(file);

            assertThrows(RuntimeException.class, () -> target.execute(SECOND, session(), null));

            assertEquals(1, other.occupiedKeyRefusals());
            assertEquals("Ana", name(target));
        }
    }

    @Test
    @DisplayName("base reaberta recusa o id gravado antes")
    void reopenedBaseRefusesStoredIds() {
        run(FIRST);
        backend.close();
        open();

        refusal(SECOND, session());

        assertEquals(1, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("a importação continua sobrescrevendo, e depois dela o CREATE continua recusando")
    void importStillRestores() {
        run(FIRST);
        String dump = ds.exportDatabase("n", "d");
        run("UPDATE p:ana SET nome = 'Mudada'");

        ds.importDatabase("n", "d", dump);

        assertEquals("Ana", name(ds));
        assertEquals(0, backend.occupiedKeyRefusals());
        refusal(SECOND, session());
        assertEquals(1, backend.occupiedKeyRefusals());
        assertEquals("Ana", name(ds));
    }

    @Test
    @DisplayName("de várias criações simultâneas do mesmo id só uma é aceita, e o registro é o dela")
    void concurrentDuplicatesCreateOnce() throws Exception {
        run("DEFINE TABLE p SCHEMALESS");
        for (int round = 0; round < 200; round++) {
            String id = "p:" + round;
            Queue<Integer> accepted = new ConcurrentLinkedQueue<>();
            Queue<String> refused = new ConcurrentLinkedQueue<>();

            race(8, writer -> {
                try {
                    run("CREATE " + id + " SET n = " + writer);
                    accepted.add(writer);
                } catch (RuntimeException e) {
                    refused.add(String.valueOf(e.getMessage()));
                }
            });

            assertEquals(1, accepted.size(), id);
            assertEquals(7, refused.size(), id);
            assertTrue(refused.stream().allMatch(message -> message.contains(id)), refused.toString());
            assertEquals(accepted.element().longValue(),
                run("SELECT * FROM " + id).asObject().get("n").asLong(), id);
        }
    }

    @RepeatedTest(10)
    @DisplayName("atalho e transações disputando o mesmo id criam uma vez só")
    void shortcutAndTransactionsCreateOnce() throws Exception {
        run("DEFINE TABLE p SCHEMALESS");
        for (int round = 0; round < 200; round++) {
            String id = "p:" + round;
            Queue<Integer> accepted = new ConcurrentLinkedQueue<>();

            race(8, writer -> {
                Session s = session();
                boolean transaction = writer % 2 == 0;
                try {
                    if (transaction) {
                        ds.execute("BEGIN", s, null);
                    }
                    ds.execute("CREATE " + id + " SET n = " + writer, s, null);
                    if (transaction) {
                        ds.execute("COMMIT", s, null);
                    }
                    accepted.add(writer);
                } catch (RuntimeException refused) {
                    if (s.inTransaction()) {
                        ds.execute("CANCEL", s, null);
                    }
                }
            });

            assertEquals(1, accepted.size(), id);
            assertEquals(accepted.element().longValue(),
                run("SELECT * FROM " + id).asObject().get("n").asLong(), id);
        }
    }

    private interface Writer {
        void write(int index);
    }

    private static void race(int writers, Writer work) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    work.write(index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
    }
}
