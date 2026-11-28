package com.axonbase.core.shard;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Diferencial: a mesma carga num nó único e em três shards atrás do gateway precisa dar a mesma resposta, na mesma
 * ordem. Os ids são escolhidos ({@code SHARD BY id}) para os dois lados terem os mesmos registros.
 */
@DisplayName("Shards: consultas espalhadas iguais às de um nó único")
class ShardDifferentialTest {

    private static final int ROWS = 600;
    private static final String[] CITIES = {"recife", "olinda", "natal", "fortaleza", "salvador", "maceio", "aracaju"};
    private static final String[] STATUS = {"novo", "pago", "enviado", "cancelado"};

    private static Datastore single;
    private static GatewayFixture sharded;

    @BeforeAll
    static void load() {
        single = new Datastore(new MemoryBackend());
        sharded = GatewayFixture.memory(3);
        both("DEFINE TABLE pedido SCHEMALESS SHARD BY id");
        Random random = new Random(7);
        for (int i = 0; i < ROWS; i++) {
            StringBuilder sql = new StringBuilder("CREATE pedido:").append(i).append(" SET cidade = '")
                .append(CITIES[random.nextInt(CITIES.length)]).append("', status = '")
                .append(STATUS[random.nextInt(STATUS.length)]).append("', total = ")
                .append(random.nextInt(500)).append(", itens = ").append(1 + random.nextInt(5));
            // alguns registros sem o campo, com nulo, decimal e texto onde se espera número
            if (i % 11 == 0) {
                sql.append(", desconto = NULL");
            } else if (i % 7 == 0) {
                sql.append(", desconto = 'nenhum'");
            } else if (i % 3 == 0) {
                sql.append(", desconto = ").append(random.nextInt(50)).append(".5");
            } else if (i % 2 == 0) {
                sql.append(", desconto = ").append(random.nextInt(50));
            }
            both(sql.toString());
        }
    }

    @AfterAll
    static void close() {
        sharded.close();
    }

    private static Session session() {
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        return session;
    }

    private static void both(String sql) {
        single.execute(sql, session(), null);
        sharded.sql(sql);
    }

    private static void same(String sql) {
        AxonValue expected = single.execute(sql, session(), null);
        AxonValue actual = sharded.sql(sql);
        assertEquals(AxonJson.write(expected), AxonJson.write(actual), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // linhas, ordem e janela
        "SELECT * FROM pedido",
        "SELECT * FROM pedido LIMIT 10",
        "SELECT * FROM pedido LIMIT 10 START 25",
        "SELECT * FROM pedido WHERE total > 400",
        "SELECT * FROM pedido WHERE total > 100000",
        "SELECT cidade, total FROM pedido WHERE status = 'pago' ORDER BY total LIMIT 20",
        "SELECT cidade, total FROM pedido ORDER BY total DESC LIMIT 15 START 5",
        "SELECT id, cidade FROM pedido ORDER BY cidade, total DESC LIMIT 40",
        "SELECT total FROM pedido ORDER BY cidade DESC, itens, total LIMIT 33 START 100",
        "SELECT total * itens AS valor FROM pedido ORDER BY valor DESC LIMIT 12",
        "SELECT desconto FROM pedido ORDER BY desconto LIMIT 50",
        "SELECT desconto FROM pedido ORDER BY desconto DESC LIMIT 50",
        "SELECT VALUE total FROM pedido ORDER BY total LIMIT 25",
        "SELECT VALUE id FROM pedido WHERE cidade = 'natal'",
        "SELECT *, total + 1 AS mais FROM pedido ORDER BY total LIMIT 5",
        "SELECT * FROM pedido ORDER BY total LIMIT 0",
        "SELECT * FROM pedido LIMIT 10 START 5000",
        "SELECT * FROM pedido:17",
        "SELECT * FROM pedido:999999",
        "SELECT * ONLY FROM pedido WHERE total >= 0 LIMIT 1",
        // agregações
        "SELECT count() FROM pedido GROUP ALL",
        "SELECT count() FROM pedido",
        "SELECT VALUE count() FROM pedido",
        "SELECT count(), sum(total), avg(total), min(total), max(total) FROM pedido GROUP ALL",
        "SELECT sum(desconto), avg(desconto), min(desconto), max(desconto) FROM pedido GROUP ALL",
        "SELECT count(DISTINCT cidade) FROM pedido GROUP ALL",
        "SELECT cidade, count(), sum(total) FROM pedido GROUP BY cidade",
        "SELECT cidade, status, count(), avg(total) FROM pedido GROUP BY cidade, status",
        "SELECT cidade, sum(total) AS soma FROM pedido GROUP BY cidade ORDER BY soma DESC",
        "SELECT cidade, sum(total) AS soma FROM pedido GROUP BY cidade ORDER BY soma DESC LIMIT 3",
        "SELECT cidade, sum(total) AS soma FROM pedido GROUP BY cidade ORDER BY cidade LIMIT 2 START 2",
        "SELECT status, count() AS n FROM pedido GROUP BY status HAVING n > 100 ORDER BY n",
        "SELECT status, count(DISTINCT cidade) AS cidades, max(total) - min(total) AS faixa FROM pedido GROUP BY status",
        "SELECT itens, sum(total) / count() AS media FROM pedido WHERE status != 'cancelado' GROUP BY itens",
        "SELECT cidade, status, count() FROM pedido GROUP BY cidade",
        "SELECT count() FROM pedido WHERE total > 100000 GROUP ALL",
        "SELECT sum(total) FROM pedido WHERE total > 100000 GROUP ALL",
        "SELECT cidade, count() FROM pedido WHERE total > 100000 GROUP BY cidade",
        "SELECT VALUE sum(total) FROM pedido GROUP BY cidade"
    })
    void mesmaRespostaDeUmNoUnico(String sql) {
        same(sql);
    }

    @Test
    void parametrosValemNosDoisLados() {
        Map<String, AxonValue> vars = Map.of("minimo", AxonValue.num(250), "quantos", AxonValue.num(7));
        String sql = "SELECT total FROM pedido WHERE total >= $minimo ORDER BY total LIMIT $quantos";

        assertEquals(AxonJson.write(single.execute(sql, session(), vars)),
            AxonJson.write(sharded.sql(sql, vars)));
    }

    @Test
    void consultasAleatoriasBatem() {
        Random random = new Random(42);
        String[] fields = {"total", "itens", "cidade", "status", "desconto"};
        for (int round = 0; round < 150; round++) {
            String where = random.nextBoolean() ? " WHERE total " + (random.nextBoolean() ? "<" : ">=") + " "
                + random.nextInt(500) : "";
            String first = fields[random.nextInt(fields.length)];
            String second = fields[random.nextInt(fields.length)];
            String sql;
            if (random.nextBoolean()) {
                sql = "SELECT " + first + ", " + second + " AS outro FROM pedido" + where + " ORDER BY " + first
                    + (random.nextBoolean() ? " DESC" : "") + " LIMIT " + random.nextInt(40) + " START "
                    + random.nextInt(60);
            } else {
                sql = "SELECT " + first + ", count(), sum(total) AS soma, avg(" + second + ") AS media FROM pedido"
                    + where + " GROUP BY " + first + (random.nextBoolean() ? " ORDER BY soma DESC" : "");
            }
            same(sql);
        }
    }

    @Test
    void depoisDeAtualizarEApagarContinuaIgual() {
        List<String> writes = new ArrayList<>();
        for (int i = 0; i < 60; i += 3) {
            writes.add("UPDATE pedido:" + i + " SET total = total + 1000");
            writes.add("DELETE pedido:" + (i + 1));
        }
        writes.forEach(ShardDifferentialTest::both);

        same("SELECT * FROM pedido ORDER BY total DESC LIMIT 30");
        same("SELECT count(), sum(total) FROM pedido GROUP ALL");
    }

    @Test
    void indiceEmCadaShardNaoMudaOResultado() {
        Datastore reference = new Datastore(new MemoryBackend());
        try (GatewayFixture fixture = GatewayFixture.memory(3)) {
            List<String> setup = new ArrayList<>();
            setup.add("DEFINE TABLE venda SHARD BY id");
            setup.add("DEFINE INDEX por_total ON TABLE venda COLUMNS total");
            for (int i = 0; i < 90; i++) {
                setup.add("CREATE venda:" + i + " SET total = " + (i * 37 % 101) + ", loja = 'l" + (i % 4) + "'");
            }
            for (String sql : setup) {
                reference.execute(sql, session(), null);
                fixture.sql(sql);
            }

            for (String sql : List.of("SELECT total, loja FROM venda ORDER BY total, loja LIMIT 20",
                "SELECT loja, count(), sum(total) FROM venda GROUP BY loja ORDER BY loja",
                "SELECT id FROM venda WHERE total = 37 ORDER BY id")) {
                assertEquals(AxonJson.write(reference.execute(sql, session(), null)),
                    AxonJson.write(fixture.sql(sql)), sql);
            }
        }
    }

    @Test
    void falhaDeUmShardDerrubaAConsultaEmVezDeResponderPelaMetade() {
        try (GatewayFixture fixture = GatewayFixture.memory(3)) {
            fixture.sql("DEFINE TABLE pedido SHARD BY id");
            fixture.sql("CREATE pedido:1 SET total = 1; CREATE pedido:2 SET total = 2");
            ShardClient down = request -> {
                throw new ShardException(ShardException.Code.UNAVAILABLE, "fora do ar");
            };
            try (ShardGateway broken = new ShardGateway(fixture.store,
                info -> info.id().equals("s2") ? down : fixture.clients.get(info.id()),
                ShardGateway.Options.defaults(), HybridClock.system())) {
                ShardException failure = assertThrows(ShardException.class,
                    () -> broken.execute(new GatewaySession("n", "d"), "SELECT * FROM pedido", null));

                assertEquals(ShardException.Code.UNAVAILABLE, failure.code());
            }
        }
    }
}
