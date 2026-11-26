package com.axonbase.core.shard;

import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: movimentação de slots")
class ShardMoverTest {

    private static final String BY_CLIENT = "SELECT * FROM pedido ORDER BY n";
    private static final String BY_ID = "SELECT * FROM evento ORDER BY n";

    private static GatewayFixture loaded(ShardGateway.Options options) {
        GatewayFixture fixture = GatewayFixture.memory(3, options);
        fixture.sql("DEFINE TABLE pedido SHARD BY cliente");
        fixture.sql("DEFINE TABLE evento SHARD BY id");
        fixture.sql("DEFINE INDEX por_cliente ON TABLE pedido COLUMNS cliente");
        for (int i = 0; i < 200; i++) {
            fixture.sql("CREATE pedido SET cliente = 'c" + i + "', n = " + i + ", total = " + (i % 13) + ".5");
        }
        for (int i = 0; i < 60; i++) {
            fixture.sql("CREATE evento:" + i + " SET n = " + i);
        }
        return fixture;
    }

    private static GatewayFixture loaded() {
        return loaded(ShardGateway.Options.defaults().withMapTtl(Duration.ZERO));
    }

    private static ShardMover mover(GatewayFixture fixture, Function<ShardInfo, ShardClient> clients) {
        return new ShardMover(fixture.store, clients, Duration.ZERO, HybridClock.system());
    }

    private static ShardMover mover(GatewayFixture fixture) {
        return mover(fixture, info -> fixture.clients.get(info.id()));
    }

    private static String snapshot(GatewayFixture fixture) {
        return AxonJson.write(fixture.sql(BY_CLIENT)) + AxonJson.write(fixture.sql(BY_ID))
            + AxonJson.write(fixture.sql("SELECT count(), sum(total) FROM pedido GROUP ALL"));
    }

    /** Cada registro está no dono do slot dele, e só lá. */
    private static void assertPlacement(GatewayFixture fixture) {
        ShardMap map = fixture.store.require();
        int orders = 0;
        int events = 0;
        for (int shard = 0; shard < fixture.shards.size(); shard++) {
            for (AxonValue row : fixture.onShard(shard, "SELECT * FROM pedido").asArray()) {
                assertEquals("s" + shard, map.ownerOf(SlotHash.ofValue(row.asObject().get("cliente"))));
                orders++;
            }
            for (AxonValue row : fixture.onShard(shard, "SELECT * FROM evento").asArray()) {
                assertEquals("s" + shard, map.ownerOf(SlotHash.ofId("evento", row.asObject().get("id"))));
                events++;
            }
        }
        assertEquals(200, orders);
        assertEquals(60, events);
    }

    @Test
    void moverSlotsLevaOsRegistrosSemMudarNenhumaResposta() {
        try (GatewayFixture fixture = loaded()) {
            String before = snapshot(fixture);

            ShardMap done = mover(fixture).move(List.of(new SlotRange(0, 699)), "s2");

            assertTrue(done.migration().isEmpty());
            assertEquals("s2", done.ownerOf(0));
            assertEquals("s0", done.ownerOf(700));
            assertEquals(before, snapshot(fixture));
            assertPlacement(fixture);
            // o índice do destino enxerga o que chegou
            for (int i = 0; i < 200; i++) {
                assertEquals(1, fixture.sql("SELECT * FROM pedido WHERE cliente = 'c" + i + "'").asArray().size());
            }
        }
    }

    @Test
    void shardNovoRecebeAsDefinicoesEDepoisOsSlots() {
        try (GatewayFixture fixture = loaded()) {
            String before = snapshot(fixture);
            fixture.add("s3");
            ShardMover mover = mover(fixture);

            mover.addShard(new ShardInfo("s3", List.of()));
            mover.move(List.of(new SlotRange(0, 511)), "s3");

            var table = fixture.shards.get(3).findDatabase("n", "d").catalog().table("pedido");
            assertEquals("cliente", table.shardBy());
            assertTrue(table.indexes().containsKey("por_cliente"));
            assertTrue(fixture.count(3, "pedido") > 0);
            assertEquals(before, snapshot(fixture));
            assertPlacement(fixture);
        }
    }

    @Test
    void duranteAMovimentacaoAsEscritasDosSlotsEsperamEAsLeiturasNaoDobram() {
        try (GatewayFixture fixture = loaded()) {
            String before = snapshot(fixture);
            // a cópia para no meio: o destino fica com registros que ainda são da origem
            Function<ShardInfo, ShardClient> stops = info -> request -> {
                ShardReply reply = fixture.clients.get(info.id()).execute(request);
                if (info.id().equals("s2") && request.sql().startsWith("INSERT")) {
                    throw new IllegalStateException("queda no meio da cópia");
                }
                return reply;
            };
            assertThrows(RuntimeException.class,
                () -> mover(fixture, stops).move(List.of(new SlotRange(0, 699)), "s2"));
            assertEquals(ShardMap.MigrationPhase.COPY,
                fixture.store.require().migration().orElseThrow().phase());

            assertEquals(before, snapshot(fixture));
            String moving = keyIn(fixture, 0, 699);
            String still = keyIn(fixture, 3000, 4095);
            ShardException frozen = assertThrows(ShardException.class,
                () -> fixture.sql("CREATE pedido SET cliente = '" + moving + "', n = 1000"));
            assertEquals(ShardException.Code.MOVING, frozen.code());
            assertThrows(ShardException.class, () -> fixture.sql("DEFINE INDEX outro ON TABLE pedido COLUMNS n"));
            fixture.sql("CREATE pedido SET cliente = '" + still + "', n = 1001");
            fixture.sql("DELETE pedido WHERE cliente = '" + still + "' AND n = 1001");

            ShardMap done = mover(fixture).resume();

            assertTrue(done.migration().isEmpty());
            assertEquals(before, snapshot(fixture));
            assertPlacement(fixture);
        }
    }

    @Test
    void desistirApagaACopiaEMantemODono() {
        try (GatewayFixture fixture = loaded()) {
            String before = snapshot(fixture);
            Function<ShardInfo, ShardClient> stops = info -> request -> {
                ShardReply reply = fixture.clients.get(info.id()).execute(request);
                if (info.id().equals("s2") && request.sql().startsWith("INSERT")) {
                    throw new IllegalStateException("queda no meio da cópia");
                }
                return reply;
            };
            assertThrows(RuntimeException.class,
                () -> mover(fixture, stops).move(List.of(new SlotRange(0, 699)), "s2"));

            ShardMap aborted = mover(fixture).abort();

            assertEquals("s0", aborted.ownerOf(0));
            assertTrue(aborted.migration().isEmpty());
            assertEquals(before, snapshot(fixture));
            assertPlacement(fixture);
        }
    }

    @Test
    void pedirOutraMovimentacaoComUmaAbertaERecusado() {
        try (GatewayFixture fixture = loaded()) {
            fixture.store.update(map -> map.startMigration(List.of(new SlotRange(0, 9)), "s1"));

            assertThrows(ShardException.class,
                () -> mover(fixture).move(List.of(new SlotRange(10, 19)), "s1"));
            assertThrows(ShardException.class, () -> mover(fixture).addShard(new ShardInfo("s9", List.of())));
        }
    }

    @Test
    void leiturasEEscritasDuranteAMovimentacaoNaoPerdemNemDobramNada() throws Exception {
        ShardGateway.Options options = ShardGateway.Options.defaults().withMapTtl(Duration.ofMillis(20));
        try (GatewayFixture fixture = loaded(options)) {
            ShardMover slow = new ShardMover(fixture.store, info -> fixture.clients.get(info.id()),
                Duration.ofMillis(400), HybridClock.system());
            AtomicBoolean moving = new AtomicBoolean(true);
            AtomicInteger created = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(3);
            List<Future<?>> clients = new ArrayList<>();
            clients.add(pool.submit(() -> {
                GatewaySession session = new GatewaySession("n", "d");
                while (moving.get()) {
                    AxonValue rows = fixture.gateway.execute(session, "SELECT count() FROM evento GROUP ALL", null);
                    assertEquals(AxonValue.num(60), rows.asArray().get(0).asObject().get("count"));
                }
            }));
            clients.add(pool.submit(() -> {
                GatewaySession session = new GatewaySession("n", "d");
                for (int i = 0; moving.get(); i++) {
                    try {
                        fixture.gateway.execute(session,
                            "CREATE pedido SET cliente = 'w" + i + "', n = " + (10_000 + i), null);
                        created.incrementAndGet();
                    } catch (ShardException refused) {
                        assertEquals(ShardException.Code.MOVING, refused.code());
                    }
                }
            }));
            try {
                slow.move(List.of(new SlotRange(0, 699)), "s2");
            } finally {
                moving.set(false);
            }
            for (Future<?> client : clients) {
                client.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            Thread.sleep(40);

            AxonValue total = fixture.sql("SELECT count() FROM pedido GROUP ALL");
            assertEquals(AxonValue.num(200 + created.get()), total.asArray().get(0).asObject().get("count"));
        }
    }

    private static String keyIn(GatewayFixture fixture, int from, int to) {
        for (int i = 0; ; i++) {
            int slot = SlotHash.ofValue(AxonValue.str("k" + i));
            if (slot >= from && slot <= to) {
                return "k" + i;
            }
        }
    }
}
