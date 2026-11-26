package com.axonbase.core.shard;

import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: gateway, escrita e leitura por chave")
class ShardGatewayWriteTest {

    @TempDir
    Path dir;

    private static GatewayFixture orders() {
        GatewayFixture fixture = GatewayFixture.memory(3);
        fixture.sql("DEFINE TABLE pedido SCHEMALESS SHARD BY cliente");
        return fixture;
    }

    private static ShardException refused(GatewayFixture fixture, String sql) {
        ShardException refused = assertThrows(ShardException.class, () -> fixture.sql(sql), sql);
        assertEquals(ShardException.Code.REFUSED, refused.code(), sql + ": " + refused.getMessage());
        return refused;
    }

    private static int total(GatewayFixture fixture, String table) {
        int total = 0;
        for (int shard = 0; shard < fixture.shards.size(); shard++) {
            total += fixture.count(shard, table);
        }
        return total;
    }

    @Test
    void definicoesVaoATodosOsShardsEAChaveEntraNoMapa() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("DEFINE INDEX por_status ON TABLE pedido COLUMNS status");

            for (int shard = 0; shard < 3; shard++) {
                var table = fixture.shards.get(shard).findDatabase("n", "d").catalog().table("pedido");
                assertEquals("cliente", table.shardBy());
                assertTrue(table.indexes().containsKey("por_status"));
            }
            assertEquals("cliente",
                fixture.store.require().shardKey(new ShardMap.TableKey("n", "d", "pedido")).orElseThrow());
        }
    }

    @Test
    void cadaRegistroVaiParaODonoDaChave() {
        try (GatewayFixture fixture = orders()) {
            for (int i = 0; i < 60; i++) {
                fixture.sql("CREATE pedido SET cliente = 'c" + (i % 20) + "', n = " + i);
            }

            assertEquals(60, total(fixture, "pedido"));
            for (int shard = 0; shard < 3; shard++) {
                for (AxonValue row : fixture.onShard(shard, "SELECT * FROM pedido").asArray()) {
                    assertEquals(shard, fixture.ownerOf(row.asObject().get("cliente")));
                }
                assertTrue(fixture.count(shard, "pedido") > 0, "shard " + shard + " sem registros");
            }
        }
    }

    @Test
    void consultaPelaChaveTrazSoOsRegistrosDela() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("CREATE pedido SET cliente = 'ana', n = 1; CREATE pedido SET cliente = 'bia', n = 2;"
                + " CREATE pedido SET cliente = 'ana', n = 3");

            AxonValue rows = fixture.sql("SELECT VALUE n FROM pedido WHERE cliente = $c ORDER BY n",
                Map.of("c", AxonValue.str("ana")));

            assertEquals(List.of(AxonValue.num(1), AxonValue.num(3)), rows.asArray());
        }
    }

    @Test
    void comShardByIdOGatewayGeraOIdERoteiaPorEle() {
        try (GatewayFixture fixture = GatewayFixture.memory(3)) {
            fixture.sql("DEFINE TABLE evento SHARD BY id");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                AxonValue created = fixture.sql("CREATE evento SET x = " + i);
                ids.add(created.asArray().get(0).asObject().get("id").toString().replace("\"", ""));
            }
            fixture.sql("CREATE evento:fixo SET x = 99");

            assertEquals(30, ids.size());
            assertEquals(31, total(fixture, "evento"));
            assertEquals(AxonValue.num(99), fixture.sql("SELECT * FROM evento:fixo").asObject().get("x"));
            for (String id : ids) {
                AxonValue found = fixture.sql("SELECT * FROM " + id);
                assertTrue(found.isObject(), "não achou " + id);
            }
        }
    }

    @Test
    void insertEspalhadoDevolveNaOrdemDoPedido() {
        try (GatewayFixture fixture = orders()) {
            List<AxonValue> rows = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                rows.add(AxonValue.object(Map.of("cliente", AxonValue.str("c" + i), "n", AxonValue.num(i))));
            }

            AxonValue created = fixture.sql("INSERT INTO pedido $linhas",
                Map.of("linhas", AxonValue.array(rows)));

            for (int i = 0; i < 30; i++) {
                assertEquals(AxonValue.num(i), created.asArray().get(i).asObject().get("n"));
            }
            assertEquals(30, total(fixture, "pedido"));
            assertTrue(fixture.count(0, "pedido") < 30);
        }
    }

    @Test
    void escritaSemAChaveERecusada() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "CREATE pedido SET n = 1");
            refused(fixture, "INSERT INTO pedido [{ n: 1 }]");
            refused(fixture, "CREATE pedido SET cliente = outra_coisa");
            assertEquals(0, total(fixture, "pedido"));
        }
    }

    @Test
    void idEscolhidoEmTabelaDivididaPorOutroCampoERecusadoPorPadrao() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "CREATE pedido:1 SET cliente = 'ana'");
            assertEquals(0, total(fixture, "pedido"));
        }
    }

    @Test
    void comAChecagemOIdEscolhidoEAceitoUmaVezSo() {
        try (GatewayFixture fixture = GatewayFixture.memory(3, ShardGateway.Options.defaults()
            .withMapTtl(Duration.ZERO).withCheckExplicitIds(true))) {
            fixture.sql("DEFINE TABLE pedido SHARD BY cliente");
            String first = fixture.keyOn(0);
            String other = fixture.keyOn(1);

            fixture.sql("CREATE pedido:1 SET cliente = '" + first + "'");
            refused(fixture, "CREATE pedido:1 SET cliente = '" + other + "'");

            assertEquals(1, total(fixture, "pedido"));
        }
    }

    @Test
    void atualizacaoEExclusaoPelaChaveFicamNumShard() {
        try (GatewayFixture fixture = orders()) {
            String ana = fixture.keyOn(0);
            String bia = fixture.keyOn(1);
            fixture.sql("CREATE pedido SET cliente = '" + ana + "', n = 1; CREATE pedido SET cliente = '" + bia
                + "', n = 2");

            AxonValue updated = fixture.sql("UPDATE pedido SET status = 'pago' WHERE cliente = '" + ana + "'");
            assertEquals(1, updated.asArray().size());
            assertEquals(AxonValue.str("pago"),
                fixture.onShard(0, "SELECT VALUE status FROM pedido").asArray().get(0));

            fixture.sql("DELETE pedido WHERE cliente = '" + bia + "'");
            assertEquals(1, total(fixture, "pedido"));
        }
    }

    @Test
    void escritaEmVariosShardsForaDeSagaERecusada() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("CREATE pedido SET cliente = 'ana', n = 1");

            refused(fixture, "UPDATE pedido SET n = 2");
            refused(fixture, "DELETE pedido WHERE n > 0");

            assertEquals(1, total(fixture, "pedido"));
        }
    }

    @Test
    void escritaPorRecordIdEmTabelaDivididaPorCampoAchaORegistro() {
        try (GatewayFixture fixture = orders()) {
            String id = fixture.sql("CREATE pedido SET cliente = 'ana', n = 1").asArray().get(0).asObject()
                .get("id").toString().replace("\"", "");
            fixture.sql("CREATE pedido SET cliente = 'bia', n = 1");

            AxonValue updated = fixture.sql("UPDATE " + id + " SET n = 7");

            assertEquals(1, updated.asArray().size());
            long changed = 0;
            for (int shard = 0; shard < 3; shard++) {
                changed += fixture.onShard(shard, "SELECT * FROM pedido WHERE n = 7").asArray().size();
            }
            assertEquals(1, changed);
            fixture.sql("DELETE " + id);
            assertEquals(1, total(fixture, "pedido"));
        }
    }

    @Test
    void aChaveDeShardNaoMuda() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("CREATE pedido SET cliente = 'ana', n = 1");

            refused(fixture, "UPDATE pedido SET cliente = 'bia' WHERE cliente = 'ana'");
            refused(fixture, "UPDATE pedido CONTENT { n: 2 } WHERE cliente = 'ana'");
            refused(fixture, "UPDATE pedido MERGE { cliente: 'bia' } WHERE cliente = 'ana'");
            fixture.sql("UPDATE pedido SET cliente = 'ana', n = 5 WHERE cliente = 'ana'");
            fixture.sql("UPDATE pedido CONTENT { cliente: 'ana', n: 6 } WHERE cliente = 'ana'");

            assertEquals(AxonValue.num(6),
                fixture.sql("SELECT VALUE n FROM pedido WHERE cliente = 'ana'").asArray().get(0));
        }
    }

    @Test
    void upsertPrecisaCairNumShardETrazerAChave() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "UPSERT pedido SET n = 1");
            refused(fixture, "UPSERT pedido SET n = 1 WHERE cliente = 'ana'");

            fixture.sql("UPSERT pedido SET cliente = 'ana', n = 1 WHERE cliente = 'ana'");
            fixture.sql("UPSERT pedido SET cliente = 'ana', n = 2 WHERE cliente = 'ana'");

            assertEquals(1, total(fixture, "pedido"));
            assertEquals(AxonValue.num(2),
                fixture.sql("SELECT VALUE n FROM pedido WHERE cliente = 'ana'").asArray().get(0));
        }
    }

    @Test
    void blocoNumShardSoEUmaTransacao() {
        try (GatewayFixture fixture = orders()) {
            String ana = fixture.keyOn(0);
            String bia = fixture.keyOn(1);

            fixture.sql("BEGIN; CREATE pedido SET cliente = '" + ana + "', n = 1; INSERT INTO pedido [{cliente: '"
                + ana + "', n: 2}]; COMMIT");
            assertEquals(2, total(fixture, "pedido"));

            assertThrows(RuntimeException.class, () -> fixture.sql(
                "BEGIN; CREATE pedido SET cliente = '" + ana + "', n = 3; ERROR 'parou'; COMMIT"));
            fixture.sql("BEGIN; CREATE pedido SET cliente = '" + ana + "', n = 4; CANCEL");
            refused(fixture, "BEGIN; CREATE pedido SET cliente = '" + ana + "'; CREATE pedido SET cliente = '"
                + bia + "'; COMMIT");
            refused(fixture, "BEGIN; CREATE pedido SET cliente = '" + ana + "'");

            assertEquals(2, total(fixture, "pedido"));
        }
    }

    @Test
    void sagaCanceladaDesfazEmTodosOsShards() {
        try (GatewayFixture fixture = orders()) {
            String ana = fixture.keyOn(0);
            String bia = fixture.keyOn(1);
            fixture.sql("CREATE pedido SET cliente = '" + bia + "', n = 100");

            fixture.sql("BEGIN SAGA troca WITH CORRELATION 'c1'");
            fixture.sql("CREATE pedido SET cliente = '" + ana + "', n = 1");
            fixture.sql("CREATE pedido SET cliente = '" + bia + "', n = 2");
            fixture.sql("UPDATE pedido SET n = 101 WHERE cliente = '" + bia + "' AND n = 100");
            assertEquals(3, total(fixture, "pedido"));

            // outra sessão (outro gateway) consegue cancelar
            fixture.gateway.execute(new GatewaySession("n", "d"), "CANCEL SAGA troca WITH CORRELATION 'c1'", null);

            assertEquals(1, total(fixture, "pedido"));
            assertEquals(AxonValue.num(100), fixture.sql("SELECT VALUE n FROM pedido WHERE cliente = '" + bia + "'")
                .asArray().get(0));
        }
    }

    @Test
    void sagaConfirmadaMantemENaoAceitaMaisPassos() {
        try (GatewayFixture fixture = orders()) {
            String ana = fixture.keyOn(0);
            String bia = fixture.keyOn(2);

            fixture.sql("BEGIN SAGA troca WITH CORRELATION 'c2';"
                + " CREATE pedido SET cliente = '" + ana + "'; CREATE pedido SET cliente = '" + bia + "';"
                + " COMMIT SAGA troca WITH CORRELATION 'c2'");

            assertEquals(2, total(fixture, "pedido"));
            refused(fixture, "JOIN SAGA troca WITH CORRELATION 'c2'");
            refused(fixture, "CANCEL SAGA troca WITH CORRELATION 'c2'");
        }
    }

    @Test
    void tabelaSemChaveFicaNoShardBase() {
        try (GatewayFixture fixture = orders()) {
            for (int i = 0; i < 5; i++) {
                fixture.sql("CREATE cidade:" + i + " SET nome = 'x'");
            }
            fixture.sql("UPDATE cidade SET nome = 'y'");

            assertEquals(5, fixture.count(0, "cidade"));
            assertEquals(0, fixture.onShard(1, "SELECT * FROM cidade").asArray().size());
            assertEquals(5, fixture.sql("SELECT * FROM cidade WHERE nome = 'y'").asArray().size());
        }
    }

    @Test
    void definicoesQueQuebrariamAGarantiaSaoRecusadas() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("CREATE cliente:1 SET nome = 'ana'");

            refused(fixture, "DEFINE TABLE cliente SHARD BY nome");
            refused(fixture, "DEFINE TABLE pedido SHARD BY outro");
            refused(fixture, "DEFINE TABLE pedido SCHEMAFULL");
            refused(fixture, "DEFINE INDEX numero ON TABLE pedido COLUMNS numero UNIQUE");
            refused(fixture, "DEFINE EVENT e ON TABLE pedido WHEN true THEN (CREATE log SET m = 1)");
            fixture.sql("DEFINE INDEX por_cliente ON TABLE pedido COLUMNS cliente, numero UNIQUE");
        }
    }

    @Test
    void recursosQueAtravessamShardsSaoRecusados() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "SELECT * FROM pedido FETCH cliente");
            refused(fixture, "SELECT * FROM pedido WHERE cliente INSIDE (SELECT VALUE id FROM pedido)");
            refused(fixture, "SELECT ->tem->item FROM pedido");
            refused(fixture, "LIVE SELECT * FROM pedido");
            refused(fixture, "RELATE pedido:1->tem->item:2");
            refused(fixture, "SELECT * FROM cidade WHERE id INSIDE (SELECT VALUE id FROM pedido)");
        }
    }

    @Test
    void explicarMostraORoteamento() {
        try (GatewayFixture fixture = orders()) {
            AxonValue one = fixture.sql("EXPLAIN SELECT * FROM pedido WHERE cliente = 'ana'");
            AxonValue all = fixture.sql("EXPLAIN SELECT * FROM pedido");

            assertEquals(AxonValue.str("single"), one.asObject().get("routing"));
            assertEquals(AxonValue.str("scatter"), all.asObject().get("routing"));
            assertEquals(3, all.asObject().get("shards").asArray().size());
        }
    }

    @Test
    void trocaDeLiderNumShardNaoAfetaOGateway() {
        try (ShardTestCluster cluster = ShardTestCluster.memory(dir, 3, 3)) {
            ShardMapStore store = new ShardMapStore(cluster.client("s0"));
            store.bootstrap(ShardMap.initial(cluster.shardInfos()));
            try (ShardGateway gateway = new ShardGateway(store, info -> cluster.client(info.id()),
                ShardGateway.Options.defaults(), HybridClock.system())) {
                GatewaySession session = new GatewaySession("n", "d");
                gateway.execute(session, "DEFINE TABLE pedido SHARD BY cliente", null);
                for (int i = 0; i < 15; i++) {
                    gateway.execute(session, "CREATE pedido SET cliente = 'c" + i + "'", null);
                }

                cluster.stop("s1", cluster.awaitLeader("s1").id());
                for (int i = 15; i < 30; i++) {
                    gateway.execute(session, "CREATE pedido SET cliente = 'c" + i + "'", null);
                }

                int found = 0;
                for (int i = 0; i < 30; i++) {
                    found += gateway.execute(session, "SELECT * FROM pedido WHERE cliente = 'c" + i + "'", null)
                        .asArray().size();
                }
                assertEquals(30, found);
            }
        }
    }
}
