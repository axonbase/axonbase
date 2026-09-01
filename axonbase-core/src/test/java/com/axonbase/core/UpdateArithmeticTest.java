package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testa UPDATE com expressões aritméticas como {@code SET qtd = qtd - 1}.
 */
class UpdateArithmeticTest {

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    /** Lê um campo, lidando com o fato do SELECT poder retornar object ou array. */
    private long readLong(Datastore ds, String sql, String campo) {
        AxonValue result = ds.execute(sql, session(), null);
        assertTrue(result.isArray() || result.isObject(),
            "esperado array ou object, got: " + result);
        java.util.Map<String, AxonValue> obj = result.isArray()
            ? result.asArray().get(0).asObject()
            : result.asObject();
        AxonValue val = obj.get(campo);
        assertTrue(val != null && val.isNumber(), "campo " + campo + " não é número");
        return val.asLong();
    }

    @Test
    void updateDecrementaValor() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        ds.execute("CREATE produto:1 CONTENT { nome: \"caneta\", quantidade: 10 }",
            session(), null);

        ds.execute("UPDATE produto:1 SET quantidade = quantidade - 1", session(), null);

        long qtd = readLong(ds, "SELECT quantidade FROM produto:1", "quantidade");
        assertEquals(9, qtd, "quantidade deveria ser 9 após decremento");
    }

    @Test
    void updateDecrementaMultiplasVezes() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        ds.execute("CREATE estoque:1 CONTENT { item: \"caderno\", saldo: 100 }",
            session(), null);

        for (int i = 0; i < 5; i++) {
            ds.execute("UPDATE estoque:1 SET saldo = saldo - 1", session(), null);
        }

        long saldo = readLong(ds, "SELECT saldo FROM estoque:1", "saldo");
        assertEquals(95, saldo, "saldo deveria ser 95 após 5 decrementos");
    }

    @Test
    void updateIncrementa() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        ds.execute("CREATE contador:1 CONTENT { valor: 0 }", session(), null);

        ds.execute("UPDATE contador:1 SET valor = valor + 10", session(), null);
        ds.execute("UPDATE contador:1 SET valor = valor + 5", session(), null);

        long valor = readLong(ds, "SELECT valor FROM contador:1", "valor");
        assertEquals(15, valor, "valor deveria ser 15 após 10+5");
    }

    @Test
    void updateComMultiplicacao() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        ds.execute("CREATE calc:1 CONTENT { x: 10 }", session(), null);

        ds.execute("UPDATE calc:1 SET x = x * 3", session(), null);

        long x = readLong(ds, "SELECT x FROM calc:1", "x");
        assertEquals(30, x, "x deveria ser 30 após x * 3");
    }

    @Test
    void updateComRocksDB() {
        var dir = java.nio.file.Path.of("/tmp/axon-update-test");
        deleteDir(dir);
        dir.toFile().mkdirs();

        Datastore ds = new Datastore(
            new com.axonbase.core.storage.RocksDbBackend(dir.toString()));
        ds.createDatabase("test", "dev");
        ds.execute("CREATE prod:1 CONTENT { qtd: 50 }", session(), null);

        for (int i = 0; i < 10; i++) {
            ds.execute("UPDATE prod:1 SET qtd = qtd - 1", session(), null);
        }

        long qtd = readLong(ds, "SELECT qtd FROM prod:1", "qtd");
        assertEquals(40, qtd, "qtd deveria ser 40 após 10 decrementos");
        deleteDir(dir);
    }

    private static void deleteDir(java.nio.file.Path dir) {
        if (dir.toFile().exists()) {
            try {
                java.nio.file.Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
            } catch (Exception ignored) {
            }
        }
    }
}