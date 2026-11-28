package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Data Rule: a remoção persiste e a regra não toma o lugar de uma tabela")
class DataRuleDropRestartTest {

    private final MemoryBackend backend = new MemoryBackend();
    private final Datastore ds = new Datastore(backend);

    private void run(String sql) {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        ds.execute(sql, s, null);
    }

    private static com.axonbase.core.catalog.Catalog catalog(Datastore target) {
        return target.findDatabase("n", "d").catalog();
    }

    @Test
    void regraRemovidaNaoVoltaNoReinicio() {
        run("CREATE DATA RULE keepme APPLY a = 1");
        run("DROP DATA RULE keepme");

        assertNull(catalog(new Datastore(backend)).dataRule("keepme"));
    }

    @Test
    void regraComNomeDeTabelaNaoApagaADefinicaoDaTabela() {
        run("DEFINE TABLE pedido SCHEMAFULL");
        run("CREATE DATA RULE pedido APPLY a = 1");

        Datastore reopened = new Datastore(backend);

        assertNotNull(catalog(reopened).dataRule("pedido"));
        assertTrue(catalog(reopened).table("pedido").schemafull());
    }

    @Test
    void removerARegraNaoRemoveATabelaDeMesmoNome() {
        run("DEFINE TABLE pedido SCHEMAFULL");
        run("CREATE DATA RULE pedido APPLY a = 1");
        run("DROP DATA RULE pedido");

        Datastore reopened = new Datastore(backend);

        assertNull(catalog(reopened).dataRule("pedido"));
        assertTrue(catalog(reopened).table("pedido").schemafull());
    }
}
