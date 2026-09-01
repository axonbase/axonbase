package com.axonbase.core.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Savepoints: checkpoint de writes/deletes dentro dunha transacción, coa pila
 * de {@link Transaction}. Os reads (snapshot e versións) non se botan ao
 * retroceder.
 */
class SavepointTest {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    @Test
    void rollbackToReverteSoEscopoDespoisDoCheckpoint() {
        MemoryBackend backend = new MemoryBackend();
        Transaction tx = new Transaction(backend);
        tx.put("k1", bytes("antes"));
        tx.savepoint("checkpoint");
        tx.put("k2", bytes("dentro"));
        tx.delete("k1");
        tx.rollbackTo("checkpoint");

        tx.commit();
        assertEquals("antes", text(backend.get("k1").orElseThrow()));
        assertTrue(backend.get("k2").isEmpty(), "a escrita do savepoint debe revertar");
    }

    @Test
    void releaseMantemEscriturasDoSavepoint() {
        MemoryBackend backend = new MemoryBackend();
        Transaction tx = new Transaction(backend);
        tx.put("k1", bytes("antes"));
        tx.savepoint("checkpoint");
        tx.put("k2", bytes("dentro"));
        tx.release("checkpoint");
        tx.commit();

        assertEquals("antes", text(backend.get("k1").orElseThrow()));
        assertEquals("dentro", text(backend.get("k2").orElseThrow()));
    }

    @Test
    void rollbackToPreservaLecturasJaFeitas() {
        MemoryBackend backend = new MemoryBackend();
        backend.put("k", bytes("v0"));
        Transaction tx = new Transaction(backend);

        String lido = text(tx.get("k").orElseThrow());
        assertEquals("v0", lido);
        tx.savepoint("checkpoint");
        tx.put("k", bytes("v1"));
        tx.rollbackTo("checkpoint");

        assertEquals("v0", text(tx.get("k").orElseThrow()),
            "snapshot da primeira lectura debe manterse tras o rollback");
        tx.commit();
        assertEquals("v0", text(backend.get("k").orElseThrow()));
    }

    @Test
    void commitUsaSoEstadoFinalPoxasCapas() {
        MemoryBackend backend = new MemoryBackend();
        Transaction tx = new Transaction(backend);
        tx.put("a", bytes("1"));
        tx.savepoint("sp1");
        tx.put("b", bytes("2"));
        tx.savepoint("sp2");
        tx.put("c", bytes("3"));
        tx.rollbackTo("sp1");
        tx.put("d", bytes("4"));
        tx.commit();

        assertEquals("1", text(backend.get("a").orElseThrow()));
        assertTrue(backend.get("b").isEmpty(), "escrita entre sp1 e sp2 debe revertar");
        assertTrue(backend.get("c").isEmpty(), "escrita tras sp2 debe revertar");
        assertEquals("4", text(backend.get("d").orElseThrow()));
    }

    @Test
    void rollbackToNomeInesistenteLanza() {
        Transaction tx = new Transaction(new MemoryBackend());
        tx.put("a", bytes("1"));
        tx.savepoint("sp1");
        assertThrows(IllegalArgumentException.class, () -> tx.rollbackTo("sp2"));
        // o estado non debe cambiar tras o erro
        tx.release("sp1");
        tx.commit();
    }
}