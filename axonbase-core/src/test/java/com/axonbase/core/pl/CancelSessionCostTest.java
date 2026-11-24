package com.axonbase.core.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

/**
 * Desfazer uma sessão só reconstrói o catálogo (reexecuta todo o plano de controle, de todos os bancos) quando houve
 * DDL. Uma procedure que falha ou um CANCEL só de dados não pode pagar esse custo, que cresce com o tamanho do
 * catálogo e trava as demais operações de controle enquanto dura.
 */
class CancelSessionCostTest {

    private final Datastore ds = Datastore.memory();
    private final Session s = session();

    private static Session session() {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, s, null);
    }

    @Test
    void procedureQueFalhaSemDdlNaoReconstroiOCatalogo() {
        run("DEFINE TABLE item SCHEMALESS");
        run("CREATE PROCEDURE quebra() AS $$\ndb.exec(\"CREATE item CONTENT {a: 1}\")\nraise ValueError('falhou')\n$$");
        long antes = ds.catalogRebuilds();
        assertThrows(AxonError.class, () -> run("CALL quebra()"));
        assertEquals(antes, ds.catalogRebuilds(), "falha só de dados não pode reconstruir o catálogo");
        assertEquals(0, run("SELECT * FROM item").asArray().size(), "os dados continuam desfeitos");
    }

    @Test
    void cancelDeUmaTransacaoSoDeDadosNaoReconstroiOCatalogo() {
        run("DEFINE TABLE item SCHEMALESS");
        long antes = ds.catalogRebuilds();
        run("BEGIN");
        run("CREATE item CONTENT {a: 1}");
        run("CANCEL");
        assertEquals(antes, ds.catalogRebuilds());
        assertEquals(0, run("SELECT * FROM item").asArray().size());
    }

    @Test
    void falhaComDdlDentroDaProcedureAindaDesfazOCatalogo() {
        run("CREATE PROCEDURE ddl_e_falha() AS $$\ndb.exec(\"DEFINE TABLE efemera SCHEMALESS\")\nraise ValueError('falhou')\n$$");
        long antes = ds.catalogRebuilds();
        assertThrows(AxonError.class, () -> run("CALL ddl_e_falha()"));
        assertTrue(ds.catalogRebuilds() > antes, "DDL desfeito exige reconstruir o catálogo");
        AxonValue tables = run("INFO FOR DATABASE").asObject().get("tables");
        assertFalse(tables.toString().contains("efemera"), "a tabela criada na procedure que falhou não pode ficar");
    }

    @Test
    void cancelDepoisDeDdlNaTransacaoAindaDesfaz() {
        run("BEGIN");
        run("DEFINE TABLE temporaria SCHEMALESS");
        long antes = ds.catalogRebuilds();
        run("CANCEL");
        assertTrue(ds.catalogRebuilds() > antes);
        assertFalse(run("INFO FOR DATABASE").asObject().get("tables").toString().contains("temporaria"));
    }

    @Test
    void varioDesfazeresSeguidosNaoAcumulamCusto() {
        run("DEFINE TABLE item SCHEMALESS");
        run("CREATE PROCEDURE quebra() AS $$\ndb.exec(\"CREATE item CONTENT {a: 1}\")\nraise ValueError('falhou')\n$$");
        long antes = ds.catalogRebuilds();
        for (int i = 0; i < 20; i++) {
            assertThrows(AxonError.class, () -> run("CALL quebra()"));
        }
        assertEquals(antes, ds.catalogRebuilds());
    }
}
