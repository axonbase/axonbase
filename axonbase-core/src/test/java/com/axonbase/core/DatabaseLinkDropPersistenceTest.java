package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("DATABASE LINK: a remoção persiste")
class DatabaseLinkDropPersistenceTest {

    private static final String CREATE = "CREATE DATABASE LINK \"some\" CONNECT BY \"http://127.0.0.1:9\" "
        + "WITH ns = \"t\" db = \"r\" user = \"root\" password = \"segredo-de-teste\"";

    private static void run(Datastore ds, String sql) {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        ds.execute(sql, s, null);
    }

    @Test
    void ligacaoRemovidaNaoVoltaNoReinicio() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        run(first, CREATE);
        run(first, "DROP DATABASE LINK \"some\"");

        assertNull(new Datastore(backend).databaseLink("t", "some"));
    }

    @Test
    void ligacaoRecriadaDepoisDaRemocaoSobrevive() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        run(first, CREATE);
        run(first, "DROP DATABASE LINK \"some\"");
        run(first, CREATE);

        assertNotNull(new Datastore(backend).databaseLink("t", "some"));
    }

    @Test
    void restaurarBackupSemALigacaoTiraALigacaoDaMemoria() {
        Datastore source = Datastore.memory();
        run(source, "CREATE item:1 SET q = 1");
        Datastore target = Datastore.memory();
        run(target, CREATE);

        target.restoreCluster(source.backupCluster());

        assertNull(target.databaseLink("t", "some"));
    }
}
