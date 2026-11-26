package com.axonbase.core.shard;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: plano de controle (mapa gravado no grupo de metadados)")
class ShardMapStoreTest {

    @TempDir
    Path dir;

    private static ShardMap initial() {
        return ShardMap.initial(List.of(new ShardInfo("s0", List.of()), new ShardInfo("s1", List.of())));
    }

    private static ShardClient singleNode() {
        ShardNode node = new ShardNode(new Datastore(new MemoryBackend()), null, HybridClock.system());
        return new GroupShardClient("meta", () -> List.<ShardMember>of(node), Duration.ofSeconds(2));
    }

    @Test
    void semMapaGravadoNaoHaOQueCarregar() {
        ShardMapStore store = new ShardMapStore(singleNode());

        assertTrue(store.load().isEmpty());
        ShardException missing = assertThrows(ShardException.class, store::require);
        assertEquals(ShardException.Code.STALE_MAP, missing.code());
    }

    @Test
    void criaUmaVezEDevolveOQueJaExiste() {
        ShardMapStore store = new ShardMapStore(singleNode());

        ShardMap first = store.bootstrap(initial());
        ShardMap second = store.bootstrap(initial().withShard(new ShardInfo("s2", List.of())));

        assertEquals(initial(), first);
        assertEquals(first, second);
        assertEquals(first, store.require());
    }

    @Test
    void soGravaSobreAEpocaEsperada() {
        ShardMapStore store = new ShardMapStore(singleNode());
        ShardMap base = store.bootstrap(initial());
        ShardMap.TableKey a = new ShardMap.TableKey("n", "d", "a");
        ShardMap.TableKey b = new ShardMap.TableKey("n", "d", "b");

        assertTrue(store.compareAndSet(base.epoch(), base.withTable(a, "id")));
        // quem partiu do mapa velho perde, e o que foi gravado fica
        assertFalse(store.compareAndSet(base.epoch(), base.withTable(b, "id")));

        assertTrue(store.require().shardKey(a).isPresent());
        assertTrue(store.require().shardKey(b).isEmpty());
    }

    @Test
    void epocaPrecisaSerASeguinte() {
        ShardMapStore store = new ShardMapStore(singleNode());
        ShardMap base = store.bootstrap(initial());

        assertThrows(IllegalArgumentException.class, () -> store.compareAndSet(base.epoch(), base));
    }

    @Test
    void mudancasConcorrentesNoClusterNaoSePerdem() throws Exception {
        try (ShardTestCluster cluster = ShardTestCluster.memory(dir, 1, 3)) {
            ShardMapStore store = new ShardMapStore(cluster.client("s0"));
            store.bootstrap(initial());
            ExecutorService pool = Executors.newFixedThreadPool(6);
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String table = "t" + i;
                // cada escritor usa a própria cópia do store, como gateways diferentes
                tasks.add(pool.submit(() -> new ShardMapStore(cluster.client("s0"))
                    .update(map -> map.withTable(new ShardMap.TableKey("n", "d", table), "id"))));
            }
            for (Future<?> task : tasks) {
                task.get(120, TimeUnit.SECONDS);
            }
            pool.shutdown();

            ShardMap result = store.require();
            assertEquals(12, result.tables().size());
            assertEquals(13, result.epoch());
        }
    }

    @Test
    void mapaSobreviveATrocaDeLiderDoGrupoDeMetadados() {
        try (ShardTestCluster cluster = ShardTestCluster.memory(dir, 1, 3)) {
            ShardMapStore store = new ShardMapStore(cluster.client("s0"));
            ShardMap saved = store.bootstrap(initial());

            cluster.stop("s0", cluster.awaitLeader("s0").id());

            assertEquals(saved, store.require());
        }
    }
}
