package com.axonbase.core.cluster;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: carimbo do relógio híbrido na replicação")
class ClusterClockTest {

    /** Relógios dos nós em relação à hora real: um certo, um 300 ms adiantado e um 300 ms atrasado. */
    private static final Map<String, Long> SKEWS = Map.of("n1", 0L, "n2", 300L, "n3", -300L);

    @TempDir
    Path dir;

    private static HybridClock skewed(long skewMillis) {
        return new HybridClock(() -> System.currentTimeMillis() + skewMillis, HybridClock.DEFAULT_MAX_DRIFT_MS,
            event -> { });
    }

    /** O que todos os nós confirmaram, por índice do log, e quem discordou. */
    private static final class Ledger {
        final ConcurrentSkipListMap<Long, HlcTimestamp> byIndex = new ConcurrentSkipListMap<>();
        final ConcurrentLinkedQueue<String> disagreements = new ConcurrentLinkedQueue<>();

        ReplicationHooks recording(String node, ReplicationHooks hooks) {
            return new ReplicationHooks() {
                @Override
                public void recovered(CommittedBatch batch) {
                    hooks.recovered(batch);
                }

                @Override
                public CommittedBatch stamp(CommittedBatch batch) {
                    return hooks.stamp(batch);
                }

                @Override
                public boolean accept(CommittedBatch batch) {
                    return hooks.accept(batch);
                }

                @Override
                public void committed(long index, long term, CommittedBatch batch) {
                    HlcTimestamp other = byIndex.putIfAbsent(index, batch.stamp());
                    if (other != null && !other.equals(batch.stamp())) {
                        disagreements.add(node + " índice " + index + ": " + batch.stamp() + " e " + other);
                    }
                }
            };
        }
    }

    private static void start(ClusterHarness cluster, Ledger ledger, String id) {
        cluster.start(id).hooks(ledger.recording(id, new ClockHooks(skewed(SKEWS.get(id)))));
    }

    /** Escreve com o líder da vez; durante uma troca o desfecho pode ser desconhecido, então tenta de novo. */
    private static void writeThroughLeader(ClusterHarness cluster, String key) {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            try {
                write(cluster.awaitLeader(), key, key);
                return;
            } catch (RuntimeException notConfirmed) {
                if (System.nanoTime() > deadline) {
                    throw notConfirmed;
                }
            }
        }
    }

    @Test
    @DisplayName("portão 2: trocas de líder com relógios 300 ms adiantado e atrasado mantêm a ordem de carimbo "
        + "igual à de índice")
    void leaderChangesWithSkewedClocksKeepStampOrder() throws Exception {
        Ledger ledger = new Ledger();
        Set<String> leaders = new LinkedHashSet<>();
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")) {
            SKEWS.keySet().stream().sorted().forEach(id -> start(cluster, ledger, id));
            int written = 0;
            for (int round = 0; round < 6; round++) {
                for (int i = 0; i < 25; i++) {
                    writeThroughLeader(cluster, "k" + written++);
                }
                String leader = cluster.awaitLeader().config().nodeId();
                leaders.add(leader);
                cluster.stop(leader);
                for (int i = 0; i < 10; i++) {
                    writeThroughLeader(cluster, "k" + written++);
                }
                leaders.add(cluster.awaitLeader().config().nodeId());
                start(cluster, ledger, leader);
                cluster.awaitConverged();
            }
        }

        assertTrue(leaders.size() >= 2, "o teste precisa ter trocado de líder: " + leaders);
        assertEquals(List.of(), List.copyOf(ledger.disagreements));
        assertTrue(ledger.byIndex.size() >= 6 * 35, "lotes confirmados: " + ledger.byIndex.size());
        HlcTimestamp previous = HlcTimestamp.NONE;
        List<String> violations = new ArrayList<>();
        for (var entry : ledger.byIndex.entrySet()) {
            if (entry.getValue().compareTo(previous) <= 0) {
                violations.add("índice " + entry.getKey() + ": " + entry.getValue() + " não passa de " + previous);
            }
            previous = entry.getValue();
        }
        assertEquals(List.of(), violations, "líderes " + leaders);
    }

    @Test
    @DisplayName("quem recebe um snapshot passa a emitir acima do carimbo dele, mesmo com o relógio atrasado")
    void aSnapshotCarriesTheClockForward() throws Exception {
        HybridClock sourceClock = skewed(300);
        HybridClock targetClock = skewed(-100);
        try (RaftNode source = new RaftNode("a", dir.resolve("a"), new MemoryBackend());
             RaftNode target = new RaftNode("b", dir.resolve("b"), new MemoryBackend())) {
            source.hooks(new ClockHooks(sourceClock));
            target.hooks(new ClockHooks(targetClock));
            source.appendLocal(1, 1, ClusterHarness.put("um", "1"));
            source.commitLocal(1, "a", 1);
            RaftNode.Image image = source.image();
            assertFalse(image.batch().stamp().isNone(), "o líder carimba o lote do snapshot");
            RaftWire.Request request = new RaftWire.Request(RaftWire.SNAPSHOT, "g", 1, "a", image.index(), 1,
                RaftPayload.encode(image.batch()));

            await("o snapshot não foi aceito", 30_000, () -> target.handle(request).accepted());

            assertTrue(targetClock.now().compareTo(image.batch().stamp()) > 0);
        }
    }

    @Test
    @DisplayName("um lote além do desvio máximo é recusado e contado, e o relógio não é puxado por ele")
    void aBatchTooFarAheadIsRefused() {
        HybridClock clock = skewed(0);
        ClockHooks hooks = new ClockHooks(clock);
        CommittedBatch ahead = ClusterHarness.put("k", "v")
            .withStamp(new HlcTimestamp(System.currentTimeMillis() + 60_000, 0));

        assertFalse(hooks.accept(ahead));

        assertEquals(1, clock.rejectedCount());
        assertTrue(clock.now().compareTo(ahead.stamp()) < 0);
    }

    @Test
    @DisplayName("o líder carimba de novo um lote que já traga carimbo, e o arranque parte da cauda do log")
    void stampingFollowsTheLogOrder() {
        HybridClock clock = skewed(0);
        ClockHooks hooks = new ClockHooks(clock);
        HlcTimestamp tail = new HlcTimestamp(System.currentTimeMillis() + 10_000, 7);

        hooks.recovered(ClusterHarness.put("cauda", "v").withStamp(tail));
        CommittedBatch stale = ClusterHarness.put("k", "v").withStamp(new HlcTimestamp(1, 0));
        CommittedBatch first = hooks.stamp(stale);
        CommittedBatch second = hooks.stamp(ClusterHarness.put("k2", "v"));

        assertTrue(first.stamp().compareTo(tail) > 0);
        assertTrue(second.stamp().compareTo(first.stamp()) > 0);
        assertEquals(stale.puts().keySet(), first.puts().keySet());
    }
}
