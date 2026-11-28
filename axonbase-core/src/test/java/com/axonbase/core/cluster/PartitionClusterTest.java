package com.axonbase.core.cluster;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.ColdTier;
import com.axonbase.core.partition.PartitionMaintenance;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E4 em cluster: remoção por faixa, retenção, TTL e camada fria decididos no líder chegam iguais aos seguidores,
 * inclusive a um que estava fora.
 */
@DisplayName("Cluster: partições, TTL e camada fria")
class PartitionClusterTest {

    @TempDir
    Path dir;

    private static final long NOW = Instant.parse("2026-11-04T00:00:00Z").toEpochMilli();

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private Datastore datastore(ClusterRuntime node) {
        ColdTier cold = ColdTier.of(new FileSystemColdStore(dir.resolve("cold")),
            dir.resolve("cache-" + node.config().nodeId()), ColdTier.Settings.defaults());
        Datastore ds = new Datastore(node.backend(), cold);
        node.appliedBatchListener(ds.appliedBatchListener());
        ds.commitCoordinator(node, node.config().nodeId());
        return ds;
    }

    private static PartitionMaintenance maintenance(Datastore ds) {
        return new PartitionMaintenance(ds, PartitionMaintenance.Options.defaults().withTtlPause(Duration.ZERO),
            () -> NOW);
    }

    private static List<String> who(Datastore ds, String table) {
        AxonValue rows = ds.execute("SELECT * FROM " + table, session(), null);
        return rows.asArray().stream().map(r -> r.asObject().get("who").asString()).sorted().toList();
    }

    /**
     * O que veio do log. Cada motor grava ao subir, só na própria base, o marcador {@code EKM|} da migração de arestas;
     * um nó cujo motor sobe já com dados o tem e o líder não.
     */
    private static Map<String, String> replicated(ClusterRuntime node) {
        Map<String, String> data = ClusterHarness.data(node);
        data.keySet().removeIf(key -> key.startsWith("EKM|"));
        return data;
    }

    private static void awaitSameData(ClusterRuntime leader, ClusterRuntime other) {
        Map<String, String> expected = replicated(leader);
        try {
            ClusterHarness.await("nó " + other.config().nodeId() + " diferente do líder", 30_000,
                () -> replicated(other).equals(expected));
        } catch (AssertionError stillDifferent) {
            Map<String, String> actual = replicated(other);
            java.util.Set<String> keys = new java.util.TreeSet<>(expected.keySet());
            keys.addAll(actual.keySet());
            keys.removeIf(key -> java.util.Objects.equals(expected.get(key), actual.get(key)));
            throw new AssertionError(stillDifferent.getMessage() + "; chaves diferentes: "
                + keys.stream().limit(20).map(k -> k.replace('\0', '/')
                    + (expected.containsKey(k) ? "" : " (só no nó)")
                    + (actual.containsKey(k) ? "" : " (só no líder)")).toList());
        }
    }

    private static void event(Datastore ds, String id, int day) {
        ds.execute("CREATE events:" + id + " CONTENT {at: d\"2026-11-0" + day + "T10:00:00Z\", who: \"" + id + "\"}",
            session(), null);
    }

    @Test
    @DisplayName("seguidores ficam idênticos ao líder depois de remover, expirar e esfriar")
    void followersConverge() {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leaderNode = cluster.awaitLeader();
            Map<ClusterRuntime, Datastore> stores = new LinkedHashMap<>();
            cluster.nodes().forEach(n -> stores.put(n, datastore(n)));
            Datastore leader = stores.get(leaderNode);
            leader.execute("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d", session(), null);
            leader.execute("DEFINE TTL ON TABLE sessions FIELD expires", session(), null);
            event(leader, "ana", 1);
            event(leader, "bia", 2);
            event(leader, "caio", 3);
            leader.execute("CREATE sessions:old CONTENT {who: \"old\", expires: d\"2026-11-01T00:00:00Z\"}",
                session(), null);
            leader.execute("CREATE sessions:new CONTENT {who: \"new\", expires: d\"2027-01-01T00:00:00Z\"}",
                session(), null);
            ClusterRuntime followerNode = cluster.awaitFollower();
            Datastore follower = stores.get(followerNode);
            cluster.awaitConverged();
            ClusterHarness.await("seguidor sem as linhas", 10_000, () -> who(follower, "events").size() == 3);

            assertEquals(0, maintenance(follower).runOnce().expiredRows());
            leader.execute("DROP PARTITION ON TABLE events AT d\"2026-11-01T10:00:00Z\"", session(), null);
            leader.execute("ALTER PARTITION ON TABLE events AT d\"2026-11-02T10:00:00Z\" TO COLD", session(), null);
            PartitionMaintenance.Report report = maintenance(leader).runOnce();

            assertEquals(1, report.expiredRows());
            assertEquals(1, report.movedCold());
            assertEquals(0, report.failures());
            cluster.awaitConverged();
            cluster.nodes().forEach(node -> awaitSameData(leaderNode, node));
            assertEquals(List.of("bia", "caio"), who(follower, "events"));
            assertEquals(List.of("new"), who(follower, "sessions"));
        }
    }

    @Test
    @DisplayName("um seguidor que estava fora recebe a remoção quando volta")
    void restartedFollowerAppliesDrop() {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leaderNode = cluster.awaitLeader();
            Datastore leader = datastore(leaderNode);
            leader.execute("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d", session(), null);
            event(leader, "ana", 1);
            event(leader, "bia", 2);
            ClusterRuntime followerNode = cluster.awaitFollower();
            String id = followerNode.config().nodeId();
            cluster.awaitConverged();
            cluster.stop(id);

            leader.execute("DROP PARTITION ON TABLE events AT d\"2026-11-01T10:00:00Z\"", session(), null);
            event(leader, "volta", 1);
            ClusterRuntime back = cluster.start(id);
            Datastore follower = datastore(back);

            awaitSameData(leaderNode, back);
            ClusterHarness.await("catálogo do seguidor atrasado", 10_000, () -> {
                follower.reloadFromBackend();
                return who(follower, "events").equals(List.of("bia", "volta"));
            });
        }
    }
}
