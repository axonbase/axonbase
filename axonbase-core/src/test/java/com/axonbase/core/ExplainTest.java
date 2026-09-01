package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplainTest {

    private Datastore datastore() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    @Test
    void explainIgualdadeUnicaDeleIndex() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE TABLE t SCHEMALESS", s, null);
        ds.execute("DEFINE INDEX a_idx ON TABLE t COLUMNS a UNIQUE", s, null);
        ds.execute("CREATE t:x CONTENT { a: \"x\", u: \"v\" }", s, null);
        AxonValue plan = ds.execute("EXPLAIN SELECT * FROM t WHERE a = \"x\"", s, null);
        assertEquals("UNIQUE_LOOKUP", plan.asObject().get("strategy").asString());
        assertEquals("a_idx", plan.asObject().get("indexName").asString());
        assertEquals("t", plan.asObject().get("table").asString());
    }

    @Test
    void explainSenCondicionEAFullScan() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT { n: 1 }", s, null);
        AxonValue plan = ds.execute("EXPLAIN SELECT * FROM t", s, null);
        assertEquals("FULL_SCAN", plan.asObject().get("strategy").asString());
        assertTrue(((AxonValue) plan.asObject().get("examinedRows")).isNumber());
    }

    @Test
    void explainFullTextUsaOIndiceInvertido() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE ANALYZER plain LOWERCASE", s, null);
        ds.execute("DEFINE INDEX body_idx ON TABLE t COLUMNS body SEARCH ANALYZER plain", s, null);
        ds.execute("CREATE t:1 CONTENT { body: \"gato corredor\" }", s, null);
        AxonValue plan = ds.execute("EXPLAIN SELECT * FROM t WHERE body @@ \"gato\"", s, null);
        assertEquals("FULLTEXT", plan.asObject().get("strategy").asString());
        assertEquals("body_idx", plan.asObject().get("indexName").asString());
    }

    @Test
    void explainAnalyzeContaFilasReais() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("CREATE t:1 CONTENT { n: 1 }", s, null);
        ds.execute("CREATE t:2 CONTENT { n: 2 }", s, null);
        ds.execute("CREATE t:3 CONTENT { n: 3 }", s, null);
        AxonValue plan = ds.execute("EXPLAIN ANALYZE SELECT * FROM t", s, null);
        assertEquals("FULL_SCAN", plan.asObject().get("strategy").asString());
        assertEquals(3L, plan.asObject().get("examinedRows").asLong());
    }

@Test
    void selectNormalUsaIndiceUnicoEFiltraNoWhere() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE INDEX a_idx ON TABLE t COLUMNS a UNIQUE", s, null);
        ds.execute("CREATE t:x CONTENT { a: \"x\", n: 1 }", s, null);
        ds.execute("CREATE t:y CONTENT { a: \"y\", n: 2 }", s, null);
        AxonValue rows = ds.execute("SELECT n FROM t WHERE a = \"x\"", s, null);
        assertEquals(1, rows.asArray().size());
        assertEquals(1L, rows.asArray().get(0).asObject().get("n").asLong());
        // o filtro adicional do WHERE segue aplicando depois do pré-selección
        AxonValue none = ds.execute("SELECT n FROM t WHERE a = \"x\" AND n = 99", s, null);
        assertEquals(0, none.asArray().size());
    }

    @Test
    void fetchSoResolveNosDocsDaLimit() {
        CountingBackend backend = new CountingBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "dev");
        Session s = session();
        ds.execute("CREATE author:1 CONTENT { name: \"Ana\" }", s, null);
        ds.execute("CREATE author:2 CONTENT { name: \"Bia\" }", s, null);
        int before = backend.authorReads;
        AxonValue out = ds.execute("SELECT * FROM [{autor: author:1}, {autor: author:2}] "
            + "LIMIT 1 FETCH autor", s, null);
        int reads = backend.authorReads - before;
        // A fonte virtual garda os record ids tipados en memoria: FETCH resolve
        // só a fiestra [start, stop), por isso só unha lectura de autor.
        assertEquals(1, out.asArray().size());
        assertEquals(1, reads);
    }

    /** Backend de memoria que conta as lecturas de registros de author. */
    private static final class CountingBackend implements com.axonbase.core.storage.KvBackend {
        private static final String AUTHOR_PREFIX = "test\u0000dev\u0000author\u0000";
        private final MemoryBackend delegate = new MemoryBackend();
        int authorReads;

        @Override
        public synchronized java.util.Optional<byte[]> get(String key) {
            if (key.startsWith(AUTHOR_PREFIX)) {
                authorReads++;
            }
            return delegate.get(key);
        }

        @Override
        public synchronized void put(String key, byte[] value) {
            delegate.put(key, value);
        }

        @Override
        public boolean putIfAbsent(String key, byte[] value) {
            return delegate.putIfAbsent(key, value);
        }

        @Override
        public boolean delete(String key) {
            return delegate.delete(key);
        }

        @Override
        public java.util.List<String> keysWithPrefix(String prefix) {
            return delegate.keysWithPrefix(prefix);
        }

        @Override
        public void flush() {
            delegate.flush();
        }
    }
}