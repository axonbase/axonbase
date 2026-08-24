package com.axonbase.core.control;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Controle: state machine e codec do catálogo replicado")
class ControlStateMachineTest {

    private static ControlCommand table(String name) {
        return ControlCommand.of(ControlCommand.Kind.TABLE, "app", "main", name,
            "DEFINE TABLE " + name + " SCHEMAFULL");
    }

    @Test
    void snapshotReconstruiDefinicoesDeterministicamente() {
        ControlStateMachine a = new ControlStateMachine();
        a.apply(table("person"));
        a.apply(ControlCommand.of(ControlCommand.Kind.USER, "app", "main", "ana:DATABASE:app:main",
            "DEFINE USER ana ON DATABASE PASSHASH \"aa:bb\""));
        ControlStateMachine b = new ControlStateMachine();
        b.restore(a.snapshot());
        assertEquals(CatalogCodec.encode(a.snapshot()), CatalogCodec.encode(b.snapshot()));
    }

    @Test
    void aplicarDuasVezesNaoDuplica() {
        ControlStateMachine state = new ControlStateMachine();
        state.apply(table("person"));
        state.apply(table("person"));
        assertEquals(1, state.size());
    }

    @Test
    void snapshotVemNaOrdemDeDependencia() {
        ControlStateMachine state = new ControlStateMachine();
        state.apply(ControlCommand.of(ControlCommand.Kind.INDEX, "app", "main", "person:ix", "x"));
        state.apply(ControlCommand.of(ControlCommand.Kind.FIELD, "app", "main", "person:age", "x"));
        state.apply(table("person"));
        state.apply(ControlCommand.of(ControlCommand.Kind.ANALYZER, "app", "main", "pt", "x"));
        state.apply(ControlCommand.database("app", "main"));
        assertEquals(List.of(ControlCommand.Kind.DATABASE, ControlCommand.Kind.ANALYZER,
                ControlCommand.Kind.TABLE, ControlCommand.Kind.FIELD, ControlCommand.Kind.INDEX),
            state.snapshot().commands().stream().map(ControlCommand::kind).toList());
    }

    @Test
    void storeRecuperaSnapshotDoBackend() {
        ControlStateMachine state = new ControlStateMachine();
        state.apply(table("t"));
        MemoryBackend backend = new MemoryBackend();
        ControlStore store = new ControlStore(backend);
        store.save(state, backend);
        assertEquals(CatalogCodec.encode(state.snapshot()), CatalogCodec.encode(store.load()));
    }

    @Test
    void codecIgnoraTipoDesconhecidoEmVezDeAbortar() {
        ControlSnapshot decoded = CatalogCodec.decode(
            "[{\"kind\":\"SHARD\",\"ns\":\"a\",\"db\":\"d\",\"name\":\"n\",\"definition\":\"\"}]");
        assertTrue(decoded.isEmpty());
    }

    @Test
    void datastoreMantemControleNoBackendEntreInstancias() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        first.applyControl(table("t"));
        assertTrue(new Datastore(backend).controlSnapshot().commands().contains(table("t")));
    }

    @Test
    void authCodecRejeitaHashSemSalt() {
        assertThrows(IllegalArgumentException.class, () -> AuthCodec.parse("semseparador"));
        assertEquals("aa", AuthCodec.parse("aa:bb").saltHex());
        assertEquals("bb", AuthCodec.parse("aa:bb").hashHex());
    }
}
