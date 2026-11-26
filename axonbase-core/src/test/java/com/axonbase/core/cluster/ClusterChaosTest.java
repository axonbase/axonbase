package com.axonbase.core.cluster;

import com.axonbase.core.storage.PhysicalBackups;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Falhas injetadas num cluster de três processos. Cada cenário confere no fim que nenhuma escrita confirmada se
 * perdeu e que os três nós têm o mesmo conteúdo.
 *
 * <p>{@code -Daxon.chaos.seed=<n>} repete a mesma sequência de escolhas e de esperas (o escalonamento dos processos
 * continua variando), e {@code -Daxon.chaos.rounds=<n>} repete cada falha.</p>
 */
@DisplayName("Caos: líder morto, rede partida e queda durante o backup")
@Timeout(600)
class ClusterChaosTest {

    private static final long ELECTION_LIMIT_MILLIS = 10_000;
    private static final long SEED = Long.getLong("axon.chaos.seed", 20261126L);
    private static final int ROUNDS = Math.max(1, Integer.getInteger("axon.chaos.rounds", 1));
    /** Uma escrita já replicada quando o enlace caiu pode ter a confirmação lida pouco depois. */
    private static final long IN_FLIGHT_NANOS = TimeUnit.SECONDS.toNanos(1);

    @TempDir
    Path dir;

    private final Random random = new Random(SEED);

    private static void report(String scenario, String details) {
        System.out.println("CHAOS seed=" + SEED + " scenario=" + scenario + " " + details);
    }

    private void jitter() throws InterruptedException {
        Thread.sleep(random.nextInt(400));
    }

    private String anyFollower(String leader) {
        List<String> others = ChaosCluster.IDS.stream().filter(id -> !id.equals(leader)).toList();
        return others.get(random.nextInt(others.size()));
    }

    @Test
    @DisplayName("matar o líder elege outro em menos de 10 s sem perder escrita confirmada")
    void killLeader() throws Exception {
        try (ChaosCluster cluster = new ChaosCluster(dir).startAll()) {
            cluster.awaitLeader();
            cluster.awaitProgress(200);
            for (int round = 1; round <= ROUNDS * 3; round++) {
                jitter();
                String leader = cluster.awaitLeader();
                long killed = System.nanoTime();
                cluster.kill(leader);

                long millis = cluster.millisToNewLeader(killed, leader);

                report("kill-leader", "round=" + round + " killed=" + leader + " election_ms=" + millis);
                assertTrue(millis >= 0 && millis < ELECTION_LIMIT_MILLIS, "eleição em " + millis + " ms");
                cluster.awaitProgress(100);
                cluster.start(leader);
                cluster.awaitProgress(100);
            }
            report("kill-leader", "acknowledged=" + cluster.verifyNothingLost() + " lost=0 PASS");
        }
    }

    @Test
    @DisplayName("líder isolado não confirma nada, a maioria elege outro e tudo converge ao religar")
    void isolateLeader() throws Exception {
        try (ChaosCluster cluster = new ChaosCluster(dir).startAll()) {
            cluster.awaitLeader();
            cluster.awaitProgress(200);
            for (int round = 1; round <= ROUNDS; round++) {
                jitter();
                String leader = cluster.awaitLeader();
                long cut = System.nanoTime();
                cluster.isolate(leader);

                long millis = cluster.millisToNewLeader(cut, leader);

                report("isolate-leader", "round=" + round + " isolated=" + leader + " election_ms=" + millis);
                assertTrue(millis >= 0 && millis < ELECTION_LIMIT_MILLIS, "eleição em " + millis + " ms");
                cluster.awaitProgress(200);
                long minority = cluster.acks.stream()
                    .filter(a -> a.node().equals(leader) && a.nanos() > cut + IN_FLIGHT_NANOS).count();
                assertTrue(minority == 0, "o nó isolado confirmou " + minority + " escritas sem a maioria");
                cluster.heal();
                cluster.awaitProgress(200);
            }
            report("isolate-leader", "acknowledged=" + cluster.verifyNothingLost() + " lost=0 PASS");
        }
    }

    @Test
    @DisplayName("cortar um sentido de um enlace não para o cluster nem faz divergir")
    void oneWayLink() throws Exception {
        try (ChaosCluster cluster = new ChaosCluster(dir).startAll()) {
            cluster.awaitLeader();
            cluster.awaitProgress(200);
            for (int round = 1; round <= ROUNDS * 2; round++) {
                String leader = cluster.awaitLeader();
                String follower = anyFollower(leader);
                boolean fromLeader = round % 2 == 1;
                cluster.cut(fromLeader ? leader : follower, fromLeader ? follower : leader);

                cluster.awaitProgress(200);

                report("one-way-link", "round=" + round + " cut=" + (fromLeader ? leader + ">" + follower
                    : follower + ">" + leader));
                cluster.heal();
                cluster.awaitProgress(100);
            }
            report("one-way-link", "acknowledged=" + cluster.verifyNothingLost() + " lost=0 PASS");
        }
    }

    @Test
    @DisplayName("matar um seguidor durante o backup físico não deixa backup corrompido")
    void killDuringBackup() throws Exception {
        try (ChaosCluster cluster = new ChaosCluster(dir).startAll()) {
            String leader = cluster.awaitLeader();
            cluster.nodes.get(leader).send("load 200");
            cluster.nodes.get(leader).awaitEvent(e -> e.startsWith("LOADED"), "carga");
            cluster.awaitProgress(100);
            Path backups = dir.resolve("backups");
            for (int round = 1; round <= ROUNDS * 3; round++) {
                String follower = anyFollower(cluster.awaitLeader());
                ChaosCluster.Node node = cluster.nodes.get(follower);
                node.send("backup " + backups);
                node.awaitEvent(e -> e.equals("BACKUP start"), "início do backup");
                int delay = random.nextInt(300);
                Thread.sleep(delay);
                boolean finished = node.events.stream().anyMatch(e -> e.startsWith("BACKUP done"));
                cluster.kill(follower);

                int listed;
                try (PhysicalBackups engine = PhysicalBackups.open(backups)) {
                    List<PhysicalBackups.Info> all = engine.list();
                    listed = all.size();
                    for (PhysicalBackups.Info info : all) {
                        assertTrue(engine.verify(info.id()), "backup " + info.id() + " corrompido");
                    }
                }
                report("kill-during-backup", "round=" + round + " killed=" + follower + " after_ms=" + delay
                    + " finished=" + finished + " backups_listed=" + listed);
                cluster.awaitProgress(50);
                cluster.start(follower);
                cluster.awaitProgress(100);
            }
            // depois das quedas o diretório continua aceitando um backup completo
            String follower = anyFollower(cluster.awaitLeader());
            ChaosCluster.Node node = cluster.nodes.get(follower);
            node.send("backup " + backups);
            String done = node.awaitEvent(e -> e.startsWith("BACKUP done") || e.startsWith("BACKUP failed"),
                "backup final");
            assertTrue(done.startsWith("BACKUP done"), done);
            report("kill-during-backup", "acknowledged=" + cluster.verifyNothingLost() + " lost=0 PASS");
        }
    }
}
