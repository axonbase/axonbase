package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.ColdTier;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.storage.KvBackend;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A importação aplica o dump inteiro ou nada. */
@DisplayName("Importação tudo ou nada")
class AtomicImportTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static AxonValue run(Datastore ds, String sql) {
        return ds.execute(sql, session(), null);
    }

    private static Datastore existing(KvBackend backend) {
        Datastore ds = new Datastore(backend);
        run(ds, "DEFINE TABLE antiga SCHEMALESS; CREATE antiga:1 SET v = 1");
        return ds;
    }

    /** O registro de dados e de definições que o motor guarda, para comparar antes e depois. */
    private static List<String> stored(KvBackend backend) {
        List<String> out = new ArrayList<>();
        for (String key : backend.keysWithPrefix("")) {
            if (key.startsWith("n\u0000d\u0000") || key.startsWith("KV|") || key.startsWith("E|")
                || key.equals("__axon/control/snapshot")) {
                out.add(key + "=" + new String(backend.get(key).orElseThrow(),
                    java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
        return out;
    }

    private static final String FAILING_DUMP = String.join("\n",
        "DEFINE TABLE nova SCHEMALESS;",
        "DEFINE INDEX unico ON TABLE nova COLUMNS codigo UNIQUE;",
        "CREATE nova:1 CONTENT {codigo: 'a'};",
        "CREATE antiga:1 CONTENT {v: 2};",
        "CREATE antiga:2 CONTENT {v: 3};",
        "RELATE nova:1->liga->antiga:1;",
        "RETURN kv::set('chave', 1);",
        "CREATE nova:2 CONTENT {codigo: 'a'};",
        "CREATE nova:3 CONTENT {codigo: 'c'};",
        "");

    private static void failedImportChangesNothing(KvBackend backend) {
        Datastore ds = existing(backend);
        List<String> before = stored(backend);

        assertThrows(RuntimeException.class, () -> ds.importDatabase("n", "d", FAILING_DUMP));

        assertEquals(before, stored(backend));
        assertEquals("[{\"v\":1}]", AxonJson.write(run(ds, "SELECT v FROM antiga")));
        assertTrue(!AxonJson.write(run(ds, "INFO FOR DATABASE").asObject().get("tables")).contains("nova"));
    }

    @Test
    @DisplayName("uma sentença que falha desfaz dados, definições, arestas e chave-valor")
    void failureUndoesEverything() {
        failedImportChangesNothing(new MemoryBackend());
    }

    @Test
    @DisplayName("o mesmo com RocksDB")
    void failureUndoesEverythingOnRocksDb(@TempDir Path dir) throws Exception {
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("rocks").toString())) {
            failedImportChangesNothing(backend);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEGIN", "COMMIT", "CANCEL"})
    @DisplayName("um dump que controla a própria transação é recusado antes de escrever")
    void transactionControlIsRefused(String statement) {
        MemoryBackend backend = new MemoryBackend();
        Datastore ds = existing(backend);
        List<String> before = stored(backend);

        RuntimeException error = assertThrows(RuntimeException.class,
            () -> ds.importDatabase("n", "d", "CREATE antiga:2;\n" + statement + ";\n"));

        assertTrue(error.getMessage().contains(statement), error.getMessage());
        assertEquals(before, stored(backend));
    }

    private static Datastore limited(KvBackend backend, Path dir, long bytes) {
        return new Datastore(backend, ColdTier.of(new FileSystemColdStore(dir.resolve("cold")), dir.resolve("cache"),
            ColdTier.Settings.defaults().withClusterBytes(bytes)));
    }

    @Test
    @DisplayName("um arquivo acima do limite de transação é recusado com o limite no erro")
    void oversizedFileIsRefused(@TempDir Path dir) {
        MemoryBackend backend = new MemoryBackend();
        Datastore ds = limited(backend, dir, 2_000);
        String dump = "CREATE grande:1 CONTENT {texto: '" + "x".repeat(4_000) + "'};";

        RuntimeException error = assertThrows(RuntimeException.class, () -> ds.importDatabase("n", "d", dump));

        assertTrue(error.getMessage().contains("2000"), error.getMessage());
        assertEquals(0, backend.keysWithPrefix("n\u0000d\u0000grande\u0000").size());
    }

    @Test
    @DisplayName("um arquivo pequeno cuja transação passa do limite também é recusado")
    void oversizedTransactionIsRefused(@TempDir Path dir) {
        MemoryBackend backend = new MemoryBackend();
        Datastore ds = limited(backend, dir, 2_000);
        StringBuilder dump = new StringBuilder("DEFINE INDEX c ON TABLE t COLUMNS a, b, c UNIQUE;\n");
        for (int i = 0; i < 30; i++) {
            dump.append("CREATE t:").append(i).append(" SET a=").append(i).append(",b=1,c=1;\n");
        }
        assertTrue(dump.length() < 2_000, "o arquivo precisa caber para o teste valer: " + dump.length());

        RuntimeException error = assertThrows(RuntimeException.class,
            () -> ds.importDatabase("n", "d", dump.toString()));

        assertTrue(error.getMessage().contains("2000"), error.getMessage());
        assertEquals(0, backend.keysWithPrefix("n\u0000d\u0000t\u0000").size());
    }

    @Test
    @DisplayName("dentro do limite a importação entra inteira, com índice sobre as linhas dela")
    void importCommits(@TempDir Path dir) {
        Datastore ds = limited(new MemoryBackend(), dir, 1 << 20);

        ds.importDatabase("n", "d", "DEFINE INDEX unico ON TABLE t COLUMNS a UNIQUE;\nCREATE t:1 SET a = 1;\n");

        assertEquals(1, run(ds, "SELECT * FROM t").asArray().size());
        assertThrows(RuntimeException.class, () -> run(ds, "CREATE t:2 SET a = 1"));
    }
}
