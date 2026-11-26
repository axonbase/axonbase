package com.axonbase.core.shard;

import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.ClockDriftException;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: cliente de um grupo Raft e execução no shard")
class GroupShardClientTest {

    @TempDir
    Path dir;

    private static ShardRequest sql(String sql) {
        return ShardRequest.of("n", "d", sql);
    }

    private static int count(ShardClient shard, String table) {
        AxonValue rows = shard.execute(sql("SELECT * FROM " + table)).value();
        return rows.asArray().size();
    }

    @Test
    void cadaGrupoGuardaOsPropriosDados() {
        try (ShardTestCluster cluster = ShardTestCluster.memory(dir, 2, 3)) {
            cluster.client("s0").execute(sql("CREATE pedido:1 SET total = 10"));
            cluster.client("s1").execute(sql("CREATE pedido:2 SET total = 20"));
            cluster.client("s1").execute(sql("CREATE pedido:3 SET total = 30"));

            assertEquals(1, count(cluster.client("s0"), "pedido"));
            assertEquals(2, count(cluster.client("s1"), "pedido"));
        }
    }

    @Test
    void trocaDeLiderNumShardNaoInterrompeOClienteNemOOutroShard() {
        try (ShardTestCluster cluster = ShardTestCluster.memory(dir, 2, 3)) {
            ShardClient first = cluster.client("s0");
            ShardClient second = cluster.client("s1");
            first.execute(sql("CREATE pedido:1 SET total = 10"));
            ShardTestCluster.Node oldLeader = cluster.awaitLeader("s0");

            cluster.stop("s0", oldLeader.id());
            second.execute(sql("CREATE pedido:2 SET total = 20"));
            first.execute(sql("CREATE pedido:3 SET total = 30"));

            assertNotEquals(oldLeader.id(), cluster.awaitLeader("s0").id());
            assertEquals(2, count(first, "pedido"));
            assertEquals(1, count(second, "pedido"));
        }
    }

    @Test
    void seguidorRecusaEscritaELeitura() {
        try (ShardTestCluster cluster = ShardTestCluster.memory(dir, 1, 3)) {
            cluster.awaitLeader("s0");
            ShardTestCluster.Node follower = cluster.nodes("s0").stream()
                .filter(node -> node.runtime().role() != ClusterRole.LEADER).findFirst().orElseThrow();

            assertThrows(NotLeaderException.class, () -> follower.endpoint().handle(sql("CREATE t:1")));
            assertThrows(NotLeaderException.class, () -> follower.endpoint().handle(sql("SELECT * FROM t")));
        }
    }

    @Test
    void semQuorumOClienteDesisteSemGravar() {
        try (ShardTestCluster cluster = ShardTestCluster.memory(dir, 1, 3)) {
            ShardTestCluster.Node leader = cluster.awaitLeader("s0");
            for (ShardTestCluster.Node node : cluster.nodes("s0")) {
                if (!node.id().equals(leader.id())) {
                    cluster.stop("s0", node.id());
                }
            }
            ShardClient client = new GroupShardClient("s0",
                () -> cluster.nodes("s0").stream().map(ShardTestCluster.Node::endpoint)
                    .map(ShardMember.class::cast).toList(), Duration.ofSeconds(3));

            RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client.execute(sql("CREATE t:1 SET a = 1")));

            assertTrue(failure instanceof ShardException
                    || failure instanceof com.axonbase.core.cluster.CommitOutcomeUnknownException,
                "falha inesperada: " + failure);
        }
    }

    @Test
    void grupoSemNenhumNoDePeFalhaDentroDoPrazo() {
        ShardClient client = new GroupShardClient("s9", List::of, Duration.ofMillis(300));

        ShardException failure = assertThrows(ShardException.class, () -> client.execute(sql("SELECT * FROM t")));

        assertEquals(ShardException.Code.UNAVAILABLE, failure.code());
    }

    @Test
    void scriptAtomicoNaoGravaNadaQuandoUmaInstrucaoFalha() {
        try (ShardTestCluster cluster = ShardTestCluster.memory(dir, 1, 3)) {
            ShardClient client = cluster.client("s0");
            ShardNode single = new ShardNode(new Datastore(new MemoryBackend()), null, HybridClock.system());
            for (ShardClient target : List.<ShardClient>of(client,
                new GroupShardClient("unico", () -> List.<ShardMember>of(single), Duration.ofSeconds(2)))) {

                assertThrows(RuntimeException.class, () -> target.execute(
                    sql("CREATE t:1 SET a = 1; ERROR 'parou';").asAtomic()));
                assertEquals(0, count(target, "t"));

                target.execute(sql("CREATE t:1 SET a = 1; CREATE t:2 SET a = 2;").asAtomic());
                assertEquals(2, count(target, "t"));
            }
        }
    }

    @Test
    void variaveisChegamAoShard() {
        ShardNode node = new ShardNode(new Datastore(new MemoryBackend()), null, HybridClock.system());

        node.handle(sql("CREATE t:1 SET nome = $nome").withVars(Map.of("nome", AxonValue.str("ana"))));
        AxonValue rows = node.handle(sql("SELECT VALUE nome FROM t")).value();

        assertEquals(AxonValue.str("ana"), rows.asArray().get(0));
    }

    @Test
    void carimboViajaDeUmShardParaOOutro() {
        long now = System.currentTimeMillis();
        // o relógio do segundo shard está atrasado: sem a mensagem, o carimbo dele seria menor que o do primeiro
        HybridClock ahead = new HybridClock(() -> now + 200, 500, event -> { });
        HybridClock behind = new HybridClock(() -> now - 200, 500, event -> { });
        ShardNode first = new ShardNode(new Datastore(new MemoryBackend()), null, ahead);
        ShardNode second = new ShardNode(new Datastore(new MemoryBackend()), null, behind);

        HlcTimestamp a = first.handle(sql("CREATE t:1")).stamp();
        HlcTimestamp b = second.handle(sql("CREATE t:2").withStamp(a)).stamp();

        assertTrue(b.compareTo(a) > 0, a + " deveria vir antes de " + b);
    }

    @Test
    void carimboAlemDoDesvioERecusadoSemExecutar() {
        ShardNode node = new ShardNode(new Datastore(new MemoryBackend()), null, HybridClock.system());
        HlcTimestamp future = new HlcTimestamp(System.currentTimeMillis() + 60_000, 0);

        assertThrows(ClockDriftException.class, () -> node.handle(sql("CREATE t:1").withStamp(future)));

        assertEquals(0, node.handle(sql("SELECT * FROM t")).value().asArray().size());
    }
}
