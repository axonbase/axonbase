package com.axonbase.core.shard;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.Render;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: cláusula SHARD BY no parser e no catálogo")
class ShardByClauseTest {

    private static Statement.DefineTable parse(String sql) {
        return (Statement.DefineTable) AxonQl.parse(sql).statements().get(0);
    }

    private static Session session() {
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        return session;
    }

    @Test
    void clausulaVemDepoisDoEsquemaEAntesDasPermissoes() {
        assertEquals("cliente_id", parse("DEFINE TABLE pedido SCHEMALESS SHARD BY cliente_id").shardBy());
        assertEquals("id", parse("DEFINE TABLE evento SHARD BY id").shardBy());
        Statement.DefineTable full = parse(
            "DEFINE TABLE pedido SCHEMAFULL SHARD BY cliente_id PERMISSIONS FOR select WHERE true");
        assertTrue(full.schemafull());
        assertEquals("cliente_id", full.shardBy());
        assertNull(parse("DEFINE TABLE pedido SCHEMALESS").shardBy());
    }

    @Test
    void clausulaIncompletaNaoPassa() {
        assertThrows(RuntimeException.class, () -> AxonQl.parse("DEFINE TABLE pedido SHARD cliente_id"));
        assertThrows(RuntimeException.class, () -> AxonQl.parse("DEFINE TABLE pedido SHARD BY"));
    }

    @Test
    void renderizacaoIdaEVoltaPreservaAClausula() {
        Statement.DefineTable original = parse("DEFINE TABLE pedido SCHEMALESS SHARD BY cliente_id");

        assertEquals(original, parse(Render.stmt(original)));
    }

    @Test
    void catalogoGuardaEMostraOCampo() {
        Datastore datastore = new Datastore(new MemoryBackend());
        datastore.execute("DEFINE TABLE pedido SCHEMALESS SHARD BY cliente_id", session(), null);

        assertEquals("cliente_id", datastore.findDatabase("n", "d").catalog().table("pedido").shardBy());
        AxonValue info = datastore.execute("INFO FOR TABLE pedido", session(), null);
        assertEquals(AxonValue.str("cliente_id"), info.asObject().get("shard_by"));
        assertTrue(datastore.exportDatabase("n", "d").contains("SHARD BY cliente_id"));
    }

    @Test
    void noUnicoAClausulaNaoMudaAExecucao() {
        Datastore datastore = new Datastore(new MemoryBackend());
        datastore.execute("DEFINE TABLE pedido SHARD BY cliente_id", session(), null);

        // sem a chave de shard a escrita continua valendo num nó só
        datastore.execute("CREATE pedido:1 SET total = 10", session(), null);

        assertEquals(AxonValue.num(10),
            datastore.execute("SELECT * FROM pedido:1", session(), null).asObject().get("total"));
    }

    @Test
    void definicaoSobreviveAoReinicioPeloPlanoDeControle() {
        MemoryBackend backend = new MemoryBackend();
        new Datastore(backend).execute("DEFINE TABLE pedido SHARD BY cliente_id", session(), null);

        Datastore reopened = new Datastore(backend);

        assertEquals("cliente_id", reopened.findDatabase("n", "d").catalog().table("pedido").shardBy());
    }
}
