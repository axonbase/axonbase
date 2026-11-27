package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SAGA: a definição persiste e operar sobre SAGA inexistente dá erro")
class SagaDefinitionPersistenceTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return s;
    }

    private static AxonValue run(Datastore ds, String sql) {
        return ds.execute(sql, session(), null);
    }

    private static String describe(Datastore ds, String saga) {
        return run(ds, "DESCRIBE SAGA " + saga).asObject().get("links").asArray().get(0).asString();
    }

    @Test
    void definicaoSobreviveAoReinicio() {
        MemoryBackend backend = new MemoryBackend();
        run(new Datastore(backend), "CREATE SAGA s1 WITH DATABASES 'p1'");

        assertEquals("p1", describe(new Datastore(backend), "s1"));
    }

    @Test
    void nomeEntreAspasSobreviveAoReinicio() {
        MemoryBackend backend = new MemoryBackend();
        run(new Datastore(backend), "CREATE SAGA 'pedido-v2' WITH DATABASES 'p1'");

        assertEquals("p1", describe(new Datastore(backend), "'pedido-v2'"));
    }

    @Test
    void definicaoRemovidaNaoVoltaNoReinicio() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        run(first, "CREATE SAGA s1 WITH DATABASES 'p1'");
        run(first, "DROP SAGA s1");

        Datastore second = new Datastore(backend);
        assertThrows(AxonError.class, () -> run(second, "DESCRIBE SAGA s1"));
    }

    @Test
    void definicaoVaiNoBackupENaRestauracao() {
        Datastore source = Datastore.memory();
        run(source, "CREATE SAGA s1 WITH DATABASES 'p1'");
        Datastore target = Datastore.memory();
        run(target, "CREATE SAGA velha WITH DATABASES 'p9'");

        target.restoreCluster(source.backupCluster());

        assertEquals("p1", describe(target, "s1"));
        assertThrows(AxonError.class, () -> run(target, "DESCRIBE SAGA velha"));
    }

    @Test
    void exportacaoLevaASagaDasLigacoesDoNamespace() {
        Datastore ds = Datastore.memory();
        run(ds, "DEFINE DATABASE LINK \"p1\" CONNECT BY \"http://127.0.0.1:9\" "
            + "WITH ns = \"t\" db = \"p\" user = \"\" password = \"\"");
        run(ds, "CREATE SAGA s1 WITH DATABASES 'p1'");
        run(ds, "CREATE item:1 SET q = 1");

        String dump = ds.exportDatabase("t", "d");

        assertTrue(dump.contains("CREATE SAGA s1 WITH DATABASES 'p1'"), dump);
    }

    @Test
    void removerSagaInexistenteDaErro() {
        Datastore ds = Datastore.memory();
        assertThrows(AxonError.class, () -> run(ds, "DROP SAGA nunca"));
    }

    @Test
    void cancelarComOutroNomeDaErro() {
        Datastore ds = Datastore.memory();
        run(ds, "BEGIN SAGA pedido WITH CORRELATION 'c1'");

        assertThrows(AxonError.class, () -> run(ds, "CANCEL SAGA outra WITH CORRELATION 'c1'"));
        assertThrows(AxonError.class, () -> run(ds, "COMMIT SAGA outra WITH CORRELATION 'c1'"));
        assertThrows(AxonError.class, () -> run(ds, "JOIN SAGA outra WITH CORRELATION 'c1'"));
        assertEquals("FAILED", run(ds, "CANCEL SAGA pedido WITH CORRELATION 'c1'")
            .asObject().get("status").asString());
    }

    @Test
    void semADefinicaoUsadaNoBeginNaoHaSucessoSemCompensar() throws Exception {
        try (FakeParticipant participant = FakeParticipant.start()) {
            Datastore ds = Datastore.memory();
            run(ds, "DEFINE DATABASE LINK \"p1\" CONNECT BY \"" + participant.url()
                + "\" WITH ns = \"t\" db = \"p\" user = \"\" password = \"\"");
            run(ds, "CREATE SAGA s1 WITH DATABASES 'p1'");
            run(ds, "BEGIN SAGA s1 WITH CORRELATION 'r1'");
            run(ds, "DROP SAGA s1");

            assertThrows(AxonError.class, () -> run(ds, "CANCEL SAGA s1 WITH CORRELATION 'r1'"));
            assertThrows(AxonError.class, () -> run(ds, "COMMIT SAGA s1 WITH CORRELATION 'r1'"));

            run(ds, "CREATE SAGA s1 WITH DATABASES 'p1'");
            assertEquals("FAILED", run(ds, "CANCEL SAGA s1 WITH CORRELATION 'r1'")
                .asObject().get("status").asString());
            assertTrue(participant.received().stream().anyMatch(q -> q.startsWith("CANCEL SAGA s1")),
                participant.received().toString());
        }
    }
}
