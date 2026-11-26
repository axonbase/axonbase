package com.axonbase.core.shard.backup;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.journal.PitrException;
import com.axonbase.core.shard.GatewaySession;
import com.axonbase.core.shard.ShardGateway;
import com.axonbase.core.shard.ShardMap;
import com.axonbase.core.shard.ShardMapStore;
import com.axonbase.core.shard.ShardTestCluster;
import com.axonbase.core.shard.SlotHash;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: backup do cluster e restauração a um instante")
class ClusterBackupTest {

    @TempDir
    Path dir;

    private static final String ALL = "SELECT * FROM passo ORDER BY id";

    /** Um conjunto de shards com disco e journal, um gateway e o coordenador do backup. */
    private static final class Fleet implements AutoCloseable {
        final ShardTestCluster cluster;
        final ShardMapStore store;
        final ShardGateway gateway;
        final ClusterBackup backups;

        Fleet(ShardTestCluster cluster, boolean bootstrap) {
            this.cluster = cluster;
            this.store = new ShardMapStore(cluster.client("s0"));
            if (bootstrap) {
                store.bootstrap(ShardMap.initial(cluster.shardInfos()));
            }
            this.gateway = new ShardGateway(store, info -> cluster.client(info.id()),
                ShardGateway.Options.defaults().withMapTtl(Duration.ofMillis(200)), HybridClock.system());
            this.backups = new ClusterBackup(store, info -> cluster.backupMembers(info.id()), Duration.ofSeconds(60));
        }

        AxonValue sql(String script) {
            return gateway.execute(new GatewaySession("n", "d"), script, null);
        }

        String snapshot() {
            return AxonJson.write(sql(ALL));
        }

        @Override
        public void close() {
            gateway.close();
            cluster.close();
        }
    }

    private Fleet fleet(int shards, int members) {
        Fleet fleet = new Fleet(ShardTestCluster.rocks(dir.resolve("original"), shards, members), true);
        fleet.sql("DEFINE TABLE passo SHARD BY id");
        return fleet;
    }

    private static Map<String, ClusterRestore.Source> sources(ClusterManifest manifest) {
        Map<String, ClusterRestore.Source> out = new LinkedHashMap<>();
        manifest.shards().forEach(s -> out.put(s.shard(),
            new ClusterRestore.Source(Path.of(s.backupDir()), Path.of(s.journalDir()))));
        return out;
    }

    private Fleet restore(ClusterManifest manifest, HlcTimestamp target, String name, int members) {
        Path destination = dir.resolve(name);
        ClusterRestore.restore(manifest, target, sources(manifest), destination);
        Map<String, Path> data = new LinkedHashMap<>();
        manifest.shards().forEach(s -> data.put(s.shard(), destination.resolve(s.shard())));
        return new Fleet(ShardTestCluster.restored(dir.resolve(name + "-cluster"), data, members), false);
    }

    @Test
    void backupRestauraOCorteEORestauradoAceitaEscritas() throws IOException {
        try (Fleet original = fleet(2, 3)) {
            for (int i = 0; i < 40; i++) {
                original.sql("CREATE passo:" + i + " SET n = " + i);
            }
            String atCut = original.snapshot();
            ClusterManifest manifest = original.backups.create();
            original.sql("CREATE passo:999 SET n = 999");

            Path file = dir.resolve("manifesto.json");
            manifest.write(file);
            assertEquals(manifest, ClusterManifest.read(file));

            try (Fleet restored = restore(manifest, null, "restaurado", 3)) {
                assertEquals(atCut, restored.snapshot());
                restored.sql("CREATE passo:1000 SET n = 1000");
                assertEquals(1, restored.sql("SELECT * FROM passo WHERE n = 1000").asArray().size());
                // o mapa volta junto com o shard base
                assertEquals(original.store.require(), restored.store.require());
            }
        }
    }

    @Test
    void restauracaoAUmInstanteDepoisDoCorteTrazExatamenteOEstadoDele() {
        try (Fleet original = fleet(3, 1)) {
            original.sql("CREATE passo:1 SET n = 1");
            ClusterManifest manifest = original.backups.create();
            for (int i = 2; i < 30; i++) {
                original.sql("CREATE passo:" + i + " SET n = " + i);
            }
            original.sql("UPDATE passo:1 SET n = 100");
            HlcTimestamp instant = original.backups.barrier();
            String atInstant = original.snapshot();
            original.sql("DELETE passo:2");
            original.sql("CREATE passo:500 SET n = 500");
            original.backups.barrier();
            original.backups.sealJournals(manifest);

            try (Fleet restored = restore(manifest, instant, "no-instante", 1)) {
                assertEquals(atInstant, restored.snapshot());
            }
            try (Fleet restored = restore(manifest, null, "no-corte", 1)) {
                assertEquals(1, restored.sql(ALL).asArray().size());
            }
        }
    }

    @Test
    void cortesSobEscritaMantemACausalidadeEntreShards() throws Exception {
        try (Fleet original = fleet(2, 3)) {
            // duas chaves em shards diferentes: cada passo grava no primeiro e só então no segundo
            int first = idOn(original.store.require(), "s0", 0);
            int second = idOn(original.store.require(), "s1", 0);
            original.sql("CREATE passo:" + first + " SET seq = 0; CREATE passo:" + second + " SET seq = 0");
            AtomicBoolean writing = new AtomicBoolean(true);
            AtomicInteger confirmed = new AtomicInteger();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<?> writer = pool.submit(() -> {
                for (int i = 1; writing.get(); i++) {
                    original.sql("UPDATE passo:" + first + " SET seq = " + i);
                    original.sql("UPDATE passo:" + second + " SET seq = " + i);
                    confirmed.set(i);
                }
            });
            ShardTestCluster.await("sem escritas antes do backup", () -> confirmed.get() > 5);
            int before = confirmed.get();
            ClusterManifest manifest = original.backups.create();
            int after = confirmed.get() + 1;
            writing.set(false);
            writer.get(60, TimeUnit.SECONDS);
            pool.shutdown();

            try (Fleet restored = restore(manifest, null, "causal", 1)) {
                long a = seq(restored, first);
                long b = seq(restored, second);
                // b nunca passa de a (b é escrito depois de a ser confirmado), e a está no máximo um passo à frente
                assertTrue(b <= a && a - b <= 1, "a=" + a + " b=" + b);
                assertTrue(a >= before && a <= after, "a=" + a + " fora de [" + before + ", " + after + "]");
            }
        }
    }

    @Test
    void instanteForaDoAlcanceEAnteriorAoCorteSaoRecusadosSemDeixarNada() {
        try (Fleet original = fleet(2, 1)) {
            original.sql("CREATE passo:1 SET n = 1");
            ClusterManifest manifest = original.backups.create();
            HlcTimestamp future = new HlcTimestamp(manifest.cut().physical() + 3_600_000, 0);

            PitrException unreachable = assertThrows(PitrException.class,
                () -> ClusterRestore.restore(manifest, future, sources(manifest), dir.resolve("futuro")));
            PitrException early = assertThrows(PitrException.class, () -> ClusterRestore.restore(manifest,
                new HlcTimestamp(manifest.cut().physical() - 60_000, 0), sources(manifest), dir.resolve("antes")));

            assertEquals(PitrException.Reason.UNREACHABLE, unreachable.reason());
            assertEquals(PitrException.Reason.BASE_TOO_NEW, early.reason());
            assertTrue(empty(dir.resolve("futuro")));
            assertTrue(empty(dir.resolve("antes")));
        }
    }

    @Test
    void journalComBuracoNumShardRecusaARestauracaoInteira() throws IOException {
        try (Fleet original = fleet(2, 1)) {
            ClusterManifest manifest = original.backups.create();
            int onSecond = idOn(original.store.require(), "s1", 0);
            original.sql("CREATE passo:" + onSecond + " SET n = 1");
            original.backups.sealJournals(manifest);
            original.sql("CREATE passo:" + idOn(original.store.require(), "s1", onSecond + 1) + " SET n = 2");
            HlcTimestamp instant = original.backups.barrier();
            original.backups.barrier();
            original.backups.sealJournals(manifest);
            // some o segmento que tem a primeira escrita depois do backup no segundo shard
            Path journal = Path.of(manifest.shard("s1").journalDir());
            removeSegmentHolding(journal, manifest.shard("s1").barrierIndex() + 1);

            PitrException hole = assertThrows(PitrException.class,
                () -> ClusterRestore.restore(manifest, instant, sources(manifest), dir.resolve("buraco")));

            assertEquals(PitrException.Reason.UNREACHABLE, hole.reason());
            assertTrue(empty(dir.resolve("buraco")), "nenhum shard pode ficar restaurado sozinho");
        }
    }

    @Test
    void backupFuncionaDepoisDeUmaTrocaDeLider() {
        try (Fleet original = fleet(2, 3)) {
            original.sql("CREATE passo:1 SET n = 1");
            original.cluster.stop("s1", original.cluster.awaitLeader("s1").id());
            original.cluster.awaitLeader("s1");
            for (int i = 2; i < 12; i++) {
                original.sql("CREATE passo:" + i + " SET n = " + i);
            }
            String expected = original.snapshot();

            ClusterManifest manifest = original.backups.create();

            try (Fleet restored = restore(manifest, null, "depois-da-troca", 1)) {
                assertEquals(expected, restored.snapshot());
            }
        }
    }

    @Test
    void instanteDepoisDeUmaMovimentacaoERecusadoEOCorteContinuaValendo() {
        try (Fleet original = fleet(2, 1)) {
            for (int i = 0; i < 20; i++) {
                original.sql("CREATE passo:" + i + " SET n = " + i);
            }
            String atCut = original.snapshot();
            ClusterManifest manifest = original.backups.create();
            new com.axonbase.core.shard.ShardMover(original.store, info -> original.cluster.client(info.id()),
                Duration.ofMillis(500), HybridClock.system())
                .move(List.of(new com.axonbase.core.shard.SlotRange(0, 511)), "s1");
            HlcTimestamp instant = original.backups.barrier();
            original.backups.barrier();
            original.backups.sealJournals(manifest);

            PitrException moved = assertThrows(PitrException.class,
                () -> ClusterRestore.restore(manifest, instant, sources(manifest), dir.resolve("movido")));

            assertTrue(moved.getMessage().contains("slots"), moved.getMessage());
            assertTrue(empty(dir.resolve("movido")));
            try (Fleet restored = restore(manifest, null, "antes-de-mover", 1)) {
                assertEquals(atCut, restored.snapshot());
            }
        }
    }

    @Test
    void naoHaBackupDuranteUmaMovimentacao() {
        try (Fleet original = fleet(2, 1)) {
            original.store.update(map -> map.startMigration(
                List.of(new com.axonbase.core.shard.SlotRange(0, 9)), "s1"));

            assertThrows(com.axonbase.core.shard.ShardException.class, original.backups::create);
            assertFalse(original.store.require().migration().isEmpty());
        }
    }

    private static long seq(Fleet fleet, int id) {
        return fleet.sql("SELECT * FROM passo:" + id).asObject().get("seq").asLong();
    }

    private static int idOn(ShardMap map, String shard, int from) {
        for (int i = from; ; i++) {
            if (map.ownerOf(SlotHash.ofValue(AxonValue.num(i))).equals(shard)) {
                return i;
            }
        }
    }

    private static boolean empty(Path directory) {
        if (!Files.exists(directory)) {
            return true;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.findAny().isEmpty();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void removeSegmentHolding(Path journal, long fromIndex) throws IOException {
        for (var segment : com.axonbase.core.journal.JournalSegments.list(journal)) {
            if (segment.batches() > 0 && segment.lastIndex() >= fromIndex) {
                Files.delete(journal.resolve(segment.name()));
                return;
            }
        }
        throw new AssertionError("nenhum segmento a partir do índice " + fromIndex);
    }
}
