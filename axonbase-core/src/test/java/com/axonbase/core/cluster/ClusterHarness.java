package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.core.storage.VersionedKvBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/** Cluster de nós reais sobre TCP, no mesmo processo, para os testes de replicação, leitura e queda. */
final class ClusterHarness implements AutoCloseable {

    private static final Set<Integer> HANDED_OUT = ConcurrentHashMap.newKeySet();

    private final Path root;
    private final RaftOptions options;
    private final boolean rocks;
    private final Map<String, InetSocketAddress> addresses = new LinkedHashMap<>();
    private final Map<String, ClusterRuntime> running = new LinkedHashMap<>();
    private final Map<String, VersionedKvBackend> backends = new LinkedHashMap<>();
    private final List<String> voters;

    private ClusterHarness(Path root, RaftOptions options, boolean rocks, List<String> voters) {
        this.root = root;
        this.options = options;
        this.rocks = rocks;
        this.voters = voters;
        voters.forEach(this::address);
    }

    static ClusterHarness memory(Path root, RaftOptions options, String... voters) {
        return new ClusterHarness(root, options, false, List.of(voters));
    }

    static ClusterHarness rocks(Path root, RaftOptions options, String... voters) {
        return new ClusterHarness(root, options, true, List.of(voters));
    }

    static List<Integer> reservePorts(int count) {
        List<Integer> ports = new ArrayList<>();
        int attempts = 0;
        while (ports.size() < count) {
            if (++attempts > 2000) {
                throw new IllegalStateException("sem portas livres na faixa 20000 a 32000");
            }
            int candidate = ThreadLocalRandom.current().nextInt(20_000, 32_000);
            if (!HANDED_OUT.add(candidate)) {
                continue;
            }
            try (ServerSocket socket = new ServerSocket(candidate)) {
                socket.setReuseAddress(true);
                ports.add(candidate);
            } catch (IOException busy) {
                // ocupada: tenta outra
            }
        }
        return ports;
    }

    InetSocketAddress address(String id) {
        return addresses.computeIfAbsent(id,
            ignored -> new InetSocketAddress("127.0.0.1", reservePorts(1).getFirst()));
    }

    Path dir(String id) {
        return root.resolve(id);
    }

    ClusterHarness startAll() {
        voters.forEach(this::start);
        return this;
    }

    ClusterRuntime start(String id) {
        boolean learner = !voters.contains(id);
        List<InetSocketAddress> peers = voters.stream().filter(v -> !v.equals(id)).map(this::address).toList();
        ClusterConfig config = new ClusterConfig(id, "g", address(id), peers, learner);
        VersionedKvBackend backend = rocks ? new RocksDbBackend(dir(id).resolve("db").toString(), options.sync())
            : new MemoryBackend();
        try {
            ClusterRuntime runtime = new ClusterRuntime(config, dir(id).resolve("raft"), backend,
                options.withAllowedLearners(List.of("*")));
            backends.put(id, backend);
            running.put(id, runtime);
            return runtime;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void stop(String id) {
        ClusterRuntime runtime = running.remove(id);
        if (runtime == null) {
            return;
        }
        try {
            runtime.close();
            if (backends.get(id) instanceof RocksDbBackend db) {
                db.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ClusterRuntime node(String id) {
        return running.get(id);
    }

    List<ClusterRuntime> nodes() {
        return List.copyOf(running.values());
    }

    ClusterRuntime awaitLeader() {
        ClusterRuntime[] found = new ClusterRuntime[1];
        await("nenhum líder pronto", 30_000, () -> {
            for (ClusterRuntime runtime : nodes()) {
                if (runtime.role() == ClusterRole.LEADER && runtime.status().ready()) {
                    found[0] = runtime;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    ClusterRuntime awaitFollower() {
        ClusterRuntime leader = awaitLeader();
        ClusterRuntime follower = nodes().stream().filter(n -> n != leader && n.role() == ClusterRole.FOLLOWER)
            .findFirst().orElseThrow(() -> new AssertionError("sem seguidor"));
        await("seguidor sem líder", 10_000, () -> !follower.leaderAddress().isEmpty());
        return follower;
    }

    /** Espera todos os nós de pé aplicarem até o último índice confirmado do líder. */
    void awaitConverged() {
        await("nós não convergiram: " + describe(), 60_000, () -> {
            long target = nodes().stream().mapToLong(ClusterRuntime::commitIndex).max().orElse(0);
            return nodes().stream().allMatch(n -> n.commitIndex() == target && n.lastIndex() == target);
        });
    }

    String describe() {
        StringBuilder out = new StringBuilder();
        running.forEach((id, n) -> out.append(id).append('=').append(n.role()).append(" commit=")
            .append(n.commitIndex()).append(" last=").append(n.lastIndex()).append("; "));
        return out.toString();
    }

    /** Estado visível de um nó, sem as chaves internas do Raft. */
    static Map<String, String> data(ClusterRuntime runtime) {
        Map<String, String> out = new java.util.TreeMap<>();
        for (String key : runtime.backend().keysWithPrefix("")) {
            if (!key.startsWith("__raft/")) {
                runtime.backend().get(key)
                    .ifPresent(v -> out.put(key, new String(v, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    static CommittedBatch put(String key, String value) {
        return new CommittedBatch("tx-" + key + "-" + System.nanoTime(),
            Map.of(key, value.getBytes(StandardCharsets.UTF_8)), Set.of());
    }

    static long write(ClusterRuntime leader, String key, String value) {
        return leader.confirm(leader.config().nodeId(), put(key, value));
    }

    static void await(String message, long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Override
    public void close() {
        List.copyOf(running.keySet()).forEach(this::stop);
    }
}
