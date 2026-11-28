package com.axonbase.core.shard;

import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: o que o gateway recusa para não perder, duplicar nem expor dados")
class ShardGatewaySafetyTest {

    private static final List<SlotRange> FIRST_SLOTS = List.of(new SlotRange(0, 699));

    private static GatewayFixture orders() {
        GatewayFixture fixture = GatewayFixture.memory(3);
        fixture.sql("DEFINE TABLE pedido SCHEMALESS SHARD BY cliente");
        return fixture;
    }

    private static void refused(GatewayFixture fixture, String sql) {
        ShardException failure = assertThrows(ShardException.class, () -> fixture.sql(sql), sql);
        assertEquals(ShardException.Code.REFUSED, failure.code(), sql + ": " + failure.getMessage());
    }

    private static int total(GatewayFixture fixture, String table) {
        int total = 0;
        for (int shard = 0; shard < fixture.shards.size(); shard++) {
            total += fixture.count(shard, table);
        }
        return total;
    }

    private static ShardMover mover(GatewayFixture fixture, Function<ShardInfo, ShardClient> clients) {
        return new ShardMover(fixture.store, clients, Duration.ZERO, HybridClock.system());
    }

    private static ShardMover mover(GatewayFixture fixture) {
        return mover(fixture, info -> fixture.clients.get(info.id()));
    }

    /** Clientes que avisam depois de cada requisição atendida por um shard. */
    private static Function<ShardInfo, ShardClient> after(GatewayFixture fixture, String shard,
                                                          Consumer<ShardRequest> hook) {
        return info -> request -> {
            ShardReply reply = fixture.clients.get(info.id()).execute(request);
            if (info.id().equals(shard)) {
                hook.accept(request);
            }
            return reply;
        };
    }

    @Test
    void oGatewayNaoExecutaInstrucoesNemCalculaOQueDependeDoShard() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("CREATE cidade:1 SET nome = 'recife'");

            refused(fixture, "LET $x = { DELETE cidade; RETURN 1 }");
            refused(fixture, "INSERT INTO pedido { cliente: 'a', dono: $auth }");
            refused(fixture, "CREATE pedido SET cliente = { DELETE cidade; RETURN 'a' }");
            refused(fixture, "SELECT count() + record::exists(cidade:1) FROM pedido GROUP ALL");

            assertEquals(1, fixture.count(0, "cidade"));
            assertEquals(0, total(fixture, "pedido"));
        }
    }

    @Test
    void blocosNaoContornamAsRecusas() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "CREATE cidade:2 SET x = { CREATE pedido SET cliente = 'a' }");
            refused(fixture, "UPDATE pedido SET x = { CREATE cidade:3 } WHERE cliente = 'a'");

            assertEquals(0, total(fixture, "pedido"));
            assertEquals(0, total(fixture, "cidade"));
        }
    }

    @Test
    void nomesQueNaoSaoIdentificadoresNaoEntramNoMapa() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "DEFINE TABLE `a; DELETE pedido` SHARD BY cliente");

            assertEquals(1, fixture.store.require().tables().size());
        }
    }

    @Test
    void permissoesNaoConvivemComTabelaDividida() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "DEFINE TABLE a SHARD BY cliente PERMISSIONS FOR select WHERE n > 1");
            refused(fixture, "DEFINE TABLE pedido SHARD BY cliente PERMISSIONS FOR select WHERE n > 1");
            fixture.sql("DEFINE TABLE b SCHEMALESS PERMISSIONS FOR select WHERE n > 1");
            fixture.sql("CREATE b:1 SET n = 0");

            // o registro que a permissão esconde ainda conta: a tabela não está vazia e as regras ficam de fora
            refused(fixture, "DEFINE TABLE b SHARD BY cliente");
            assertEquals(1, fixture.store.require().tables().size());
        }
    }

    @Test
    void aChaveGravadaPrecisaSerARoteada() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("CREATE pedido SET cliente = 'ana', n = 1");

            refused(fixture, "DEFINE FIELD cliente ON TABLE pedido TYPE bool");
            refused(fixture, "UPDATE pedido PATCH { cliente: 'bia' } WHERE cliente = 'ana'");
            refused(fixture, "UPSERT pedido:9 SET cliente = 'bia' WHERE cliente = 'bia'");
            refused(fixture, "CREATE pedido SET cliente = 1.5");
            refused(fixture, "CREATE DATA RULE dono APPLY cliente = 'x'");
            fixture.sql("DEFINE FIELD cliente ON TABLE pedido TYPE string");

            fixture.sql("DEFINE TABLE a SCHEMALESS");
            fixture.sql("DEFINE FIELD dono ON TABLE a TYPE bool");
            refused(fixture, "DEFINE TABLE a SHARD BY dono");
            fixture.sql("CREATE DATA RULE regra APPLY setor = 'x'");
            refused(fixture, "DEFINE TABLE c SHARD BY setor");
        }
    }

    @Test
    void eventosDeOutrasTabelasNaoEscrevemEmTabelaDividida() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "DEFINE EVENT e ON TABLE cidade WHEN true THEN (CREATE pedido SET cliente = 'a')");
            fixture.sql("DEFINE EVENT f ON TABLE cidade WHEN true THEN (CREATE log SET m = 1)");
            refused(fixture, "DEFINE TABLE log SHARD BY cliente");
        }
    }

    @Test
    void numeroETextoNumericoMoramNoMesmoShard() {
        assertEquals(SlotHash.ofValue(AxonValue.num(7)), SlotHash.ofValue(AxonValue.str("007")));
        assertEquals(SlotHash.ofValue(AxonValue.num(7)), SlotHash.ofValue(AxonValue.str("7.0")));
        try (GatewayFixture fixture = orders()) {
            for (int i = 0; i < 12; i++) {
                fixture.sql("CREATE pedido SET cliente = '" + i + "'");
            }
            for (int i = 0; i < 12; i++) {
                assertEquals(1, fixture.sql("SELECT * FROM pedido WHERE cliente = " + i).asArray().size());
            }
        }
    }

    @Test
    void origensQueNaoDizemATabelaSaoRecusadas() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("LET $ids = [cidade:1]");

            refused(fixture, "SELECT * FROM $ids");
            refused(fixture, "SELECT * FROM [cidade:1]");
        }
    }

    @Test
    void letDentroDoBlocoERecusado() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "LET $c = 'a'; BEGIN; LET $c = 'b'; CREATE pedido SET cliente = $c; COMMIT");

            assertEquals(0, total(fixture, "pedido"));
        }
    }

    @Test
    void escritaEspalhadaParaQuandoOMapaMuda() {
        try (GatewayFixture fixture = orders()) {
            AtomicBoolean once = new AtomicBoolean();
            Function<ShardInfo, ShardClient> changing = after(fixture, "s0", request -> {
                if (request.sql().startsWith("INSERT") && once.compareAndSet(false, true)) {
                    fixture.store.update(map -> map.startMigration(List.of(new SlotRange(4000, 4095)), "s0"));
                }
            });
            try (ShardGateway gateway = new ShardGateway(fixture.store, changing,
                ShardGateway.Options.defaults().withMapTtl(Duration.ZERO), HybridClock.system())) {
                String rows = "INSERT INTO pedido [{cliente: '" + fixture.keyOn(0) + "'}, {cliente: '"
                    + fixture.keyOn(1) + "'}]";

                ShardException partial = assertThrows(ShardException.class,
                    () -> gateway.execute(fixture.session(), rows, null));

                assertEquals(ShardException.Code.PARTIAL_WRITE, partial.code());
                assertEquals(1, total(fixture, "pedido"));
            }
        }
    }

    @Test
    void sagaAbertaNaOrigemImpedeMoverECancelarEsperaAMovimentacao() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("BEGIN SAGA s WITH CORRELATION 'c'; CREATE pedido SET cliente = '" + fixture.keyOn(0)
                + "'");

            ShardException open = assertThrows(ShardException.class,
                () -> mover(fixture).move(FIRST_SLOTS, "s2"));
            assertEquals(ShardException.Code.REFUSED, open.code(), open.getMessage());
            assertTrue(fixture.store.require().migration().isEmpty());

            fixture.store.update(map -> map.startMigration(List.of(new SlotRange(4000, 4095)), "s0"));
            assertThrows(ShardException.class, () -> fixture.sql("CANCEL SAGA s WITH CORRELATION 'c'"));
            fixture.store.update(ShardMap::abortMigration);

            fixture.sql("CANCEL SAGA s WITH CORRELATION 'c'");
            mover(fixture).move(FIRST_SLOTS, "s2");
            assertEquals(0, total(fixture, "pedido"));
        }
    }

    @Test
    void quemPerdeAMovimentacaoParaSemApagarNada() {
        try (GatewayFixture fixture = orders()) {
            List<AxonValue> rows = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                rows.add(AxonValue.object(Map.of("cliente", AxonValue.str("c" + i))));
            }
            fixture.sql("INSERT INTO pedido $linhas RETURN NONE", Map.of("linhas", AxonValue.array(rows)));
            AtomicInteger aborted = new AtomicInteger();
            // outra execução desiste logo depois que a primeira copiou para o destino
            Function<ShardInfo, ShardClient> interrupted = after(fixture, "s2", request -> {
                if (request.sql().startsWith("INSERT") && aborted.compareAndSet(0, 1)) {
                    mover(fixture).abort();
                }
            });

            ShardException superseded = assertThrows(ShardException.class,
                () -> mover(fixture, interrupted).move(FIRST_SLOTS, "s2"));

            assertEquals(ShardException.Code.STALE_MAP, superseded.code(), superseded.getMessage());
            assertEquals(1, aborted.get());
            assertTrue(fixture.store.require().migration().isEmpty());
            assertEquals("s0", fixture.store.require().ownerOf(0));
            assertEquals(200, total(fixture, "pedido"));
            assertEquals(0, fixture.onShard(2, "SELECT * FROM pedido").asArray().stream()
                .filter(row -> fixture.ownerOf(row.asObject().get("cliente")) != 2).count());
        }
    }

    @Test
    void oPrazoDoClienteDoGrupoValeACadaTentativa() {
        AtomicInteger calls = new AtomicInteger();
        ShardMember slow = request -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            throw new ShardUnreachableException("fora", null);
        };
        GroupShardClient client = new GroupShardClient("s0", () -> List.of(slow, slow, slow, slow, slow),
            Duration.ofMillis(150));

        assertThrows(ShardException.class, () -> client.execute(ShardRequest.of("n", "d", "SELECT * FROM t")));

        assertTrue(calls.get() <= 2, "tentativas: " + calls.get());
    }
}
