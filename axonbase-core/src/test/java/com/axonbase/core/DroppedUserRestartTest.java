package com.axonbase.core;

import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Usuário removido: o servidor volta a subir e a remoção persiste")
class DroppedUserRestartTest {

    private static final String PASSWORD = "Test-pw-1";

    private final MemoryBackend backend = new MemoryBackend();
    private final Datastore ds = new Datastore(backend);

    private void run(String sql) {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        ds.execute(sql, s, null);
    }

    private void define(String scope) {
        run("DEFINE USER n2 ON " + scope + " PASSWORD \"" + PASSWORD + "\" ROLES editor");
    }

    private AuthCatalog.User signin(Datastore target) {
        return target.authCatalog().verify("n2", PASSWORD, "n", "d");
    }

    @Test
    void usuarioDeNamespaceRemovidoPelaApiNaoImpedeOReinicio() {
        define("NAMESPACE");
        ds.dropUser(null, "n2", AuthCatalog.Scope.NAMESPACE, "n", null);

        assertNull(signin(new Datastore(backend)));
    }

    @Test
    void usuarioDeBancoRemovidoPelaApiNaoVolta() {
        define("DATABASE");
        ds.dropUser(null, "n2", AuthCatalog.Scope.DATABASE, "n", "d");

        assertNull(signin(new Datastore(backend)));
    }

    @Test
    void usuarioRecriadoDepoisDoDropSobrevive() {
        define("NAMESPACE");
        run("DROP USER n2");
        define("NAMESPACE");

        assertNotNull(signin(new Datastore(backend)));
    }

    @Test
    void baseGravadaPorVersaoAnteriorVoltaAAbrirEPermiteRemover() {
        define("NAMESPACE");
        // o que a remoção pela API gravava: um DROP USER sem banco
        ds.applyControl(ControlCommand.of(ControlCommand.Kind.USER, "n", "", "n2", "DROP USER n2"));

        Datastore reopened = assertDoesNotThrow(() -> new Datastore(backend));
        reopened.dropUser(null, "n2", AuthCatalog.Scope.NAMESPACE, "n", null);

        assertNull(signin(new Datastore(backend)));
    }
}
