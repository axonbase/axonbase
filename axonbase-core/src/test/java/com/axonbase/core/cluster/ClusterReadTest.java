package com.axonbase.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Cluster: leitura em seguidores")
class ClusterReadTest {

    @TempDir
    Path dir;

    private ClusterHarness cluster(RaftOptions options) {
        return ClusterHarness.memory(dir, options, "n1", "n2", "n3").startAll();
    }

    private static String read(ClusterRuntime node, String key) {
        return node.backend().get(key).map(v -> new String(v, StandardCharsets.UTF_8)).orElse(null);
    }

    private static void isolate(ClusterHarness cluster, ClusterRuntime survivor) {
        List<String> others = cluster.nodes().stream().filter(n -> n != survivor)
            .map(n -> n.config().nodeId()).toList();
        others.forEach(cluster::stop);
    }

    @Test
    @DisplayName("leitura linearizável num seguidor enxerga toda escrita já confirmada")
    void linearizableFollowerReadSeesEveryConfirmedWrite() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults())) {
            ClusterRuntime follower = cluster.awaitFollower();
            ClusterRuntime leader = cluster.awaitLeader();
            for (int i = 0; i < 200; i++) {
                write(leader, "k", "v" + i);
                follower.awaitRead(ReadConsistency.LINEARIZABLE);
                assertEquals("v" + i, read(follower, "k"), "leitura " + i);
            }
        }
    }

    @Test
    @DisplayName("leituras linearizáveis concorrentes nunca voltam no tempo")
    void concurrentLinearizableReadsAreMonotonic() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults())) {
            ClusterRuntime follower = cluster.awaitFollower();
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "n", "0");
            AtomicBoolean writing = new AtomicBoolean(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger confirmed = new java.util.concurrent.atomic.AtomicInteger();
            Thread writer = Thread.ofPlatform().start(() -> {
                for (int i = 1; i <= 300; i++) {
                    write(leader, "n", Integer.toString(i));
                    confirmed.set(i);
                }
                writing.set(false);
            });
            List<Thread> readers = java.util.stream.IntStream.range(0, 4).mapToObj(r -> Thread.ofPlatform()
                .start(() -> {
                    try {
                        while (writing.get()) {
                            int floor = confirmed.get();
                            follower.awaitRead(ReadConsistency.LINEARIZABLE);
                            int seen = Integer.parseInt(read(follower, "n"));
                            if (seen < floor) {
                                throw new AssertionError("leu " + seen + " depois de " + floor + " confirmado");
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                })).toList();
            writer.join(120_000);
            for (Thread reader : readers) {
                reader.join(30_000);
            }
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
        }
    }

    @Test
    @DisplayName("leitura linearizável no líder e num nó único é atendida")
    void linearizableOnLeaderAndSingleNode() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "solo").startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "k", "v");
            assertDoesNotThrow(() -> leader.awaitRead(ReadConsistency.LINEARIZABLE));
            assertDoesNotThrow(() -> leader.awaitRead(ReadConsistency.maxLag(1_000)));
        }
    }

    @Test
    @DisplayName("seguidor em dia atende o limite de defasagem; isolado, recusa depois do limite")
    void boundedStalenessRejectsIsolatedFollower() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults())) {
            ClusterRuntime follower = cluster.awaitFollower();
            write(cluster.awaitLeader(), "k", "v");
            assertDoesNotThrow(() -> follower.awaitRead(ReadConsistency.maxLag(1_000)));

            isolate(cluster, follower);
            Thread.sleep(600);

            StaleReadException stale = assertThrows(StaleReadException.class,
                () -> follower.awaitRead(ReadConsistency.maxLag(300)));
            org.junit.jupiter.api.Assertions.assertTrue(stale.getMessage().contains("300"), stale.getMessage());
            assertDoesNotThrow(() -> follower.awaitRead(ReadConsistency.STALE));
            assertDoesNotThrow(() -> follower.awaitRead(ReadConsistency.maxLag(60_000)));
            assertThrows(StaleReadException.class, () -> follower.awaitRead(ReadConsistency.LINEARIZABLE));
        }
    }

    @Test
    @DisplayName("um líder sem maioria não serve leitura linearizável nem dentro do limite")
    void isolatedLeaderRefusesFreshReads() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults())) {
            ClusterRuntime leader = cluster.awaitLeader();
            write(leader, "k", "v");
            isolate(cluster, leader);
            Thread.sleep(600);
            assertThrows(StaleReadException.class, () -> leader.awaitRead(ReadConsistency.LINEARIZABLE));
            assertThrows(StaleReadException.class, () -> leader.awaitRead(ReadConsistency.maxLag(300)));
        }
    }

    @Test
    @DisplayName("o limite configurado vale quando o cliente não pede nada")
    void configuredLimitIsTheDefault() throws Exception {
        try (ClusterHarness cluster = cluster(RaftOptions.defaults().withMaxReadLagMillis(300))) {
            ClusterRuntime follower = cluster.awaitFollower();
            write(cluster.awaitLeader(), "k", "v");
            assertDoesNotThrow(() -> follower.awaitRead(null));
            isolate(cluster, follower);
            Thread.sleep(600);
            assertThrows(StaleReadException.class, () -> follower.awaitRead(null));
        }
    }

    @Test
    @DisplayName("o texto pedido pelo cliente é interpretado e o inválido é recusado")
    void parsesRequestedConsistency() {
        assertNull(ReadConsistency.parse(null));
        assertNull(ReadConsistency.parse("  "));
        assertEquals(ReadConsistency.STALE, ReadConsistency.parse("STALE"));
        assertEquals(ReadConsistency.LINEARIZABLE, ReadConsistency.parse(" linearizable "));
        assertEquals(ReadConsistency.maxLag(250), ReadConsistency.parse("max-lag=250"));
        for (String invalid : List.of("max-lag=", "max-lag=-1", "max-lag=abc", "forte")) {
            assertThrows(IllegalArgumentException.class, () -> ReadConsistency.parse(invalid), invalid);
        }
    }
}
