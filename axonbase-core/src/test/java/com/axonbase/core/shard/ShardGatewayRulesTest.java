package com.axonbase.core.shard;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: regras, falhas parciais e sessão do gateway")
class ShardGatewayRulesTest {

    private static GatewayFixture orders() {
        GatewayFixture fixture = GatewayFixture.memory(3);
        fixture.sql("DEFINE TABLE pedido SCHEMALESS SHARD BY cliente");
        return fixture;
    }

    private static ShardException fails(GatewayFixture fixture, ShardException.Code code, String sql) {
        ShardException failure = assertThrows(ShardException.class, () -> fixture.sql(sql), sql);
        assertEquals(code, failure.code(), sql + ": " + failure.getMessage());
        return failure;
    }

    private static void refused(GatewayFixture fixture, String sql) {
        fails(fixture, ShardException.Code.REFUSED, sql);
    }

    /** Um gateway sobre o mesmo conjunto em que um shard falha nas requisições que o filtro escolher. */
    private static ShardGateway broken(GatewayFixture fixture, String shard,
                                       java.util.function.Predicate<ShardRequest> failing) {
        return new ShardGateway(fixture.store, info -> info.id().equals(shard)
            ? request -> {
                if (failing.test(request)) {
                    throw new ShardException(ShardException.Code.UNAVAILABLE, "fora do ar");
                }
                return fixture.clients.get(shard).execute(request);
            }
            : fixture.clients.get(info.id()),
            ShardGateway.Options.defaults().withMapTtl(Duration.ZERO), HybridClock.system());
    }

    @Test
    void definicoesAnterioresQueValeriamSoDentroDeUmShardImpedemOShardBy() {
        try (GatewayFixture fixture = GatewayFixture.memory(2)) {
            fixture.sql("DEFINE TABLE a SCHEMALESS");
            fixture.sql("DEFINE INDEX unico ON TABLE a COLUMNS numero UNIQUE");
            fixture.sql("DEFINE TABLE b SCHEMALESS");
            fixture.sql("DEFINE EVENT e ON TABLE b WHEN true THEN (CREATE log SET m = 1)");

            refused(fixture, "DEFINE TABLE a SHARD BY cliente");
            refused(fixture, "DEFINE TABLE b SHARD BY cliente");
            assertTrue(fixture.store.require().tables().isEmpty());
            fixture.sql("DEFINE TABLE a SHARD BY numero");
        }
    }

    @Test
    void referenciasERemocaoDeTabelaDividida() {
        try (GatewayFixture fixture = orders()) {
            refused(fixture, "DEFINE FIELD dono ON TABLE pedido TYPE string REFERENCES cliente");

            fixture.sql("REMOVE TABLE pedido");

            assertTrue(fixture.store.require().tables().isEmpty());
        }
    }

    @Test
    void definicaoQueFalhaNoMeioDizQuemAplicou() {
        try (GatewayFixture fixture = orders();
             ShardGateway gateway = broken(fixture, "s1", request -> request.sql().startsWith("DEFINE"))) {
            ShardException partial = assertThrows(ShardException.class, () -> gateway.execute(fixture.session(),
                "DEFINE INDEX i ON TABLE pedido COLUMNS n", null));

            assertEquals(ShardException.Code.PARTIAL_WRITE, partial.code());
            assertTrue(partial.getMessage().contains("s0"), partial.getMessage());
        }
    }

    @Test
    void escritaEmVariosShardsQueFalhaNoMeioDizQuemGravou() {
        try (GatewayFixture fixture = orders();
             ShardGateway gateway = broken(fixture, "s2", request -> request.sql().startsWith("INSERT"))) {
            String rows = "INSERT INTO pedido [{cliente: '" + fixture.keyOn(0) + "'}, {cliente: '" + fixture.keyOn(2)
                + "'}]";

            ShardException partial = assertThrows(ShardException.class,
                () -> gateway.execute(fixture.session(), rows, null));

            assertEquals(ShardException.Code.PARTIAL_WRITE, partial.code());
            assertEquals(1, fixture.count(0, "pedido"));
        }
    }

    @Test
    void insercaoSilenciosaERetornosNaoSuportados() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("INSERT INTO pedido [{cliente: 'a'}, {cliente: 'b'}] RETURN NONE");
            assertEquals(AxonValue.array(List.of()), fixture.sql("INSERT INTO pedido []"));

            refused(fixture, "INSERT INTO pedido [{cliente: 'a'}] RETURN BEFORE");
            fixture.sql("BEGIN SAGA s WITH CORRELATION 'c'");
            refused(fixture, "INSERT INTO pedido [{cliente: 'a'}]");
            refused(fixture, "BEGIN; CREATE pedido SET cliente = 'a'; COMMIT");
        }
    }

    @Test
    void sagaPodeSerConsultadaRetomadaEAbandonada() {
        try (GatewayFixture fixture = orders()) {
            String ana = fixture.keyOn(0);
            String bia = fixture.keyOn(1);
            fixture.sql("BEGIN SAGA s WITH CORRELATION 'c'; CREATE pedido SET cliente = '" + ana
                + "'; CREATE pedido SET cliente = '" + bia + "'");

            AxonValue shown = fixture.sql("SHOW SAGA TRANSACTION s 'c'");
            assertEquals(2, shown.asObject().get("steps").asArray().size());
            assertTrue(shown.asObject().get("saga").isObject());

            fixture.sql("JOIN SAGA s WITH CORRELATION 'c'");
            try (ShardGateway gateway = broken(fixture, "s1", request -> request.sql().startsWith("COMMIT"))) {
                ShardException partial = assertThrows(ShardException.class, () -> gateway.execute(
                    new GatewaySession("n", "d"), "COMMIT SAGA s WITH CORRELATION 'c'", null));
                assertEquals(ShardException.Code.PARTIAL_WRITE, partial.code());
            }
            // repetir conclui o que faltou
            fixture.sql("COMMIT SAGA s WITH CORRELATION 'c'");
            refused(fixture, "COMMIT SAGA s WITH CORRELATION 'c'");
            refused(fixture, "JOIN SAGA s WITH CORRELATION 'outra'");
        }
    }

    @Test
    void sessaoGuardaBancoEVariaveis() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("USE n d; LET $c = 'ana'; CREATE pedido SET cliente = $c");
            assertEquals(1, fixture.sql("SELECT * FROM pedido WHERE cliente = $c").asArray().size());
            fixture.sql("CREATE cidade:1 SET nome = 'recife'");

            fixture.sql("LET $nomes = (SELECT VALUE nome FROM cidade)");
            assertEquals(List.of(AxonValue.str("recife")), fixture.session().vars().get("nomes").asArray());
            refused(fixture, "LET $x = (SELECT * FROM pedido)");

            fixture.sql("USE n outro");
            assertEquals("outro", fixture.session().database());
        }
    }

    @Test
    void blocosCanceladosVaziosEInvalidos() {
        try (GatewayFixture fixture = orders()) {
            assertTrue(fixture.sql("BEGIN; COMMIT").isNull());
            fixture.sql("BEGIN; SAVEPOINT a; CREATE cidade:1; ROLLBACK TO a; COMMIT");
            assertEquals(0, fixture.count(0, "cidade"));

            refused(fixture, "COMMIT");
            refused(fixture, "CANCEL");
            refused(fixture, "BEGIN; DEFINE TABLE x; COMMIT");
            refused(fixture, "BEGIN; SELECT * FROM pedido; COMMIT");
        }
    }

    @Test
    void alvosEComandosQueOGatewayNaoSabeRotear() {
        try (GatewayFixture fixture = orders()) {
            Map<String, AxonValue> record = Map.of("r", AxonValue.record("pedido", AxonValue.num(1)));
            for (String sql : List.of("UPDATE $r SET n = 1", "DELETE $r", "SELECT * FROM $r")) {
                assertThrows(ShardException.class, () -> fixture.sql(sql, record), sql);
            }
            String ana = fixture.keyOn(0);
            fixture.sql("CREATE pedido SET cliente = '" + ana + "', n = 1");
            refused(fixture, "UPDATE pedido PATCH [{ op: 'replace', path: '/cliente', value: 'x' }] WHERE cliente = '"
                + ana + "'");
            refused(fixture, "UPDATE pedido REPLACE { n: 2 } WHERE cliente = '" + ana + "'");
            refused(fixture, "SELECT * FROM pedido JOIN cidade ON pedido.c = cidade.id");
            refused(fixture, "SELECT * FROM cidade JOIN pedido ON cidade.id = pedido.c");
            refused(fixture, "SELECT * FROM pedido WHERE nome @@ 'x'");
            refused(fixture, "SELECT fn::calcula(n) FROM cidade");
            refused(fixture, "KILL 'abc'");
        }
    }

    @Test
    void tabelasSemChaveAceitamArestasEConsultasNoShardBase() {
        try (GatewayFixture fixture = orders()) {
            fixture.sql("CREATE pessoa:1; CREATE pessoa:2");
            fixture.sql("RELATE pessoa:1->conhece->pessoa:2");

            assertTrue(fixture.sql("INFO FOR DATABASE").isObject());
            assertEquals(2, fixture.sql("SELECT * FROM pessoa").asArray().size());
        }
    }

    @Test
    void mapaVencidoSemConseguirRenovarRecusa() {
        AtomicLong now = new AtomicLong();
        AtomicBoolean down = new AtomicBoolean();
        ShardMap map = ShardMap.initial(List.of(new ShardInfo("s0", List.of())));
        ShardMapCache cache = new ShardMapCache(() -> {
            if (down.get()) {
                throw new IllegalStateException("sem metadados");
            }
            return map;
        }, Duration.ofNanos(100), now::get);
        cache.get();
        down.set(true);

        now.set(99);
        assertEquals(map, cache.get());
        now.set(100);
        ShardException stale = assertThrows(ShardException.class, cache::get);
        assertEquals(ShardException.Code.STALE_MAP, stale.code());
        // e continua recusando: a cópia vencida foi descartada
        assertThrows(ShardException.class, cache::get);
        down.set(false);
        assertEquals(map, cache.get());
    }

    @Test
    void gatewayAdiantaORelogioComOCarimboQueOClienteTraz() {
        try (GatewayFixture fixture = orders()) {
            HlcTimestamp seen = new HlcTimestamp(System.currentTimeMillis() + 200, 5);

            fixture.gateway.observe(seen);

            assertTrue(fixture.gateway.clock().compareTo(seen) >= 0);
            assertEquals(fixture.store.require(), fixture.gateway.refresh());
            assertEquals(fixture.store.require(), fixture.gateway.map());
        }
    }
}
