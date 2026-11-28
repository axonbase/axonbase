package com.axonbase.core.cluster;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Em cluster o CREATE avulso é uma transação do líder: o atalho da ausência conferida no commit não entra. */
@DisplayName("Cluster: CREATE continua procurando o id antes de escrever")
class CreateAbsenceClusterTest {

    @TempDir
    Path dir;

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    @Test
    @DisplayName("o duplicado é recusado sem o atalho em nó algum")
    void clusterKeepsTheFullPath() {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            Datastore ds = new Datastore(leader.backend());
            leader.appliedBatchListener(ds.appliedBatchListener());
            ds.commitCoordinator(leader, leader.config().nodeId());
            ds.execute("CREATE p:ana SET nome = 'Ana'", session(), null);

            RuntimeException error = assertThrows(RuntimeException.class,
                () -> ds.execute("CREATE p:ana SET nome = 'Outra'", session(), null));

            assertTrue(error.getMessage().contains("p:ana"), error.getMessage());
            assertEquals("Ana", ds.execute("SELECT * FROM p:ana", session(), null).asObject().get("nome").asString());
            cluster.awaitConverged();
            cluster.nodes().forEach(node -> assertEquals(0,
                assertInstanceOf(RocksDbBackend.class, node.backend()).occupiedKeyRefusals()));
        }
    }
}
