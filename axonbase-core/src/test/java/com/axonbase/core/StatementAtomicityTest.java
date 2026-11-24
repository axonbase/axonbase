package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Statement com vários índices grava tudo ou nada (RocksDB)")
class StatementAtomicityTest {

    @TempDir
    Path dir;

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static int rows(AxonValue result) {
        return result.isArray() ? result.asArray().size() : 1;
    }

    private static final String SETUP = "DEFINE TABLE p SCHEMALESS; "
        + "CREATE INDEX p_cod ON TABLE p COLUMNS codigo UNIQUE; "
        + "CREATE INDEX p_st ON TABLE p COLUMNS status COLUMNAR";

    @Test
    @DisplayName("INSERT com duplicata no meio não deixa nenhuma linha nem entrada de índice")
    void failingStatementLeavesNothing() {
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("a").toString())) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            Session s = session();
            ds.execute(SETUP, s, null);
            ds.execute("CREATE p:0 CONTENT {codigo: 'x0', status: 'novo'}", s, null);

            int before = backend.keysWithPrefix("").size();
            // a quarta linha repete o código da segunda: a segunda precisa ser vista (ler o que escreveu)
            assertThrows(AxonError.class, () -> ds.execute(
                "INSERT INTO p [{id: 1, codigo: 'a', status: 'novo'}, {id: 2, codigo: 'b', status: 'preparo'}, "
                    + "{id: 3, codigo: 'c', status: 'novo'}, {id: 4, codigo: 'b', status: 'novo'}]", s, null));

            assertEquals(before, backend.keysWithPrefix("").size(), "o statement que falhou deixou chaves");
            assertEquals(1, rows(ds.execute("SELECT * FROM p", s, null)));
            assertEquals(0, rows(ds.execute("SELECT * FROM p WHERE codigo = 'a'", s, null)));
            // o código 'a' continua livre: a entrada de índice não ficou para trás
            ds.execute("CREATE p:9 CONTENT {codigo: 'a', status: 'novo'}", s, null);
            assertEquals(1, rows(ds.execute("SELECT * FROM p WHERE codigo = 'a'", s, null)));
        }
    }

    @Test
    @DisplayName("statement bem-sucedido grava registro e índices e sobrevive à reabertura")
    void successPersistsRecordAndIndexes() {
        String path = dir.resolve("b").toString();
        try (RocksDbBackend backend = new RocksDbBackend(path)) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            Session s = session();
            ds.execute(SETUP, s, null);
            ds.execute("INSERT INTO p [{id: 1, codigo: 'a', status: 'novo'}, {id: 2, codigo: 'b', status: 'preparo'}]", s, null);
            ds.execute("UPDATE p:1 SET status = 'entregue'", s, null);
            ds.execute("DELETE p:2", s, null);
            assertEquals(1, rows(ds.execute("SELECT * FROM p WHERE status = 'entregue'", s, null)));
            assertEquals(0, rows(ds.execute("SELECT * FROM p WHERE codigo = 'b'", s, null)));
            ds.execute("CREATE p:3 CONTENT {codigo: 'd', status: 'novo'}", s, null);
        }
        try (RocksDbBackend backend = new RocksDbBackend(path)) {
            Datastore ds = new Datastore(backend);
            Session s = session();
            assertEquals(2, rows(ds.execute("SELECT * FROM p", s, null)));
            assertEquals(1, rows(ds.execute("SELECT * FROM p WHERE status = 'entregue'", s, null)));
        }
    }

    @Test
    @DisplayName("UPDATE em vários registros lê as próprias escritas e persiste todas")
    void multiRowUpdate() {
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("c").toString())) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            Session s = session();
            ds.execute(SETUP, s, null);
            for (int i = 0; i < 20; i++) {
                ds.execute("CREATE p:" + i + " CONTENT {codigo: 'c" + i + "', status: 'novo'}", s, null);
            }
            ds.execute("UPDATE p SET status = 'preparo'", s, null);
            assertEquals(20, rows(ds.execute("SELECT * FROM p WHERE status = 'preparo'", s, null)));
            assertEquals(0, rows(ds.execute("SELECT * FROM p WHERE status = 'novo'", s, null)));
        }
    }
}
