package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RELATE registra o tipo de aresta no catálogo")
class RelateEdgeKindTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static AxonValue details(Datastore ds) {
        return ds.execute("INFO FOR DATABASE", session(), null).asObject().get("details");
    }

    private static boolean hasTable(Datastore ds, String name) {
        return details(ds).asObject().containsKey(name);
    }

    @Test
    @DisplayName("o primeiro RELATE cria a tabela do tipo e a travessia continua igual")
    void relateCreatesTheEdgeTable() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session();
        ds.execute("CREATE pessoa:ana CONTENT {nome: 'Ana'}; CREATE empresa:acme CONTENT {nome: 'Acme'}", s, null);
        assertFalse(hasTable(ds, "trabalha_em"));

        ds.execute("RELATE pessoa:ana->trabalha_em->empresa:acme", s, null);

        assertTrue(hasTable(ds, "trabalha_em"));
        assertEquals("empresa:acme", ds.execute("SELECT ->trabalha_em->empresa AS e FROM pessoa:ana", s, null)
            .asObject().get("e").toString());
        // aresta não é linha da tabela
        assertEquals(0, ds.execute("SELECT VALUE count() FROM trabalha_em", s, null).asLong());
    }

    @Test
    @DisplayName("repetir o RELATE não muda nada, e um tipo já declarado é preservado")
    void existingTableIsKept() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        Session s = session();
        ds.execute("CREATE pessoa:ana CONTENT {nome: 'Ana'}", s, null);
        ds.execute("DEFINE TABLE segue SCHEMAFULL", s, null);
        ds.execute("RELATE pessoa:ana->segue->pessoa:bia", s, null);
        ds.execute("RELATE pessoa:ana->segue->pessoa:caio", s, null);
        ds.execute("RELATE pessoa:ana->segue->pessoa:caio", s, null);

        AxonValue segue = details(ds).asObject().get("segue");
        assertEquals("SCHEMAFULL", segue.asObject().get("schema").asString());
        assertEquals(2, ds.execute("SELECT ->segue->pessoa AS s FROM pessoa:ana", s, null)
            .asObject().get("s").asArray().size());
    }

    @Test
    @DisplayName("o tipo registrado sobrevive ao reinício (replay do plano de controle)")
    void registeredKindSurvivesRestart() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        first.createDatabase("test", "dev");
        first.execute("CREATE pessoa:ana CONTENT {nome: 'Ana'}", session(), null);
        first.execute("RELATE pessoa:ana->segue->pessoa:bia", session(), null);

        Datastore second = new Datastore(backend);
        assertTrue(hasTable(second, "segue"));
        assertEquals("pessoa:bia", second.execute("SELECT ->segue->pessoa AS s FROM pessoa:ana", session(), null)
            .asObject().get("s").toString());
    }

    @Test
    @DisplayName("grafos criados antes da correção ganham os tipos na subida, uma vez")
    void legacyEdgesAreRegisteredOnStartup() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        first.createDatabase("test", "dev");
        // arestas como as versões antigas gravavam: só as chaves E|..., sem tabela no catálogo
        backend.put("E|test|dev|out|pessoa|sana|segue|pessoa|sbia", new byte[0]);
        backend.put("E|test|dev|out|pessoa|sana|trabalha_em|empresa|sacme", new byte[0]);
        backend.put("E|test|dev|in|pessoa|sbia|segue|pessoa|sana", new byte[0]);
        assertFalse(hasTable(first, "segue"));

        Datastore second = new Datastore(backend);
        assertTrue(hasTable(second, "segue"));
        assertTrue(hasTable(second, "trabalha_em"));
        assertTrue(backend.get("EKM|test|dev").isPresent(), "marca que o banco já foi varrido");

        // depois do marcador a varredura não roda de novo: uma aresta antiga nova não ganha tipo sozinha
        backend.put("E|test|dev|out|pessoa|sana|outro_tipo|pessoa|sbia", new byte[0]);
        Datastore third = new Datastore(backend);
        assertFalse(hasTable(third, "outro_tipo"));
        assertTrue(hasTable(third, "segue"));
        assertTrue(hasTable(third, "trabalha_em"));
    }

    @Test
    @DisplayName("a migração ignora nomes que não relêem como tabela simples")
    void migrationSkipsOddNames() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        first.createDatabase("test", "dev");
        backend.put("E|test|dev|out|pessoa|sana|meu tipo|pessoa|sbia", new byte[0]);
        backend.put("E|test|dev|out|pessoa|sana|ok_tipo|pessoa|sbia", new byte[0]);

        Datastore second = new Datastore(backend);
        assertTrue(hasTable(second, "ok_tipo"));
        assertFalse(hasTable(second, "meu tipo"));
    }
}
