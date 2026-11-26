package com.axonbase.core.shard;

import com.axonbase.core.cluster.ClockHooks;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.RaftOptions;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.core.storage.VersionedKvBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Vários grupos Raft reais, sobre TCP (Transmission Control Protocol), no mesmo processo: o "conjunto de shards" dos
 * testes. Cada nó tem o próprio armazenamento, relógio, runtime de consenso e datastore.
 */
final class ShardTestCluster implements AutoCloseable {

    private static final Set<Integer> HANDED_OUT = ConcurrentHashMap.newKeySet();

    /** Um nó de pé. */
    record Node(String shard, String id, VersionedKvBackend backend, HybridClock clock, ClusterRuntime runtime,
                Datastore datastore, ShardNode endpoint) {
    }

    private final Path root;
    private final boolean rocks;
    private final Map<String, Map<String, Node>> groups = new LinkedHashMap<>();
    private final Map<String, InetSocketAddress> addresses = new LinkedHashMap<>();
    private final List<String> shardIds = new ArrayList<>();
    private final int members;

    private ShardTestCluster(Path root, boolean rocks, int members) {
        this.root = root;
        this.rocks = rocks;
        this.members = members;
    }

    /** {@code shards} grupos de {@code members} nós, em memória. */
    static ShardTestCluster memory(Path root, int shards, int members) {
        return new ShardTestCluster(root, false, members).start(shards);
    }

    /** Como {@link #memory}, com RocksDB em disco (backup físico e journal). */
    static ShardTestCluster rocks(Path root, int shards, int members) {
        return new ShardTestCluster(root, true, members).start(shards);
    }

    private ShardTestCluster start(int shards) {
        for (int s = 0; s < shards; s++) {
            addShard("s" + s);
        }
        return this;
    }

    /** Sobe mais um grupo (para os testes de rebalanceamento). */
    void addShard(String shard) {
        shardIds.add(shard);
        groups.put(shard, new LinkedHashMap<>());
        for (int m = 0; m < members; m++) {
            startNode(shard, shard + "-n" + m);
        }
        awaitLeader(shard);
    }

    private InetSocketAddress address(String node) {
        return addresses.computeIfAbsent(node, ignored -> new InetSocketAddress("127.0.0.1", freePort()));
    }

    private static int freePort() {
        for (int attempt = 0; attempt < 2000; attempt++) {
            int candidate = ThreadLocalRandom.current().nextInt(33_000, 45_000);
            if (!HANDED_OUT.add(candidate)) {
                continue;
            }
            try (ServerSocket socket = new ServerSocket(candidate)) {
                socket.setReuseAddress(true);
                return candidate;
            } catch (IOException busy) {
                // ocupada: tenta outra
            }
        }
        throw new IllegalStateException("sem portas livres");
    }

    private void startNode(String shard, String id) {
        List<InetSocketAddress> peers = new ArrayList<>();
        for (int m = 0; m < members; m++) {
            String other = shard + "-n" + m;
            if (!other.equals(id)) {
                peers.add(address(other));
            }
        }
        VersionedKvBackend backend = rocks
            ? new RocksDbBackend(dir(shard, id).resolve("db").toString()) : new MemoryBackend();
        HybridClock clock = backend instanceof RocksDbBackend disk ? disk.clock() : HybridClock.system();
        try {
            ClusterRuntime runtime = new ClusterRuntime(
                new ClusterConfig(id, shard, address(id), peers), dir(shard, id).resolve("raft"), backend,
                RaftOptions.defaults().withAllowedLearners(List.of("*")));
            runtime.hooks(new ClockHooks(clock));
            Datastore datastore = new Datastore(backend);
            runtime.appliedBatchListener(datastore.appliedBatchListener());
            datastore.commitCoordinator(runtime, id);
            Node node = new Node(shard, id, backend, clock, runtime, datastore,
                new ShardNode(datastore, runtime, clock));
            synchronized (groups) {
                groups.get(shard).put(id, node);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path dir(String shard, String id) {
        return root.resolve(shard).resolve(id);
    }

    List<String> shardIds() {
        return List.copyOf(shardIds);
    }

    List<ShardInfo> shardInfos() {
        return shardIds.stream().map(id -> new ShardInfo(id, List.of())).toList();
    }

    List<Node> nodes(String shard) {
        synchronized (groups) {
            return List.copyOf(groups.get(shard).values());
        }
    }

    /** O cliente de um shard: enxerga os membros de pé a cada chamada. */
    ShardClient client(String shard) {
        return new GroupShardClient(shard, () -> nodes(shard).stream().map(Node::endpoint).map(ShardMember.class::cast)
            .toList(), java.time.Duration.ofSeconds(30));
    }

    Map<String, ShardClient> clients() {
        Map<String, ShardClient> out = new LinkedHashMap<>();
        shardIds.forEach(id -> out.put(id, client(id)));
        return out;
    }

    Node awaitLeader(String shard) {
        Node[] found = new Node[1];
        await("shard " + shard + " sem líder", () -> {
            for (Node node : nodes(shard)) {
                if (node.runtime().role() == ClusterRole.LEADER && node.runtime().status().ready()) {
                    found[0] = node;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    /** Derruba um nó (o armazenamento em memória se perde). */
    void stop(String shard, String id) {
        Node node;
        synchronized (groups) {
            node = groups.get(shard).remove(id);
        }
        if (node != null) {
            close(node);
        }
    }

    private static void close(Node node) {
        try {
            node.runtime().close();
            if (node.backend() instanceof RocksDbBackend disk) {
                disk.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void await(String message, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 30_000_000_000L;
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
        for (String shard : shardIds) {
            for (Node node : nodes(shard)) {
                stop(shard, node.id());
            }
        }
    }
}
