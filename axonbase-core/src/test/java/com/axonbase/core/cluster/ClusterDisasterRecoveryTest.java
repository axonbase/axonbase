package com.axonbase.core.cluster;

import com.axonbase.core.dr.ArchiveLayout;
import com.axonbase.core.dr.ArchiveStore;
import com.axonbase.core.dr.DirectoryArchiveStore;
import com.axonbase.core.dr.DrArchiver;
import com.axonbase.core.dr.DrRestore;
import com.axonbase.core.dr.EncryptedArchiveStore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.shard.backup.ShardJournalHooks;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.axonbase.core.cluster.ClusterHarness.data;
import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DR: perda total do cluster, restauração do arquivamento e a um instante")
@Timeout(300)
class ClusterDisasterRecoveryTest {

    private static final List<String> IDS = List.of("n1", "n2", "n3");

    @TempDir
    Path dir;

    private static void delete(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    @Test
    @DisplayName("o cluster destruído volta no instante pedido e no último arquivado, e aceita escritas")
    void loseEverythingAndComeBack() throws Exception {
        ArchiveStore store = new EncryptedArchiveStore(new DirectoryArchiveStore(dir.resolve("arquivo")),
            "chave-de-teste");
        ArchiveLayout layout = new ArchiveLayout("axon-dr", "e1", "g");
        Path original = dir.resolve("original");
        Path backups = dir.resolve("backups-do-seguidor");
        Map<String, String> atInstant;
        Map<String, String> latest;
        long instant;
        try (ClusterHarness cluster = ClusterHarness.rocks(original, RaftOptions.defaults(),
                IDS.toArray(String[]::new)).startAll()) {
            Map<String, ShardJournalHooks> journals = new LinkedHashMap<>();
            for (ClusterRuntime node : cluster.nodes()) {
                String id = node.config().nodeId();
                RocksDbBackend backend = (RocksDbBackend) node.backend();
                ShardJournalHooks journal = ShardJournalHooks.open(dir.resolve("journal-" + id),
                    JournalOptions.defaults().withMaxSegmentAge(Duration.ofMillis(200)),
                    new ClockHooks(backend.clock()));
                node.hooks(journal);
                journals.put(id, journal);
            }
            ClusterRuntime leader = cluster.awaitLeader();
            for (int i = 0; i < 100; i++) {
                write(leader, "k" + i, "antes-do-backup");
            }
            cluster.awaitConverged();
            ClusterRuntime follower = cluster.awaitFollower();
            try (PhysicalBackups engine = PhysicalBackups.open(backups)) {
                engine.create((RocksDbBackend) follower.backend());
            }
            for (int i = 50; i < 200; i++) {
                write(leader, "k" + i, "depois-do-backup");
            }
            cluster.awaitConverged();
            atInstant = data(leader);
            Thread.sleep(20);
            instant = System.currentTimeMillis();
            Thread.sleep(20);
            for (int i = 150; i < 260; i++) {
                write(leader, "k" + i, "depois-do-instante");
            }
            cluster.awaitConverged();
            latest = data(leader);
            // o arquivamento de cada nó, como o DrTool faria ao lado dele
            for (Map.Entry<String, ShardJournalHooks> journal : journals.entrySet()) {
                journal.getValue().journal().seal();
                DrArchiver archiver = new DrArchiver(store, layout, journal.getKey());
                assertTrue(archiver.pushSegments(dir.resolve("journal-" + journal.getKey())) > 0);
                assertEquals(0, archiver.pushSegments(dir.resolve("journal-" + journal.getKey())));
            }
            DrArchiver fromFollower = new DrArchiver(store, layout, follower.config().nodeId());
            assertEquals(1, fromFollower.pushBackups(backups));
            assertEquals(0, fromFollower.pushBackups(backups));
            cluster.close();
            journals.values().forEach(ShardJournalHooks::close);
        }
        // perda total
        delete(original);
        delete(backups);

        DrRestore.Result past = DrRestore.restore(store, layout, dir.resolve("work-t"), dir.resolve("no-instante"),
            HlcTimestamp.endOfMillisecond(instant), 4);
        DrRestore.Result present =
            DrRestore.restore(store, layout, dir.resolve("work-l"), dir.resolve("ultimo"), null, 4);

        assertTrue(past.prepared().lastIndex() < present.prepared().lastIndex());
        try (RocksDbBackend restored = new RocksDbBackend(past.prepared().directory().toString())) {
            Map<String, String> found = new java.util.TreeMap<>();
            restored.keysWithPrefix("k").forEach(k -> found.put(k,
                new String(restored.get(k).orElseThrow(), java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(atInstant, found);
        }
        // um cluster novo nasce da base restaurada, com o diretório do Raft vazio
        Path reborn = dir.resolve("novo");
        for (String id : IDS) {
            copy(present.prepared().directory(), reborn.resolve(id).resolve("db"));
        }
        try (ClusterHarness cluster = ClusterHarness.rocks(reborn, RaftOptions.defaults(),
                IDS.toArray(String[]::new)).startAll()) {
            ClusterRuntime leader = cluster.awaitLeader();
            assertEquals(latest, data(leader));
            write(leader, "novo", "escrito-depois-do-desastre");
            cluster.awaitConverged();
            cluster.nodes().forEach(n -> assertEquals(data(leader), data(n), n.config().nodeId()));
        }
        assertFalse(Files.exists(original));
        assertThrows(IllegalStateException.class, () -> DrRestore.restore(store,
            new ArchiveLayout("axon-dr", "outra", "g"), dir.resolve("work-x"), dir.resolve("x"), null, 2));
    }
}
