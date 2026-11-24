package com.axonbase.core.pl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O usuário root do arranque do servidor não faz parte do plano de controle. Desfazer uma sessão (por exemplo uma
 * procedure que falha) reconstrói o catálogo a partir do plano de controle e não pode apagar esse usuário: sem
 * ele ninguém consegue abrir uma nova sessão até o servidor reiniciar.
 */
class BootstrapUserSurvivesRollbackTest {

    private Datastore ds;
    private Session s;

    @BeforeEach
    void preparar() {
        ds = Datastore.memory();
        ds.authCatalog().ensureUser("root", "segredo");
        s = Session.create();
        s.namespace("t");
        s.database("d");
    }

    private boolean rootEntra() {
        return ds.authCatalog().verify("root", "segredo", null, null) != null;
    }

    @Test
    void cancelSessionNaoApagaORootDoArranque() {
        assertNotNull(ds.authCatalog().verify("root", "segredo", null, null));
        ds.cancelSession(s);
        assertNotNull(ds.authCatalog().verify("root", "segredo", null, null), "root precisa continuar entrando");
        assertNull(ds.authCatalog().verify("root", "outra", null, null));
    }

    @Test
    void procedureQueFalhaNaoDerrubaOLogin() {
        ds.execute("CREATE PROCEDURE quebra() AS $$\ndb.exec(\"CREATE x CONTENT {a: 1}\")\nraise ValueError('falhou')\n$$", s, null);
        assertThrows(AxonError.class, () -> ds.execute("CALL quebra()", s, null));
        assertNotNull(ds.authCatalog().verify("root", "segredo", null, null),
            "uma procedure que falha não pode apagar o usuário root");
    }

    @Test
    void usuarioDefinidoPeloPlanoDeControleContinuaEOverridePrevalece() {
        ds.execute("DEFINE USER ana ON ROOT PASSWORD 'a1' ROLES VIEWER", s, null);
        ds.cancelSession(s);
        assertNotNull(ds.authCatalog().verify("ana", "a1", null, null), "usuário persistido volta no replay");
        org.junit.jupiter.api.Assertions.assertTrue(rootEntra());
    }
}
