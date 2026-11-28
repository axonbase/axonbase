package com.axonbase.core.cluster;

import com.axonbase.core.storage.RocksDbBackend;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Três nós em processos separados ({@link RaftCrashNode}), ligados por um {@link ChaosProxy} em cada sentido de cada
 * par. Matar é {@code kill -9}; partir a rede é cortar enlaces.
 */
final class ChaosCluster implements AutoCloseable {

    static final List<String> IDS = List.of("n1", "n2", "n3");

    /** Uma confirmação vista pelo teste: quando chegou e quem confirmou. */
    record Ack(long nanos, String node, String key) {
    }

    /** Um nó em outro processo. */
    final class Node {
        final Process process;
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile String role = "";
        volatile long commit = -1;
        volatile long last = -1;

        private Node(ProcessBuilder builder) throws IOException {
            process = builder.start();
            Thread.ofPlatform().daemon().start(this::read);
        }

        private void read() {
            try (var out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = out.readLine(); line != null; line = out.readLine()) {
                    String[] part = line.split(" ");
                    // Uma linha cortada pela morte do processo não tem todos os campos.
                    if (part[0].equals("ACK") && part.length == 2 && part[1].split("-").length == 3) {
                        acks.add(new Ack(System.nanoTime(), part[1].split("-")[0], part[1]));
                        acked.add(part[1]);
                    } else if (part[0].equals("STATE") && part.length == 4) {
                        role = part[1];
                        commit = Long.parseLong(part[2]);
                        last = Long.parseLong(part[3]);
                    } else {
                        events.add(line);
                    }
                }
            } catch (IOException | NumberFormatException killed) {
                // o processo morreu no meio de uma linha
            }
            role = "";
        }

        void send(String command) throws IOException {
            OutputStream in = process.getOutputStream();
            in.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        }

        String awaitEvent(Predicate<String> wanted, String what) {
            String[] found = new String[1];
            await("sem " + what + ": " + events, 120_000, () -> events.stream().filter(wanted).findFirst()
                .map(event -> {
                    found[0] = event;
                    return true;
                }).orElse(false));
            return found[0];
        }
    }

    private final Path dir;
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private final Map<String, Integer> linkPorts = new LinkedHashMap<>();
    private final Map<String, ChaosProxy> links = new LinkedHashMap<>();
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final Set<String> acked = ConcurrentHashMap.newKeySet();
    final List<Ack> acks = new CopyOnWriteArrayList<>();

    ChaosCluster(Path dir) throws IOException {
        this.dir = dir;
        List<Integer> free = ClusterHarness.reservePorts(IDS.size() + IDS.size() * (IDS.size() - 1));
        int next = 0;
        for (String id : IDS) {
            ports.put(id, free.get(next++));
        }
        for (String from : IDS) {
            for (String to : IDS) {
                if (!from.equals(to)) {
                    linkPorts.put(from + ">" + to, free.get(next));
                    links.put(from + ">" + to, new ChaosProxy(free.get(next++), ports.get(to)));
                }
            }
        }
    }

    ChaosCluster startAll() throws IOException {
        for (String id : IDS) {
            start(id);
        }
        return this;
    }

    void start(String id) throws IOException {
        String peers = String.join(",", IDS.stream().filter(other -> !other.equals(id))
            .map(other -> linkPorts.get(id + ">" + other).toString()).toList());
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "-Xmx384m", "-cp", System.getProperty("java.class.path"), RaftCrashNode.class.getName(),
            id, dir.resolve(id).toString(), ports.get(id).toString(), peers, "ALWAYS", "4");
        builder.redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve(id + ".err").toFile()));
        nodes.put(id, new Node(builder));
    }

    void kill(String id) throws InterruptedException {
        Node node = nodes.get(id);
        node.process.destroyForcibly();
        assertTrue(node.process.waitFor(20, TimeUnit.SECONDS), "o processo não morreu: " + id);
        node.role = "";
    }

    /** Corta o sentido {@code from} para {@code to}: o que {@code from} inicia deixa de chegar. */
    void cut(String from, String to) {
        links.get(from + ">" + to).cut();
    }

    /** Separa o nó dos outros nos dois sentidos. */
    void isolate(String id) {
        for (String other : IDS) {
            if (!other.equals(id)) {
                cut(id, other);
                cut(other, id);
            }
        }
    }

    void heal() {
        links.values().forEach(ChaosProxy::heal);
    }

    String awaitLeader() {
        String[] found = new String[1];
        await("nenhum processo lidera: " + describe(), 60_000, () -> nodes.entrySet().stream()
            .filter(e -> e.getValue().process.isAlive() && e.getValue().role.equals("LEADER"))
            .map(Map.Entry::getKey).findFirst().map(id -> {
                found[0] = id;
                return true;
            }).orElse(false));
        return found[0];
    }

    void awaitProgress(int more) {
        int target = acked.size() + more;
        await("as escritas pararam em " + acked.size() + ": " + describe(), 90_000, () -> acked.size() >= target);
    }

    /**
     * Espera a primeira escrita confirmada, depois de {@code since}, por um nó diferente de {@code lost}.
     *
     * @return os milissegundos entre {@code since} e ela, ou -1 se o prazo acabar
     */
    long millisToNewLeader(long since, String lost) {
        long deadline = since + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            for (Ack ack : acks) {
                if (ack.nanos() > since && !ack.node().equals(lost)) {
                    return TimeUnit.NANOSECONDS.toMillis(ack.nanos() - since);
                }
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        return -1;
    }

    String describe() {
        StringBuilder out = new StringBuilder();
        nodes.forEach((id, n) -> out.append(id).append(n.process.isAlive() ? "" : "(morto)").append('=')
            .append(n.role).append(' ').append(n.commit).append('/').append(n.last).append("; "));
        return out.toString();
    }

    /**
     * Para as escritas, espera os nós convergirem, derruba o cluster com saída limpa e confere em disco que toda
     * escrita confirmada está nos três e que eles são iguais.
     *
     * @return quantas escritas confirmadas foram conferidas
     */
    int verifyNothingLost() throws Exception {
        heal();
        for (Node node : nodes.values()) {
            node.send("stop");
        }
        Thread.sleep(500);
        await("nós não convergiram: " + describe(), 120_000, () -> {
            long target = nodes.values().stream().mapToLong(n -> n.commit).max().orElse(0);
            return nodes.values().stream().allMatch(n -> n.commit == target && n.last == target);
        });
        Thread.sleep(300);
        for (Node node : nodes.values()) {
            node.send("exit");
        }
        for (Map.Entry<String, Node> node : nodes.entrySet()) {
            assertTrue(node.getValue().process.waitFor(120, TimeUnit.SECONDS), node.getKey() + " não saiu");
            assertEquals(0, node.getValue().process.exitValue(),
                Files.readString(dir.resolve(node.getKey() + ".err")));
        }
        Map<String, String> reference = null;
        for (String id : IDS) {
            try (RocksDbBackend db = new RocksDbBackend(dir.resolve(id).resolve("db").toString())) {
                Map<String, String> stored = new TreeMap<>();
                db.keysWithPrefix("n").forEach(
                    k -> stored.put(k, new String(db.get(k).orElseThrow(), StandardCharsets.UTF_8)));
                List<String> lost = new ArrayList<>(acked.stream().filter(k -> !k.equals(stored.get(k))).toList());
                assertTrue(lost.isEmpty(), id + " perdeu " + lost.size() + " escritas confirmadas, por exemplo "
                    + lost.stream().limit(5).toList());
                if (reference == null) {
                    reference = stored;
                } else {
                    assertEquals(reference, stored, id + " divergiu");
                }
            }
        }
        return acked.size();
    }

    @Override
    public void close() throws IOException {
        nodes.values().forEach(n -> n.process.destroyForcibly());
        for (ChaosProxy link : links.values()) {
            link.close();
        }
    }
}
