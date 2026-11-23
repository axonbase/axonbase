package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.value.AxonValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressões de correção e desempenho do Executor. Cada teste afirma o comportamento
 * desejado; uma falha indica que o defeito voltou.
 */
@DisplayName("Executor: regressões de correção e desempenho")
class ExecutorRegressionTest {

    /** Backend que delega ao MemoryBackend, conta varreduras e pode falhar em puts. */
    private static final class ProbeBackend implements VersionedKvBackend {
        private final MemoryBackend delegate = new MemoryBackend();
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger scans = new AtomicInteger();
        volatile Predicate<String> failPut = key -> false;

        @Override public Optional<byte[]> get(String key) { gets.incrementAndGet(); return delegate.get(key); }
        @Override public void put(String key, byte[] value) {
            if (failPut.test(key)) {
                throw new IllegalStateException("falha injetada em " + key);
            }
            delegate.put(key, value);
        }
        @Override public boolean putIfAbsent(String key, byte[] value) { return delegate.putIfAbsent(key, value); }
        @Override public boolean delete(String key) { return delegate.delete(key); }
        @Override public List<String> keysWithPrefix(String prefix) {
            scans.incrementAndGet();
            return delegate.keysWithPrefix(prefix);
        }
        @Override public long versionOf(String key) { return delegate.versionOf(key); }
        @Override public void commit(Map<String, Long> expected, Map<String, byte[]> puts,
                                     Set<String> deletes, Set<String> readPrefixes) {
            delegate.commit(expected, puts, deletes, readPrefixes);
        }
    }

    private static Session session(String ns, String db) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        return s;
    }

    // ---- Ponto 1: unique com chaves que terminam igual -----------------------

    @Test
    @DisplayName("1a. CREATE t:1 com email já usado por t:11 deve violar o unique")
    void uniqueCreateWithSuffixKey() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session("test", "dev");
        ds.execute("DEFINE INDEX email ON TABLE t COLUMNS email UNIQUE", s, null);
        ds.execute("CREATE t:11 CONTENT {email: 'a@x.com'}", s, null);

        AxonError e = assertThrows(AxonError.class,
            () -> ds.execute("CREATE t:1 CONTENT {email: 'a@x.com'}", s, null));
    }

    @Test
    @DisplayName("1b. UPDATE t:1 para email de t:11 deve violar o unique")
    void uniqueUpdateWithSuffixKey() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session("test", "dev");
        ds.execute("DEFINE INDEX email ON TABLE t COLUMNS email UNIQUE", s, null);
        ds.execute("CREATE t:11 CONTENT {email: 'a@x.com'}", s, null);
        ds.execute("CREATE t:1 CONTENT {email: 'b@x.com'}", s, null);

        assertThrows(AxonError.class,
            () -> ds.execute("UPDATE t:1 SET email = 'a@x.com'", s, null));
    }

    @Test
    @DisplayName("1c. controle: ordem inversa (t:1 primeiro, depois t:11) já viola")
    void uniqueCreateReverseOrderControl() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session("test", "dev");
        ds.execute("DEFINE INDEX email ON TABLE t COLUMNS email UNIQUE", s, null);
        ds.execute("CREATE t:1 CONTENT {email: 'a@x.com'}", s, null);

        assertThrows(AxonError.class,
            () -> ds.execute("CREATE t:11 CONTENT {email: 'a@x.com'}", s, null));
    }

    @Test
    @DisplayName("1d. ids string 'sfoo' e 'foo' (keyString ssfoo e sfoo): duplicado deve violar o unique")
    void uniqueCreateWithStringSuffixKey() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session("test", "dev");
        ds.execute("DEFINE INDEX email ON TABLE t COLUMNS email UNIQUE", s, null);
        ds.execute("CREATE t CONTENT {id: 'sfoo', email: 'a@x.com'}", s, null);

        boolean threw = false;
        try {
            ds.execute("CREATE t CONTENT {id: 'foo', email: 'a@x.com'}", s, null);
        } catch (AxonError e) {
            threw = true;
        }
        AxonValue rows = ds.execute("SELECT * FROM t WHERE email = 'a@x.com'", s, null);
        assertTrue(threw, "dois registros com o mesmo valor em índice UNIQUE");
    }

    @Test
    @DisplayName("1e. id número 1 e id string 'n1' (keyString n1 e sn1)")
    void uniqueCreateNumberVsStringKey() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session("test", "dev");
        ds.execute("DEFINE INDEX email ON TABLE t COLUMNS email UNIQUE", s, null);
        ds.execute("CREATE t CONTENT {id: 'n1', email: 'a@x.com'}", s, null);

        boolean threw = false;
        try {
            ds.execute("CREATE t CONTENT {id: 1, email: 'a@x.com'}", s, null);
        } catch (AxonError e) {
            threw = true;
        }
        AxonValue rows = ds.execute("SELECT * FROM t WHERE email = 'a@x.com'", s, null);
        assertTrue(threw, "dois registros com o mesmo valor em índice UNIQUE");
    }

    // ---- Ponto 2: falha ao gravar passo de saga ------------------------------

    private ProbeBackend sagaFixture(Datastore[] out, Session[] sessionOut) {
        ProbeBackend backend = new ProbeBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "bank");
        Session s = session("test", "bank");
        ds.execute("CREATE orders:o1 CONTENT {total: 150}", s, null);
        ds.execute("BEGIN SAGA pedido WITH CORRELATION 'c1'", s, null);
        ds.execute("LET $saga_corr = 'c1'", s, null);
        out[0] = ds;
        sessionOut[0] = s;
        // falha só na gravação dos passos de saga
        backend.failPut = key -> key.startsWith("system\u0000saga\u0000saga_step\u0000");
        return backend;
    }

    @Test
    @DisplayName("2a. UPDATE com saga: se o passo não grava, a mutação não deve ficar aplicada sem compensação")
    void sagaUpdateStepFailure() {
        Datastore[] ds = new Datastore[1];
        Session[] s = new Session[1];
        sagaFixture(ds, s);

        boolean threw = false;
        try {
            ds[0].execute("UPDATE orders:o1 SET total = 200", s[0], null);
        } catch (RuntimeException e) {
            threw = true;
        }
        AxonValue row = ds[0].execute("SELECT * FROM orders:o1", s[0], null);
        long total = row.asObject().get("total").asLong();
        // Desejado: ou lança e total=150, ou grava o passo. Defeito: não lança e total=200 sem passo.
        assertTrue(threw || total == 150,
            "mutação aplicada (total=" + total + ") sem passo de compensação e sem erro");
    }

    @Test
    @DisplayName("2b. DELETE local com saga: falha do passo se propaga?")
    void sagaDeleteStepFailure() {
        Datastore[] ds = new Datastore[1];
        Session[] s = new Session[1];
        sagaFixture(ds, s);

        boolean threw = false;
        try {
            ds[0].execute("DELETE orders:o1", s[0], null);
        } catch (RuntimeException e) {
            threw = true;
        }
        AxonValue rows = ds[0].execute("SELECT * FROM orders:o1", s[0], null);
        int remaining = rows.isArray() ? rows.asArray().size() : 1;
        assertTrue(threw || remaining == 1,
            "registro apagado sem passo de compensação e sem erro");
    }

    // ---- Ponto 3: UPDATE/DELETE devem usar índice como o SELECT --------------

    private ProbeBackend indexedTable(Datastore[] dsOut, Session[] sOut, String indexDdl) {
        ProbeBackend backend = new ProbeBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "dev");
        Session s = session("test", "dev");
        ds.execute(indexDdl, s, null);
        for (int i = 0; i < 500; i++) {
            ds.execute("CREATE t:" + i + " CONTENT {n: " + i + "}", s, null);
        }
        backend.gets.set(0);
        backend.scans.set(0);
        dsOut[0] = ds;
        sOut[0] = s;
        return backend;
    }

    @Test
    @DisplayName("3a. UPDATE ... WHERE campo UNIQUE = literal não lê a tabela inteira")
    void updateWhereUsesUniqueIndex() {
        Datastore[] ds = new Datastore[1];
        Session[] s = new Session[1];
        ProbeBackend backend = indexedTable(ds, s, "DEFINE INDEX n_uq ON TABLE t COLUMNS n UNIQUE");

        AxonValue out = ds[0].execute("UPDATE t SET flag = true WHERE n = 7", s[0], null);

        assertEquals(1, out.asArray().size());
        assertTrue(backend.gets.get() < 50, "leituras=" + backend.gets.get() + " para 1 de 500");
        AxonValue row = ds[0].execute("SELECT * FROM t:7", s[0], null);
        assertEquals(true, row.asObject().get("flag").asBool());
        AxonValue other = ds[0].execute("SELECT * FROM t:8", s[0], null);
        assertEquals(false, other.asObject().containsKey("flag"));
    }

    @Test
    @DisplayName("3b. DELETE ... WHERE campo UNIQUE = literal não lê a tabela inteira")
    void deleteWhereUsesUniqueIndex() {
        Datastore[] ds = new Datastore[1];
        Session[] s = new Session[1];
        ProbeBackend backend = indexedTable(ds, s, "DEFINE INDEX n_uq ON TABLE t COLUMNS n UNIQUE");

        ds[0].execute("DELETE t WHERE n = 7", s[0], null);

        assertTrue(backend.gets.get() < 50, "leituras=" + backend.gets.get() + " para 1 de 500");
        assertEquals(0, ds[0].execute("SELECT * FROM t:7", s[0], null).asArray().size());
        assertEquals(499, ds[0].execute("SELECT * FROM t", s[0], null).asArray().size());
    }

    @Test
    @DisplayName("3c. UPDATE com WHERE sem índice ainda funciona (varredura) e valor ausente não atualiza nada")
    void updateWhereWithoutIndexAndMissingValue() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session("test", "dev");
        ds.execute("DEFINE INDEX n_uq ON TABLE t COLUMNS n UNIQUE", s, null);
        ds.execute("CREATE t:1 CONTENT {n: 1, g: 'x'}", s, null);
        ds.execute("CREATE t:2 CONTENT {n: 2, g: 'x'}", s, null);

        assertEquals(0, ds.execute("UPDATE t SET z = 1 WHERE n = 99", s, null).asArray().size());
        assertEquals(2, ds.execute("UPDATE t SET z = 1 WHERE g = 'x'", s, null).asArray().size());
    }

    // ---- Ponto 4: isMutation por prefixo -------------------------------------

    @Test
    @DisplayName("4. EXPLAIN, DESCRIBE e SHOW são leituras")
    void isMutationClassification() {
        assertEquals(false, Datastore.isMutation("EXPLAIN SELECT * FROM t"));
        assertEquals(false, Datastore.isMutation("DESCRIBE t"));
        assertEquals(false, Datastore.isMutation("SHOW DATA RULES"));
        assertEquals(false, Datastore.isMutation("-- c\nSELECT * FROM t"));
        assertEquals(false, Datastore.isMutation("/* c */ SELECT * FROM t"));
        assertEquals(false, Datastore.isMutation("INFO FOR TABLE t"));
        assertEquals(false, Datastore.isMutation("SHOW SAGA TRANSACTION a 'b'"));
        assertEquals(false, Datastore.isMutation("BEGIN"));
        assertEquals(false, Datastore.isMutation("RETURN 1"));
        assertEquals(false, Datastore.isMutation("LIVE SELECT * FROM t"));
        assertEquals(true, Datastore.isMutation("CREATE t:1 CONTENT {a: 1}"));
        assertEquals(true, Datastore.isMutation("SELECT 1; DELETE t"));
        assertEquals(true, Datastore.isMutation("SELECT * FROM t; CREATE t:2 CONTENT {a: 1}"));
        assertEquals(true, Datastore.isMutation("isto não parseia"));
    }
}
