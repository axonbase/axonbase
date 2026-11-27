package com.axonbase.core.cluster;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.VersionConflictException;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: transações concorrentes no datastore replicado")
class ClusterDatastoreConcurrencyTest {

    @TempDir
    Path dir;

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static Map<String, Datastore> datastores(ClusterHarness cluster) {
        Map<String, Datastore> out = new LinkedHashMap<>();
        for (ClusterRuntime node : cluster.nodes()) {
            String id = node.config().nodeId();
            Datastore ds = new Datastore(node.backend());
            node.appliedBatchListener(ds.appliedBatchListener());
            ds.commitCoordinator(node, id);
            out.put(id, ds);
        }
        return out;
    }

    /** Executa a escrita, refazendo quando outra transação passou na frente. */
    private static int executeRetrying(Datastore ds, String sql) {
        for (int conflicts = 0; ; conflicts++) {
            try {
                ds.execute(sql, session(), null);
                return conflicts;
            } catch (VersionConflictException retry) {
                if (conflicts > 500) {
                    throw retry;
                }
            }
        }
    }

    private static void concurrently(int threads, int each, java.util.function.IntBinaryOperator work)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int worker = t;
            tasks.add(pool.submit(() -> {
                for (int i = 0; i < each; i++) {
                    work.applyAsInt(worker, i);
                }
            }));
        }
        for (Future<?> task : tasks) {
            task.get(180, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    @Test
    @DisplayName("incrementos concorrentes no mesmo registro não se perdem e todos os nós ficam iguais")
    void concurrentIncrementsAreNotLost() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leaderNode = cluster.awaitLeader();
            Datastore leader = datastores(cluster).get(leaderNode.config().nodeId());
            leader.execute("DEFINE TABLE c; DEFINE INDEX por_n ON TABLE c COLUMNS n", session(), null);
            leader.execute("CREATE c:1 CONTENT { n: 0 }", session(), null);

            concurrently(8, 20, (worker, i) -> executeRetrying(leader, "UPDATE c:1 SET n = n + 1"));

            AxonValue rows = leader.execute("SELECT * FROM c", session(), null);
            assertEquals(160, rows.asArray().getFirst().asObject().get("n").asLong());
            cluster.awaitConverged();
            Map<String, String> expected = ClusterHarness.data(leaderNode);
            cluster.nodes().forEach(n -> assertEquals(expected, ClusterHarness.data(n), n.config().nodeId()));
        }
    }

    @Test
    @DisplayName("comandos avulsos concorrentes não devolvem conflito ao cliente, nem ao criar a tabela")
    void autocommitStatementsDoNotSurfaceConflicts() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leaderNode = cluster.awaitLeader();
            Datastore leader = datastores(cluster).get(leaderNode.config().nodeId());

            // A tabela não existe: cada primeira inserção também grava o catálogo.
            concurrently(16, 5, (worker, i) -> {
                leader.execute("CREATE nova:n" + worker + "x" + i + " CONTENT { v: 1 }", session(), null);
                return 0;
            });
            leader.execute("CREATE c:1 CONTENT { n: 0 }", session(), null);
            concurrently(8, 10, (worker, i) -> {
                leader.execute("UPDATE c:1 SET n = n + 1", session(), null);
                return 0;
            });
            concurrently(8, 5, (worker, i) -> {
                leader.kvSet(kv(), "n", "d", "contador", AxonValue.num(i), null);
                return 0;
            });

            assertEquals(80, leader.execute("SELECT * FROM nova", session(), null).asArray().size());
            assertEquals(80, leader.execute("SELECT * FROM c", session(), null).asArray().getFirst()
                .asObject().get("n").asLong());
            cluster.awaitConverged();
            Map<String, String> expected = ClusterHarness.data(leaderNode);
            cluster.nodes().forEach(n -> assertEquals(expected, ClusterHarness.data(n), n.config().nodeId()));
        }
    }

    private static Session kv() {
        return session();
    }

    @Test
    @DisplayName("UNIQUE construído sobre dados: uma escrita aceita por valor e as mesmas chaves em todos os nós")
    void uniqueIndexBuiltOverDataIsTheSameOnEveryNode() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leaderNode = cluster.awaitLeader();
            Datastore leader = datastores(cluster).get(leaderNode.config().nodeId());
            leader.execute("DEFINE TABLE u SCHEMALESS", session(), null);
            for (int i = 0; i < 20; i++) {
                leader.execute("CREATE u:" + i + " CONTENT { email: 'u" + i + "' }", session(), null);
            }
            leader.execute("DEFINE INDEX email ON TABLE u COLUMNS email UNIQUE", session(), null);

            concurrently(8, 5, (worker, i) -> {
                try {
                    leader.execute("CREATE u:w" + worker + "x" + i + " CONTENT { email: 'v" + i + "' }",
                        session(), null);
                } catch (RuntimeException refused) {
                    // recusado: é o esperado para todos menos um de cada valor
                }
                return 0;
            });

            assertEquals(25, leader.execute("SELECT * FROM u", session(), null).asArray().size());
            cluster.awaitConverged();
            Map<String, String> expected = ClusterHarness.data(leaderNode);
            assertEquals(25, expected.keySet().stream().filter(k -> k.startsWith("UQL|")).count());
            cluster.nodes().forEach(n -> assertEquals(expected, ClusterHarness.data(n), n.config().nodeId()));
        }
    }

    @Test
    @DisplayName("PRIMARY KEY: uma escrita aceita por valor em escritas simultâneas")
    void concurrentPrimaryKeyValuesAreAcceptedOnce() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leaderNode = cluster.awaitLeader();
            Datastore leader = datastores(cluster).get(leaderNode.config().nodeId());
            leader.execute("CREATE TABLE p (email STRING PRIMARY KEY)", session(), null);

            concurrently(8, 20, (worker, i) -> {
                try {
                    leader.execute("CREATE p:w" + worker + "x" + i + " SET email = 'v" + i + "'", session(), null);
                } catch (RuntimeException refused) {
                    // recusado: é o esperado para todos menos um de cada valor
                }
                return 0;
            });

            assertEquals(20, leader.execute("SELECT * FROM p", session(), null).asArray().size());
            cluster.awaitConverged();
            Map<String, String> expected = ClusterHarness.data(leaderNode);
            cluster.nodes().forEach(n -> assertEquals(expected, ClusterHarness.data(n), n.config().nodeId()));
        }
    }

    @Test
    @DisplayName("inserções concorrentes de registros diferentes não conflitam entre si")
    void concurrentInsertsDoNotConflict() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leaderNode = cluster.awaitLeader();
            Datastore leader = datastores(cluster).get(leaderNode.config().nodeId());
            leader.execute("DEFINE TABLE pedido; DEFINE INDEX por_loja ON TABLE pedido COLUMNS loja",
                session(), null);
            AtomicInteger conflicts = new AtomicInteger();

            concurrently(8, 25, (worker, i) -> conflicts.addAndGet(executeRetrying(leader,
                "CREATE pedido:p" + worker + "x" + i + " CONTENT { loja: " + worker + " }")));

            assertEquals(200, leader.execute("SELECT * FROM pedido", session(), null).asArray().size());
            assertEquals(25, leader.execute("SELECT * FROM pedido WHERE loja = 3", session(), null)
                .asArray().size());
            assertTrue(conflicts.get() == 0, "inserções independentes conflitaram " + conflicts.get() + " vezes");
            cluster.awaitConverged();
            Map<String, String> expected = ClusterHarness.data(leaderNode);
            cluster.nodes().forEach(n -> assertEquals(expected, ClusterHarness.data(n), n.config().nodeId()));
        }
    }
}
