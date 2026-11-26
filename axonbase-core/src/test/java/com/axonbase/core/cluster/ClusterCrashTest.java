package com.axonbase.core.cluster;

import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static com.axonbase.core.cluster.ClusterHarness.data;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cluster: queda de processo e troca de líder")
class ClusterCrashTest {

    @TempDir
    Path dir;

    /** Um nó em outro processo. */
    private static final class Child {
        final Process process;
        volatile String role = "";
        volatile long commit = -1;
        volatile long last = -1;
        volatile boolean up;

        Child(ProcessBuilder builder, Set<String> acked) throws IOException {
            process = builder.start();
            Thread.ofPlatform().daemon().start(() -> {
                try (var out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line = out.readLine(); line != null; line = out.readLine()) {
                        String[] part = line.split(" ");
                        // Uma linha cortada pela morte do processo não tem todos os campos.
                        if (part[0].equals("ACK") && part.length == 2 && part[1].split("-").length == 3) {
                            acked.add(part[1]);
                        } else if (part[0].equals("STATE") && part.length == 4) {
                            role = part[1];
                            commit = Long.parseLong(part[2]);
                            last = Long.parseLong(part[3]);
                        } else if (part[0].equals("UP")) {
                            up = true;
                        }
                    }
                } catch (IOException | NumberFormatException killed) {
                    // o processo morreu no meio de uma linha
                }
                role = "";
            });
        }

        void kill() throws InterruptedException {
            process.destroyForcibly();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "o processo não morreu");
            role = "";
        }

        void send(String command) throws IOException {
            OutputStream in = process.getOutputStream();
            in.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        }
    }

    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private final Map<String, Child> children = new LinkedHashMap<>();
    private final Set<String> acked = ConcurrentHashMap.newKeySet();

    private void start(String id, RocksDbBackend.SyncMode sync) throws IOException {
        String peers = String.join(",", ports.entrySet().stream().filter(e -> !e.getKey().equals(id))
            .map(e -> e.getValue().toString()).toList());
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "-Xmx256m", "-cp", System.getProperty("java.class.path"), RaftCrashNode.class.getName(),
            id, dir.resolve(id).toString(), ports.get(id).toString(), peers, sync.name(), "4");
        builder.redirectError(dir.resolve(id + ".err").toFile());
        children.put(id, new Child(builder, acked));
    }

    private String awaitLeader() {
        String[] found = new String[1];
        await("nenhum processo lidera: " + describe(), 60_000, () -> children.entrySet().stream()
            .filter(e -> e.getValue().process.isAlive() && e.getValue().role.equals("LEADER"))
            .map(Map.Entry::getKey).findFirst().map(id -> {
                found[0] = id;
                return true;
            }).orElse(false));
        return found[0];
    }

    private void awaitProgress(int more) {
        int target = acked.size() + more;
        await("as escritas pararam em " + acked.size() + ": " + describe(), 90_000, () -> acked.size() >= target);
    }

    private String describe() {
        StringBuilder out = new StringBuilder();
        children.forEach((id, c) -> out.append(id).append(c.process.isAlive() ? "" : "(morto)").append('=')
            .append(c.role).append(' ').append(c.commit).append('/').append(c.last).append("; "));
        return out.toString();
    }

    @ParameterizedTest
    @EnumSource(value = RocksDbBackend.SyncMode.class, names = {"ALWAYS", "BATCH"})
    @DisplayName("kill -9 em líder, seguidor e cluster inteiro não perde escrita confirmada nem diverge")
    void killDashNineKeepsEveryAcknowledgedWrite(RocksDbBackend.SyncMode sync) throws Exception {
        List<Integer> free = ClusterHarness.reservePorts(3);
        for (int i = 0; i < 3; i++) {
            ports.put("n" + (i + 1), free.get(i));
        }
        try {
            for (String id : ports.keySet()) {
                start(id, sync);
            }
            awaitLeader();
            awaitProgress(100);
            for (int round = 0; round < 3; round++) {
                String leader = awaitLeader();
                children.get(leader).kill();
                awaitProgress(100);
                start(leader, sync);
                awaitProgress(100);

                String leading = awaitLeader();
                String follower = ports.keySet().stream().filter(id -> !id.equals(leading)).skip(round % 2)
                    .findFirst().orElseThrow();
                children.get(follower).kill();
                awaitProgress(100);
                start(follower, sync);
                awaitProgress(50);
            }
            // Todos de uma vez: o que foi confirmado precisa voltar só do disco.
            for (Child child : children.values()) {
                child.process.destroyForcibly();
            }
            for (Child child : children.values()) {
                assertTrue(child.process.waitFor(20, TimeUnit.SECONDS));
            }
            for (String id : ports.keySet()) {
                start(id, sync);
            }
            awaitLeader();
            awaitProgress(100);

            for (Child child : children.values()) {
                child.send("stop");
            }
            Thread.sleep(500);
            await("nós não convergiram: " + describe(), 60_000, () -> {
                long target = children.values().stream().mapToLong(c -> c.commit).max().orElse(0);
                return children.values().stream().allMatch(c -> c.commit == target && c.last == target);
            });
            Thread.sleep(300);
            for (Child child : children.values()) {
                child.send("exit");
            }
            for (Map.Entry<String, Child> child : children.entrySet()) {
                assertTrue(child.getValue().process.waitFor(60, TimeUnit.SECONDS), child.getKey() + " não saiu");
                assertEquals(0, child.getValue().process.exitValue(),
                    Files.readString(dir.resolve(child.getKey() + ".err")));
            }
        } finally {
            children.values().forEach(c -> c.process.destroyForcibly());
        }

        Map<String, String> reference = null;
        for (String id : ports.keySet()) {
            try (RocksDbBackend db = new RocksDbBackend(dir.resolve(id).resolve("db").toString())) {
                Map<String, String> stored = new TreeMap<>();
                db.keysWithPrefix("").stream().filter(k -> !k.startsWith("__raft/")).forEach(
                    k -> stored.put(k, new String(db.get(k).orElseThrow(), StandardCharsets.UTF_8)));
                List<String> lost = acked.stream().filter(k -> !k.equals(stored.get(k))).limit(5).toList();
                assertTrue(lost.isEmpty(), id + " perdeu escritas confirmadas: " + lost);
                if (reference == null) {
                    reference = stored;
                } else {
                    assertEquals(reference, stored, id + " divergiu");
                }
            }
        }
        assertTrue(acked.size() > 1_000, "poucas escritas para o teste valer: " + acked.size());
    }

    @Test
    @DisplayName("trocas de líder sob carga não perdem escrita confirmada nem deixam os nós diferentes")
    void leaderChangesUnderLoad() throws Exception {
        try (ClusterHarness cluster = ClusterHarness.rocks(dir, RaftOptions.defaults(), "n1", "n2", "n3")
                .startAll()) {
            cluster.awaitLeader();
            Set<String> confirmed = ConcurrentHashMap.newKeySet();
            AtomicBoolean writing = new AtomicBoolean(true);
            java.util.concurrent.locks.ReadWriteLock membership =
                new java.util.concurrent.locks.ReentrantReadWriteLock(true);
            List<Thread> writers = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                int worker = w;
                writers.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; writing.get(); i++) {
                        String key = "w" + worker + "-" + i;
                        ClusterRuntime leader;
                        membership.readLock().lock();
                        try {
                            leader = cluster.nodes().stream().filter(n -> n.role() == ClusterRole.LEADER)
                                .findFirst().orElse(null);
                        } finally {
                            membership.readLock().unlock();
                        }
                        if (leader == null) {
                            continue;
                        }
                        try {
                            ClusterHarness.write(leader, key, key);
                            confirmed.add(key);
                        } catch (RuntimeException notConfirmed) {
                            // líder caindo: desfecho desconhecido ou recusa
                        }
                    }
                }));
            }
            try {
                for (int round = 0; round < 5; round++) {
                    int target = confirmed.size() + 200;
                    await("escritas pararam: " + cluster.describe(), 60_000, () -> confirmed.size() >= target);
                    String leader = cluster.awaitLeader().config().nodeId();
                    ClusterRuntime stopping = cluster.node(leader);
                    stopping.close();
                    membership.writeLock().lock();
                    try {
                        cluster.stop(leader);
                    } finally {
                        membership.writeLock().unlock();
                    }
                    int after = confirmed.size() + 100;
                    await("sem escritas depois da queda do líder: " + cluster.describe(), 60_000,
                        () -> confirmed.size() >= after);
                    membership.writeLock().lock();
                    try {
                        cluster.start(leader);
                    } finally {
                        membership.writeLock().unlock();
                    }
                }
            } finally {
                writing.set(false);
                for (Thread writer : writers) {
                    writer.join(60_000);
                }
            }
            cluster.awaitConverged();
            Map<String, String> expected = data(cluster.awaitLeader());
            for (ClusterRuntime node : cluster.nodes()) {
                Map<String, String> actual = data(node);
                List<String> lost = confirmed.stream().filter(k -> !k.equals(actual.get(k))).limit(5).toList();
                assertTrue(lost.isEmpty(), node.config().nodeId() + " perdeu: " + lost);
                assertEquals(expected, actual, node.config().nodeId() + " divergiu");
            }
        }
    }
}
