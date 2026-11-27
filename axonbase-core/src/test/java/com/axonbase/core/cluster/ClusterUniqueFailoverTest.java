package com.axonbase.core.cluster;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRIMARY KEY e UNIQUE depois de uma troca de líder, quando as entradas do novo líder não batem com os registros.
 * O estado deixado por um líder da rc1 (que grava o registro sem as entradas da chave) é reproduzido com um lote
 * replicado direto no consenso.
 */
@DisplayName("Cluster: chave primária e UNIQUE depois da troca de líder")
class ClusterUniqueFailoverTest {

    @TempDir
    Path dir;

    private final Map<String, Datastore> stores = new LinkedHashMap<>();

    @AfterEach
    void releaseBuilds() throws InterruptedException {
        IndexBuilder.chunkHook(null);
        IndexBuilder.awaitAll(30_000);
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private ClusterHarness start() {
        ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3").startAll();
        for (ClusterRuntime node : cluster.nodes()) {
            String id = node.config().nodeId();
            Datastore ds = new Datastore(node.backend());
            node.appliedBatchListener(ds.appliedBatchListener());
            ds.commitCoordinator(node, id);
            stores.put(id, ds);
        }
        return cluster;
    }

    private Datastore store(ClusterRuntime node) {
        return stores.get(node.config().nodeId());
    }

    private static void run(Datastore ds, String sql) {
        ds.execute(sql, session(), null);
    }

    private static Set<String> keys(ClusterRuntime node, String table, boolean entries) {
        String prefix = "n\u0000d\u0000" + table + "\u0000";
        Set<String> out = new LinkedHashSet<>();
        for (String key : node.backend().keysWithPrefix(prefix)) {
            if ((key.charAt(prefix.length()) == '!') == entries) {
                out.add(key);
            }
        }
        return out;
    }

    /** Apaga chaves pelo consenso sem passar pelo motor, em todos os nós. */
    private static void replicateDelete(ClusterRuntime leader, Set<String> keys) {
        assertFalse(keys.isEmpty());
        leader.confirm(leader.config().nodeId(),
            new CommittedBatch("fora-do-motor-" + System.nanoTime(), Map.of(), keys));
    }

    private ClusterRuntime failover(ClusterHarness cluster, ClusterRuntime leader) {
        cluster.awaitConverged();
        cluster.stop(leader.config().nodeId());
        return cluster.awaitLeader();
    }

    private static void assertSameData(ClusterHarness cluster, ClusterRuntime leader) {
        cluster.awaitConverged();
        Map<String, String> expected = ClusterHarness.data(leader);
        cluster.nodes().forEach(n -> assertEquals(expected, ClusterHarness.data(n), n.config().nodeId()));
    }

    @Test
    @DisplayName("registro gravado sem entrada (líder rc1): o novo líder recusa a chave repetida")
    void newLeaderRefusesKeyWrittenWithoutEntry() {
        try (ClusterHarness cluster = start()) {
            ClusterRuntime first = cluster.awaitLeader();
            run(store(first), "CREATE TABLE p (email STRING PRIMARY KEY)");
            run(store(first), "CREATE p:1 SET email = 'a'");
            replicateDelete(first, keys(first, "p", true));

            ClusterRuntime next = failover(cluster, first);

            RuntimeException refused = assertThrows(RuntimeException.class,
                () -> run(store(next), "CREATE p:2 SET email = 'a'"));
            assertTrue(refused.getMessage().contains("(email)"), refused.getMessage());
            assertDoesNotThrow(() -> run(store(next), "CREATE p:3 SET email = 'b'"));
            assertSameData(cluster, next);
        }
    }

    @Test
    @DisplayName("registro apagado sem a entrada (líder rc1): o novo líder aceita o valor liberado")
    void newLeaderAcceptsReleasedKey() {
        try (ClusterHarness cluster = start()) {
            ClusterRuntime first = cluster.awaitLeader();
            run(store(first), "CREATE TABLE p (email STRING PRIMARY KEY)");
            run(store(first), "CREATE p:1 SET email = 'a'");
            replicateDelete(first, keys(first, "p", false));

            ClusterRuntime next = failover(cluster, first);

            assertDoesNotThrow(() -> run(store(next), "CREATE p:2 SET email = 'a'"));
            assertThrows(RuntimeException.class, () -> run(store(next), "CREATE p:3 SET email = 'a'"));
            assertSameData(cluster, next);
        }
    }

    @Test
    @DisplayName("entrada UNIQUE sem registro: o valor volta a ser aceito, uma única vez")
    void staleUniqueEntryDoesNotHoldTheValue() {
        try (ClusterHarness cluster = start()) {
            ClusterRuntime leader = cluster.awaitLeader();
            run(store(leader), "DEFINE TABLE u SCHEMALESS");
            run(store(leader), "DEFINE INDEX email ON TABLE u COLUMNS email UNIQUE");
            run(store(leader), "CREATE u:1 CONTENT { email: 'a' }");
            replicateDelete(leader, keys(leader, "u", false));

            assertDoesNotThrow(() -> run(store(leader), "CREATE u:2 CONTENT { email: 'a' }"));
            assertThrows(RuntimeException.class, () -> run(store(leader), "CREATE u:3 CONTENT { email: 'a' }"));
            assertSameData(cluster, leader);
        }
    }

    @Test
    @DisplayName("UNIQUE ainda em construção no novo líder: não aceita valor sem poder conferir")
    void resumedUniqueBuildRefusesWritesUntilReady() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        try (ClusterHarness cluster = start()) {
            ClusterRuntime first = cluster.awaitLeader();
            run(store(first), "DEFINE TABLE u SCHEMALESS");
            run(store(first), "CREATE u:1 CONTENT { email: 'a' }");
            store(first).findDatabase("n", "d").indexInlineRows(0);
            IndexBuilder.chunkHook(() -> {
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            run(store(first), "DEFINE INDEX email ON TABLE u COLUMNS email UNIQUE");

            ClusterRuntime next = failover(cluster, first);
            ClusterHarness.await("o novo líder não recebeu o índice", 30_000, () -> {
                var db = store(next).findDatabase("n", "d");
                var table = db == null ? null : db.catalog().table("u");
                return table != null && table.indexes().containsKey("email");
            });

            // a linha antiga ainda não foi varrida: aceitar aqui criaria a duplicata
            assertThrows(RuntimeException.class, () -> run(store(next), "CREATE u:2 CONTENT { email: 'a' }"));

            release.countDown();
            assertTrue(IndexBuilder.awaitAll(30_000));
            assertThrows(RuntimeException.class, () -> run(store(next), "CREATE u:3 CONTENT { email: 'a' }"));
            assertDoesNotThrow(() -> run(store(next), "CREATE u:4 CONTENT { email: 'b' }"));
        } finally {
            release.countDown();
        }
    }
}
