package com.axonbase.core.shard.backup;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.partition.ColdTier;
import com.axonbase.core.partition.PartitionMaintenance;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.shard.GatewaySession;
import com.axonbase.core.shard.ShardGateway;
import com.axonbase.core.shard.ShardMap;
import com.axonbase.core.shard.ShardMapStore;
import com.axonbase.core.shard.ShardTestCluster;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dois shards em disco com camada fria compartilhada e uma tabela do shard base particionada por dia, com a primeira
 * partição na camada fria.
 */
final class ColdFleet implements AutoCloseable {

    static final String DAY1 = "2026-11-01T10:00:00Z";
    static final String DAY2 = "2026-11-02T10:00:00Z";
    /** O marcador de construção do índice {@code events_who}. */
    static final String BUILD_MARKER = "IXB|n|d|events|events_who";

    private final Path root;
    final ShardTestCluster cluster;
    final ShardMapStore store;
    final ShardGateway gateway;
    final ClusterBackup backups;
    private final AtomicLong now = new AtomicLong(System.currentTimeMillis());

    ColdFleet(Path root) {
        this.root = root;
        this.cluster = ShardTestCluster.rocks(root.resolve("original"), 2, 1, node -> tier(root, node));
        this.store = new ShardMapStore(cluster.client("s0"));
        store.bootstrap(ShardMap.initial(cluster.shardInfos()));
        this.gateway = new ShardGateway(store, info -> cluster.client(info.id()),
            ShardGateway.Options.defaults().withMapTtl(Duration.ofMillis(200)), HybridClock.system());
        this.backups = new ClusterBackup(store, info -> cluster.backupMembers(info.id()), Duration.ofSeconds(60));
    }

    /** A camada fria de um processo: o armazenamento é o mesmo para todos, o cache é de cada um. */
    static ColdTier tier(Path root, String process) {
        return ColdTier.of(new FileSystemColdStore(root.resolve("cold")), root.resolve("cache-" + process),
            ColdTier.Settings.defaults().withGrace(Duration.ZERO).withGcInterval(Duration.ZERO));
    }

    AxonValue sql(String script) {
        return gateway.execute(new GatewaySession("n", "d"), script, null);
    }

    ShardTestCluster.Node home() {
        return cluster.awaitLeader("s0");
    }

    /** Cria a tabela particionada no shard base e leva a partição do primeiro dia para a camada fria. */
    void firstDayCold() {
        sql("DEFINE TABLE events SCHEMALESS");
        sql("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
        sql("CREATE events:a CONTENT {at: d\"" + DAY1 + "\", who: \"ana\"}");
        sql("CREATE events:b CONTENT {at: d\"" + DAY2 + "\", who: \"bia\"}");
        sql("ALTER PARTITION ON TABLE events AT d\"" + DAY1 + "\" TO COLD");
        assertEquals(1, maintenance(home().datastore()).runOnce().movedCold());
    }

    /** Define um índice UNIQUE e derruba a construção antes do primeiro bloco, como numa queda do processo. */
    void interruptUniqueIndex() throws InterruptedException {
        home().datastore().findDatabase("n", "d").indexInlineRows(0);
        IndexBuilder.chunkHook(() -> {
            throw new IndexBuilder.Crash();
        });
        try {
            sql("DEFINE INDEX events_who ON TABLE events COLUMNS who UNIQUE");
            assertTrue(IndexBuilder.awaitAll(10_000));
        } finally {
            IndexBuilder.chunkHook(null);
        }
        assertTrue(home().backend().get(BUILD_MARKER).isPresent(), "a construção não ficou interrompida");
    }

    /** Duas passadas com um dia entre elas: com carência zero, tudo que pode ser apagado sai. */
    int collectGarbage() {
        Datastore home = home().datastore();
        maintenance(home).runOnce();
        now.addAndGet(Duration.ofDays(1).toMillis());
        return maintenance(home).runOnce().collectedObjects();
    }

    private PartitionMaintenance maintenance(Datastore datastore) {
        return new PartitionMaintenance(datastore,
            PartitionMaintenance.Options.defaults().withTtlPause(Duration.ZERO), now::get);
    }

    List<Path> coldObjects() {
        return files(root.resolve("cold").resolve("parts"));
    }

    static Map<String, ClusterRestore.Source> sources(ClusterManifest manifest) {
        Map<String, ClusterRestore.Source> out = new LinkedHashMap<>();
        manifest.shards().forEach(s -> out.put(s.shard(),
            new ClusterRestore.Source(Path.of(s.backupDir()), Path.of(s.journalDir()))));
        return out;
    }

    private static List<Path> files(Path directory) {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        gateway.close();
        cluster.close();
    }
}
