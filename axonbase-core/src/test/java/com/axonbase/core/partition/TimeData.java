package com.axonbase.core.partition;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;

import java.nio.file.Path;
import java.util.List;

/** Um motor com o banco {@code test/dev} e atalhos para os testes de partição e TTL. */
final class TimeData implements AutoCloseable {

    static final String DAY1 = "2026-11-01T10:00:00Z";
    static final String DAY2 = "2026-11-02T10:00:00Z";
    static final String DAY3 = "2026-11-03T10:00:00Z";

    KvBackend backend;
    Datastore ds;
    private final Path directory;
    private Session session = newSession();

    private TimeData(KvBackend backend, Path directory) {
        this.backend = backend;
        this.directory = directory;
        this.ds = new Datastore(backend);
        ds.createDatabase("test", "dev");
    }

    static TimeData memory() {
        return new TimeData(new MemoryBackend(), null);
    }

    static TimeData rocks(Path directory) {
        return new TimeData(new RocksDbBackend(directory.toString(), RocksDbBackend.SyncMode.OFF), directory);
    }

    static Session newSession() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    /** Fecha e reabre a base em disco, como num reinício do servidor. */
    void restart() {
        ((RocksDbBackend) backend).close();
        backend = new RocksDbBackend(directory.toString(), RocksDbBackend.SyncMode.OFF);
        ds = new Datastore(backend);
        session = newSession();
    }

    AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    AxonValue run(Session other, String sql) {
        return ds.execute(sql, other, null);
    }

    List<AxonValue> rows(String sql) {
        return run(sql).asArray();
    }

    int count(String sql) {
        return rows(sql).size();
    }

    /** Valores de um campo texto das linhas devolvidas, em ordem alfabética. */
    List<String> values(String sql, String field) {
        return rows(sql).stream().map(r -> r.asObject().get(field).asString()).sorted().toList();
    }

    /** Uma tabela {@code events} particionada por dia, com uma linha em cada um de três dias. */
    TimeData threeDays() {
        run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
        return insertThreeDays();
    }

    TimeData insertThreeDays() {
        run("CREATE events:a CONTENT {at: d\"" + DAY1 + "\", who: \"ana\"}");
        run("CREATE events:b CONTENT {at: d\"" + DAY2 + "\", who: \"bia\"}");
        run("CREATE events:c CONTENT {at: d\"" + DAY3 + "\", who: \"caio\"}");
        return this;
    }

    /** Chaves físicas de registros e índices que sobraram da tabela. */
    List<String> physicalKeys(String table) {
        return backend.keysWithPrefix(PartitionKeys.tablePrefix("test", "dev", table));
    }

    @Override
    public void close() {
        backend.close();
    }
}
