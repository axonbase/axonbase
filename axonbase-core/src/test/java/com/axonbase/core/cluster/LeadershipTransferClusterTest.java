package com.axonbase.core.cluster;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: transferência de liderança")
@Timeout(180)
class LeadershipTransferClusterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static String idOf(ClusterRuntime node) {
        return node.config().nodeId();
    }

    private static String raftAddress(ClusterRuntime node) {
        return RaftWire.advertise(node.config().advertiseAddress());
    }

    private static ClusterRuntime currentLeader(ClusterHarness cluster) {
        return cluster.nodes().stream().filter(n -> n.role() == ClusterRole.LEADER).findFirst().orElse(null);
    }

    /**
     * Um cliente que segue o contrato de repetição: só repete quando a escrita certamente não aconteceu, e conta
     * qualquer outra falha como erro visível.
     */
    private static Thread client(ClusterHarness cluster, AtomicBoolean running, Consumer<ClusterRuntime> write,
                                 Queue<Throwable> visible) {
        return Thread.ofPlatform().start(() -> {
            while (running.get()) {
                ClusterRuntime leader = currentLeader(cluster);
                if (leader == null) {
                    sleep(5);
                    continue;
                }
                try {
                    write.accept(leader);
                } catch (NotLeaderException notExecuted) {
                    sleep(5);
                } catch (RuntimeException failure) {
                    visible.add(failure);
                    return;
                }
            }
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("três transferências sob escrita não produzem erro visível nem perdem escrita")
    void transfersUnderLoadAreInvisible() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime first = cluster.awaitLeader();
            Map<String, Datastore> engines = new ConcurrentHashMap<>();
            for (ClusterRuntime node : cluster.nodes()) {
                Datastore ds = new Datastore(node.backend());
                node.appliedBatchListener(ds.appliedBatchListener());
                ds.commitCoordinator(node, idOf(node));
                engines.put(idOf(node), ds);
            }
            engines.get(idOf(first)).execute("DEFINE TABLE item", session(), null);

            AtomicBoolean running = new AtomicBoolean(true);
            Queue<Throwable> visible = new ConcurrentLinkedQueue<>();
            Set<String> sql = ConcurrentHashMap.newKeySet();
            Set<String> raw = ConcurrentHashMap.newKeySet();
            AtomicInteger sequence = new AtomicInteger();
            List<Thread> clients = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ThreadLocal<String> pending = new ThreadLocal<>();
                clients.add(client(cluster, running, leader -> {
                    if (pending.get() == null) {
                        pending.set("k" + sequence.incrementAndGet());
                    }
                    String key = pending.get();
                    engines.get(idOf(leader)).execute("CREATE item:" + key + " CONTENT { n: 1 }", session(), null);
                    sql.add(key);
                    pending.remove();
                }, visible));
            }
            // quem escreve direto no consenso (sem passar pela admissão) também não pode ver desfecho desconhecido
            clients.add(client(cluster, running, leader -> {
                String key = "r" + sequence.incrementAndGet();
                ClusterHarness.write(leader, key, key);
                raw.add(key);
            }, visible));

            try {
                for (int round = 0; round < 3; round++) {
                    int before = sql.size() + raw.size();
                    await("escritas pararam: " + visible, 30_000,
                        () -> sql.size() + raw.size() > before + 50 || !visible.isEmpty());
                    ClusterRuntime leader = cluster.awaitLeader();
                    ClusterRuntime target = cluster.nodes().stream().filter(n -> n != leader).toList()
                        .get(round % 2);

                    TransferResult result = leader.transferLeadership(raftAddress(target), TIMEOUT);

                    assertTrue(result.transferred(), result.reason() + " " + cluster.describe());
                    assertEquals(idOf(target), result.leader());
                    assertNotEquals(ClusterRole.LEADER, leader.role());
                }
                int before = sql.size() + raw.size();
                await("escritas não voltaram: " + visible, 30_000,
                    () -> sql.size() + raw.size() > before + 50 || !visible.isEmpty());
            } finally {
                running.set(false);
                for (Thread client : clients) {
                    client.join(30_000);
                }
            }

            assertTrue(visible.isEmpty(), "erros visíveis: " + visible);
            cluster.awaitConverged();
            for (ClusterRuntime node : cluster.nodes()) {
                Datastore ds = engines.get(idOf(node));
                assertEquals(sql.size(), ds.execute("SELECT * FROM item", session(), null).asArray().size(),
                    idOf(node));
                raw.forEach(key -> assertTrue(node.backend().get(key).isPresent(), idOf(node) + " sem " + key));
            }
        }
    }

    @Test
    @DisplayName("um seguidor não transfere e um alvo parado é recusado sem interromper o líder")
    void refusesFromFollowerAndToDeadTarget() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            ClusterRuntime follower = cluster.awaitFollower();
            assertFalse(follower.transferLeadership(null, TIMEOUT).transferred());

            String dead = raftAddress(follower);
            cluster.stop(idOf(follower));
            await("o líder ainda vê o membro parado", 10_000,
                () -> leader.peerInfos().stream().anyMatch(p -> p.address().equals(dead) && !p.active()));

            TransferResult result = leader.transferLeadership(dead, Duration.ofSeconds(2));

            assertFalse(result.transferred());
            assertEquals(ClusterRole.LEADER, leader.role());
            ClusterHarness.write(leader, "depois", "v");
        }
    }

    @Test
    @DisplayName("um nó que se abstém não vira líder quando o líder cai")
    void abstainingNodeDoesNotCampaign() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            ClusterRuntime drained = cluster.awaitFollower();
            drained.abstain(true);
            long elections = drained.electionsStarted();

            cluster.stop(idOf(leader));
            ClusterRuntime next = cluster.awaitLeader();

            assertNotEquals(idOf(drained), idOf(next));
            assertEquals(elections, drained.electionsStarted());
        }
    }

    @Test
    @DisplayName("o nível de protocolo distingue um membro novo de um antigo ou parado")
    void protocolLevel() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.memory(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll();
             TcpRaftServer old = new TcpRaftServer(0, request -> new RaftWire.Response(request.term(), false, 0))) {
            ClusterRuntime leader = cluster.awaitLeader();
            ClusterRuntime follower = cluster.awaitFollower();

            assertEquals(RaftWire.PROTOCOL_LEVEL, leader.protocolOf(follower.config().advertiseAddress()));
            assertEquals(0, leader.protocolOf(new InetSocketAddress("127.0.0.1", old.port())));
            assertEquals(0, leader.protocolOf(
                new InetSocketAddress("127.0.0.1", ClusterHarness.reservePorts(1).getFirst())));
        }
    }
}
