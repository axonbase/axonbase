package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NOT NULL, PRIMARY KEY e CHECK de tabela valem nas escritas novas, inclusive depois do reinício. */
@DisplayName("Restrições de tabela")
class TableConstraintsTest {

    private static final String PEOPLE = "CREATE TABLE t (codigo STRING PRIMARY KEY, nome STRING NOT NULL)";

    private final MemoryBackend backend = new MemoryBackend();
    private final Datastore ds = new Datastore(backend);

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static AxonValue run(Datastore engine, String sql) {
        return engine.execute(sql, session(), null);
    }

    private AxonValue run(String sql) {
        return run(ds, sql);
    }

    private static int count(Datastore engine, String table) {
        return run(engine, "SELECT * FROM " + table).asArray().size();
    }

    private static void refused(Datastore engine, String sql, String mentions) {
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(engine, sql), sql);
        assertTrue(error.getMessage().contains(mentions), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE t:2 SET codigo = 'Y'",
        "CREATE t:2 SET codigo = 'Y', nome = NULL",
        "INSERT INTO t {id: 2, codigo: 'Y'}",
        "UPSERT t:2 CONTENT {codigo: 'Y'}",
        "UPDATE t:1 SET nome = NULL",
        "UPDATE t:1 CONTENT {codigo: 'X'}"})
    @DisplayName("NOT NULL vale em toda escrita nova, e o que estava gravado fica")
    void notNull(String write) {
        run(PEOPLE);
        run("CREATE t:1 SET codigo = 'X', nome = 'Ana'");

        refused(ds, write, "nome");

        assertEquals(1, count(ds, "t"));
        assertEquals("Ana", run("SELECT * FROM t:1").asObject().get("nome").asString());
    }

    @Test
    @DisplayName("DEFAULT satisfaz o NOT NULL")
    void defaultSatisfiesNotNull() {
        run("CREATE TABLE t (nome STRING NOT NULL DEFAULT 'sem nome')");

        run("CREATE t:1");

        assertEquals("sem nome", run("SELECT * FROM t:1").asObject().get("nome").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE t:2 SET codigo = 'X', nome = 'Bia'",
        "CREATE t:2 SET nome = 'Bia'",
        "CREATE t:2 SET codigo = NULL, nome = 'Bia'"})
    @DisplayName("PRIMARY KEY recusa valor repetido, ausente ou nulo")
    void primaryKey(String write) {
        run(PEOPLE);
        run("CREATE t:1 SET codigo = 'X', nome = 'Ana'");

        refused(ds, write, "codigo");

        assertEquals(1, count(ds, "t"));
    }

    @Test
    @DisplayName("PRIMARY KEY acompanha UPDATE e DELETE")
    void primaryKeyFollowsWrites() {
        run(PEOPLE);
        run("CREATE t:1 SET codigo = 'X', nome = 'Ana'; CREATE t:2 SET codigo = 'Y', nome = 'Bia'");

        refused(ds, "UPDATE t:2 SET codigo = 'X'", "codigo");
        run("UPDATE t:1 SET codigo = 'Z'");
        run("UPDATE t:2 SET codigo = 'X'");
        run("UPDATE t:2 SET nome = 'Beatriz'");
        run("DELETE t:2");
        run("CREATE t:3 SET codigo = 'X', nome = 'Caio'");

        assertEquals(2, count(ds, "t"));
    }

    @Test
    @DisplayName("chave primária composta vale para a combinação")
    void compositePrimaryKey() {
        run("CREATE TABLE item (pedido INT, linha INT, PRIMARY KEY (pedido, linha))");
        run("CREATE item:1 SET pedido = 1, linha = 1; CREATE item:2 SET pedido = 1, linha = 2");

        refused(ds, "CREATE item:3 SET pedido = 1, linha = 1", "linha");

        assertEquals(2, count(ds, "item"));
    }

    @Test
    @DisplayName("PRIMARY KEY na coluna id é a própria identidade do registro")
    void primaryKeyOnId() {
        run("CREATE TABLE u (id STRING PRIMARY KEY, v INT)");

        assertDoesNotThrow(() -> run("CREATE u:1 SET v = 1; CREATE u:2 SET v = 1"));
    }

    @Test
    @DisplayName("CHECK de tabela vale em CREATE e UPDATE")
    void tableCheck() {
        run("CREATE TABLE ck (a INT, b INT, CHECK (a < b))");

        refused(ds, "CREATE ck:1 SET a = 5, b = 1", "ck");
        run("CREATE ck:2 SET a = 1, b = 5");
        refused(ds, "UPDATE ck:2 SET a = 9", "ck");
        // como o CHECK de coluna, não é avaliado quando um campo citado está ausente
        run("CREATE ck:3 SET a = 9");

        assertEquals(2, count(ds, "ck"));
    }

    @Test
    @DisplayName("as três restrições continuam valendo depois do reinício e de um ALTER TABLE")
    void constraintsSurviveRestart() {
        run("CREATE TABLE t (codigo STRING PRIMARY KEY, nome STRING NOT NULL, a INT, b INT, CHECK (a < b))");
        run("ALTER TABLE t ADD COLUMN cidade STRING NOT NULL DEFAULT 'SP'");
        run("ALTER TABLE t ADD COLUMN uf STRING NOT NULL");
        run("DEFINE TABLE t SCHEMAFULL");
        run("CREATE t:1 SET codigo = 'X', nome = 'Ana', uf = 'SP'");

        Datastore reopened = new Datastore(backend);

        refused(reopened, "CREATE t:2 SET codigo = 'X', nome = 'Bia', uf = 'SP'", "codigo");
        refused(reopened, "CREATE t:2 SET codigo = 'Y', uf = 'SP'", "nome");
        refused(reopened, "CREATE t:2 SET codigo = 'Y', nome = 'Bia'", "uf");
        refused(reopened, "CREATE t:2 SET codigo = 'Y', nome = 'Bia', uf = 'SP', a = 2, b = 1", "t");
        run(reopened, "CREATE t:2 SET codigo = 'Y', nome = 'Bia', uf = 'SP'");
        assertEquals("SP", run(reopened, "SELECT * FROM t:2").asObject().get("cidade").asString());
    }

    @Test
    @DisplayName("dados antigos não são conferidos, mas as escritas novas enxergam o valor deles")
    void oldRowsAreNotChecked() {
        run("CREATE t:1 SET codigo = 'X'; CREATE t:2 SET codigo = 'X'");

        run(PEOPLE);

        assertEquals(2, count(ds, "t"));
        refused(ds, "CREATE t:3 SET codigo = 'X', nome = 'Ana'", "codigo");
        // regravar uma linha antiga sem mudar a chave continua possível, desde que ela cumpra o resto
        run("UPDATE t:2 SET nome = 'Bia'");
    }

    @Test
    @DisplayName("base gravada pela rc1, sem as entradas da chave primária, passa a recusar o repetido ao abrir")
    void upgradedBaseEnforcesPrimaryKey() {
        run(PEOPLE);
        run("CREATE t:1 SET codigo = 'X', nome = 'Ana'");
        backend.keysWithPrefix("n\u0000d\u0000t\u0000!uidx\u0000!pk\u0000").forEach(backend::delete);
        backend.keysWithPrefix("PKB|").forEach(backend::delete);

        Datastore reopened = new Datastore(backend);

        refused(reopened, "CREATE t:2 SET codigo = 'X', nome = 'Bia'", "codigo");
    }

    @Test
    @DisplayName("DEFINE FIELD sobre a coluna da chave primária retira as entradas antigas dela")
    void defineFieldOverKeyColumnDropsItsEntries() {
        run(PEOPLE);
        run("CREATE t:1 SET codigo = 'X', nome = 'Ana'");

        run("DEFINE FIELD codigo ON TABLE t TYPE string");

        assertEquals(java.util.List.of(), backend.keysWithPrefix("n\u0000d\u0000t\u0000!uidx\u0000!pk\u0000"));
        assertEquals(java.util.List.of(), backend.keysWithPrefix("PKB|"));
    }

    @Test
    @DisplayName("apagar e recriar a tabela não herda valores da chave primária")
    void dropTableForgetsKeys() {
        run(PEOPLE);
        run("CREATE t:1 SET codigo = 'X', nome = 'Ana'; DROP TABLE t");
        run(PEOPLE);

        assertDoesNotThrow(() -> run("CREATE t:2 SET codigo = 'X', nome = 'Bia'"));
    }

    @Test
    @DisplayName("exportar e importar leva as restrições e não confere as linhas repostas")
    void exportCarriesConstraints() {
        run("CREATE t:1 SET codigo = 'X'");
        run(PEOPLE);
        run("CREATE t:2 SET codigo = 'Y', nome = 'Bia'");
        Datastore target = new Datastore(new MemoryBackend());

        target.importDatabase("n", "d", ds.exportDatabase("n", "d"));

        assertEquals(2, count(target, "t"));
        refused(target, "CREATE t:3 SET codigo = 'Y', nome = 'Caio'", "codigo");
        refused(target, "CREATE t:3 SET codigo = 'X', nome = 'Caio'", "codigo");
        refused(target, "CREATE t:3 SET codigo = 'Z'", "nome");
    }

    @Test
    @DisplayName("a compensação de saga repõe uma linha antiga que não cumpre as restrições")
    void sagaRestoresOldRow() {
        run("CREATE t:1 SET codigo = 'X'");
        run(PEOPLE);
        Session s = session();
        ds.execute("BEGIN SAGA troca WITH CORRELATION 'c1'", s, null);
        ds.execute("LET $saga_corr = 'c1'", s, null);
        ds.execute("DELETE t:1", s, null);

        ds.execute("CANCEL SAGA troca WITH CORRELATION 'c1'", s, null);

        assertEquals(1, count(ds, "t"));
    }

    @Test
    @DisplayName("dentro de uma transação o valor repetido é recusado")
    void duplicateInsideTransaction() {
        run(PEOPLE);
        Session s = session();
        ds.execute("BEGIN", s, null);
        ds.execute("CREATE t:1 SET codigo = 'X', nome = 'Ana'", s, null);

        assertThrows(RuntimeException.class,
            () -> ds.execute("CREATE t:2 SET codigo = 'X', nome = 'Bia'", s, null));
        ds.execute("CANCEL", s, null);
    }

    @Test
    @DisplayName("com RocksDB, de várias escritas simultâneas da mesma chave primária só uma é aceita")
    void concurrentPrimaryKeyOnRocksDb(@TempDir Path dir) throws Exception {
        try (RocksDbBackend rocksBackend = new RocksDbBackend(dir.resolve("rocks").toString())) {
            Datastore rocks = new Datastore(rocksBackend);
            run(rocks, PEOPLE);
            for (int round = 0; round < 50; round++) {
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger accepted = new AtomicInteger();
                Thread[] writers = new Thread[4];
                for (int i = 0; i < writers.length; i++) {
                    String sql = "CREATE t:" + (round * 10 + i) + " SET codigo = 'c" + round + "', nome = 'n'";
                    writers[i] = new Thread(() -> {
                        try {
                            start.await();
                            run(rocks, sql);
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
                assertEquals(1, accepted.get(), "rodada " + round);
            }
        }
    }
}
