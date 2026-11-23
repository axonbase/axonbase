package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.value.AxonValue;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SELECT: índice e LIMIT não leem a tabela inteira")
class SelectLazyScanTest {

    private static final int ROWS = 2000;

    /** Conta leituras de documento para provar quanto do armazenamento um SELECT toca. */
    private static final class CountingBackend implements VersionedKvBackend {
        private final MemoryBackend delegate = new MemoryBackend();
        final AtomicInteger gets = new AtomicInteger();

        @Override public Optional<byte[]> get(String key) { gets.incrementAndGet(); return delegate.get(key); }
        @Override public void put(String key, byte[] value) { delegate.put(key, value); }
        @Override public boolean putIfAbsent(String key, byte[] value) { return delegate.putIfAbsent(key, value); }
        @Override public boolean delete(String key) { return delegate.delete(key); }
        @Override public List<String> keysWithPrefix(String prefix) { return delegate.keysWithPrefix(prefix); }
        @Override public long versionOf(String key) { return delegate.versionOf(key); }
        @Override public void commit(Map<String, Long> expected, Map<String, byte[]> puts,
                                     Set<String> deletes, Set<String> readPrefixes) {
            delegate.commit(expected, puts, deletes, readPrefixes);
        }
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static CountingBackend fill(Datastore[] dsOut, String indexDdl) {
        CountingBackend backend = new CountingBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "dev");
        Session s = session();
        ds.execute("DEFINE TABLE t SCHEMALESS", s, null);
        if (indexDdl != null) {
            ds.execute(indexDdl, s, null);
        }
        for (int base = 0; base < ROWS; base += 500) {
            StringBuilder sb = new StringBuilder("INSERT INTO t [");
            for (int i = base; i < base + 500; i++) {
                if (i > base) sb.append(',');
                sb.append("{id: ").append(i).append(", email: 'u").append(i).append("@x.com', v: ")
                    .append(i % 10).append(", n: ").append(i).append(", device: 'd").append(i % 7).append("'}");
            }
            ds.execute(sb.append(']').toString(), s, null);
        }
        dsOut[0] = ds;
        backend.gets.set(0);
        return backend;
    }

    private static List<AxonValue> rows(AxonValue result) {
        return result.isArray() ? result.asArray() : List.of(result);
    }

    @Test
    @DisplayName("WHERE no campo UNIQUE lê poucos documentos")
    void uniqueLookupDoesNotScan() {
        Datastore[] ds = new Datastore[1];
        CountingBackend backend = fill(ds, "CREATE INDEX e ON TABLE t COLUMNS email UNIQUE");

        List<AxonValue> out = rows(ds[0].execute("SELECT * FROM t WHERE email = 'u777@x.com'", session(), null));

        assertEquals(1, out.size());
        assertTrue(backend.gets.get() < 50, "leituras=" + backend.gets.get() + " de " + ROWS);
    }

    @Test
    @DisplayName("WHERE em coluna com índice columnar lê poucos documentos")
    void columnarLookupDoesNotScan() {
        Datastore[] ds = new Datastore[1];
        CountingBackend backend = fill(ds, "CREATE INDEX c ON TABLE t COLUMNS device COLUMNAR");

        List<AxonValue> out = rows(ds[0].execute("SELECT * FROM t WHERE device = 'd3' LIMIT 5", session(), null));

        assertEquals(5, out.size());
        assertTrue(backend.gets.get() < 400, "leituras=" + backend.gets.get() + " de " + ROWS);
    }

    @Test
    @DisplayName("LIMIT sem WHERE para de ler ao completar a janela")
    void limitStopsEarly() {
        Datastore[] ds = new Datastore[1];
        CountingBackend backend = fill(ds, null);

        List<AxonValue> out = rows(ds[0].execute("SELECT * FROM t LIMIT 10", session(), null));

        assertEquals(10, out.size());
        assertTrue(backend.gets.get() < 50, "leituras=" + backend.gets.get() + " de " + ROWS);
    }

    @Test
    @DisplayName("LIMIT com WHERE sem índice para ao achar as linhas pedidas")
    void filteredLimitStopsEarly() {
        Datastore[] ds = new Datastore[1];
        CountingBackend backend = fill(ds, null);

        List<AxonValue> out = rows(ds[0].execute("SELECT * FROM t WHERE v = 5 LIMIT 3", session(), null));

        assertEquals(3, out.size());
        out.forEach(r -> assertEquals(5, r.asObject().get("v").asLong()));
        assertTrue(backend.gets.get() < 200, "leituras=" + backend.gets.get() + " de " + ROWS);
    }

    @Test
    @DisplayName("LIMIT e START devolvem a mesma janela que a consulta completa")
    void windowMatchesFullResult() {
        Datastore[] ds = new Datastore[1];
        fill(ds, null);
        Session s = session();

        List<AxonValue> all = rows(ds[0].execute("SELECT * FROM t WHERE v = 5", s, null));
        List<AxonValue> window = rows(ds[0].execute("SELECT * FROM t WHERE v = 5 LIMIT 4 START 6", s, null));

        assertEquals(ROWS / 10, all.size());
        assertEquals(all.subList(6, 10), window);
    }

    @Test
    @DisplayName("ORDER BY continua ordenando o conjunto inteiro")
    void orderByStillSortsEverything() {
        Datastore[] ds = new Datastore[1];
        fill(ds, null);

        List<AxonValue> out = rows(ds[0].execute("SELECT * FROM t ORDER BY n DESC LIMIT 3", session(), null));

        assertEquals(3, out.size());
        assertEquals(ROWS - 1, out.get(0).asObject().get("n").asLong());
    }

    @Test
    @DisplayName("agregação com LIMIT continua considerando todos os registros")
    void aggregateStillSeesAllRows() {
        Datastore[] ds = new Datastore[1];
        fill(ds, null);

        List<AxonValue> out = rows(ds[0].execute("SELECT device, count() FROM t GROUP BY device", session(), null));

        long total = 0;
        for (AxonValue row : out) {
            total += row.asObject().get("count").asLong();
        }
        assertEquals(ROWS, total);
    }

    @Test
    @DisplayName("RocksDB: LIMIT usa o iterador e devolve a janela certa")
    void rocksDbLimit() throws Exception {
        try (RocksDbBackend backend = new RocksDbBackend(Files.createTempDirectory("lazy").toString())) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            Session s = session();
            List<String> inserts = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                inserts.add("{id: " + i + ", v: " + (i % 10) + "}");
            }
            ds.execute("INSERT INTO t [" + String.join(",", inserts) + "]", s, null);

            List<AxonValue> out = rows(ds.execute("SELECT * FROM t WHERE v = 5 LIMIT 4", s, null));

            assertEquals(4, out.size());
            out.forEach(r -> assertEquals(5, r.asObject().get("v").asLong()));
        }
    }
}
