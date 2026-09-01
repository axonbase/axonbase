package com.axonbase.core.storage;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Aislamiento de lends fantasma: unha transacción que varre un prefixo
 * (ex. {@code SELECT * FROM t}) non debe poder confirmar se outra transacción
 * introduce unha chave nova dentro dese prefijo despois do varrido.
 */
class PhantomIsolationTest {

    private Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private Session session() {
        Session s = Session.create();
        s.namespace("db");
        s.database("dev");
        return s;
    }

    @Test
    void commitConflitaCandoOutraTxInsireNoPrefixoVarrido() {
        Datastore ds = ds();
        Session reader = session();
        Session writer = session();
        ds.execute("CREATE item CONTENT { name: \"A\" }", writer, null);

        ds.execute("BEGIN", reader, null);
        AxonValue seen = ds.execute("SELECT * FROM item", reader, null);
        assertEquals(1, seen.asArray().size());

        ds.execute("CREATE item CONTENT { name: \"B\" }", writer, null);

        assertThrows(VersionConflictException.class, () -> ds.execute("COMMIT", reader, null),
            "o commit do lectutor debe detectar a chave nova no prefixo varrido");
    }

    @Test
    void nonConflitaQuandoInsereForaDoPrefixo() {
        Datastore ds = ds();
        Session reader = session();
        Session writer = session();
        ds.execute("CREATE item CONTENT { name: \"A\" }", writer, null);

        ds.execute("BEGIN", reader, null);
        AxonValue seen = ds.execute("SELECT * FROM item", reader, null);
        assertEquals(1, seen.asArray().size());

        ds.execute("CREATE outra CONTENT { name: \"fora\" }", writer, null);

        ds.execute("COMMIT", reader, null);
        assertEquals(1, ds.execute("SELECT * FROM item", reader, null).asArray().size(),
            "chave doutra táboa non habita no prefixo varrido de item");
    }

    @Test
    void senMudanzaExternaCommitDoVarridoVerde() {
        Datastore ds = ds();
        Session reader = session();
        ds.execute("CREATE item CONTENT { name: \"A\" }", reader, null);

        ds.execute("BEGIN", reader, null);
        assertEquals(1, ds.execute("SELECT * FROM item", reader, null).asArray().size());
        ds.execute("COMMIT", reader, null);
    }

    @Test
    void backendDetectaLendoFantasmaDiretamente() {
        MemoryBackend backend = new MemoryBackend();
        backend.put("t\u0000a", bytes("1"));
        backend.put("t\u0000b", bytes("2"));
        long versionA = backend.versionOf("t\u0000a");

        assertThrows(VersionConflictException.class, () -> backend.commit(
            Map.of("t\u0000a", versionA), Map.of(), Set.of(), Set.of("t\u0000")));

        // Sen o rango, o mesmo commit non conflita: só se valida a versión lida.
        backend.commit(Map.of("t\u0000a", versionA), Map.of(), Set.of(), Set.of());
        assertEquals("2", text(backend.get("t\u0000b").orElseThrow()));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}