package com.axonbase.parser;

import com.axonbase.parser.ast.PartitionCommand;
import com.axonbase.parser.ast.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sintaxe de partições por tempo e TTL (E4). */
class PartitionParserTest {

    private static final long DAY = 86_400_000L;

    private static PartitionCommand command(String sql) {
        Statement s = Parser.parse(sql).statements().get(0);
        return assertInstanceOf(Statement.Partition.class, s).command();
    }

    @Test
    @DisplayName("DEFINE PARTITION lê campo, intervalo, retenção e prazo frio")
    void definePartition_allClauses_parsed() {
        var define = assertInstanceOf(PartitionCommand.Define.class,
            command("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d RETENTION 30d COLD AFTER 7d"));

        assertEquals("events", define.table());
        assertEquals("at", define.field());
        assertEquals(DAY, define.intervalMillis());
        assertEquals(30 * DAY, define.retentionMillis());
        assertEquals(7 * DAY, define.coldAfterMillis());
    }

    @Test
    @DisplayName("DEFINE PARTITION sem cláusulas opcionais deixa retenção e prazo frio nulos")
    void definePartition_onlyRequired_optionalsAreNull() {
        var define = assertInstanceOf(PartitionCommand.Define.class,
            command("DEFINE PARTITION ON TABLE events FIELD ts INTERVAL 1h"));

        assertNull(define.retentionMillis());
        assertNull(define.coldAfterMillis());
    }

    @Test
    @DisplayName("DROP PARTITION por instante e por limite")
    void dropPartition_atAndBefore() {
        var at = assertInstanceOf(PartitionCommand.Drop.class,
            command("DROP PARTITION ON TABLE events AT d\"2026-11-01T00:00:00Z\""));
        var before = assertInstanceOf(PartitionCommand.Drop.class,
            command("DROP PARTITION ON TABLE events BEFORE d\"2026-10-01T00:00:00Z\""));

        assertNotNull(at.at());
        assertNull(at.before());
        assertNotNull(before.before());
        assertNull(before.at());
    }

    @Test
    @DisplayName("ALTER PARTITION escolhe a camada")
    void alterPartition_targetTier() {
        var cold = assertInstanceOf(PartitionCommand.Move.class,
            command("ALTER PARTITION ON TABLE events AT d\"2026-11-01T00:00:00Z\" TO COLD"));
        var hot = assertInstanceOf(PartitionCommand.Move.class,
            command("ALTER PARTITION ON TABLE events AT d\"2026-11-01T00:00:00Z\" TO HOT"));

        assertTrue(cold.cold());
        assertTrue(!hot.cold());
    }

    @Test
    @DisplayName("TTL: define com e sem AFTER, e remove")
    void ttl_defineAndDrop() {
        var plain = assertInstanceOf(PartitionCommand.DefineTtl.class,
            command("DEFINE TTL ON TABLE sessions FIELD expires_at"));
        var after = assertInstanceOf(PartitionCommand.DefineTtl.class,
            command("DEFINE TTL ON TABLE sessions FIELD created AFTER 1h"));
        var drop = assertInstanceOf(PartitionCommand.DropTtl.class, command("DROP TTL ON TABLE sessions"));

        assertEquals(0L, plain.afterMillis());
        assertEquals(3_600_000L, after.afterMillis());
        assertEquals("sessions", drop.table());
    }

    @Test
    @DisplayName("SHOW PARTITIONS é só leitura e as demais não são")
    void show_isReadOnly() {
        assertTrue(command("SHOW PARTITIONS ON TABLE events").readOnly());
        assertTrue(!command("DROP TTL ON TABLE events").readOnly());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d RETENTION 30d COLD AFTER 7d",
        "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 90m",
        "DROP PARTITION ON TABLE events AT d\"2026-11-01T00:00:00Z\"",
        "DROP PARTITION ON TABLE events BEFORE d\"2026-10-01T00:00:00Z\"",
        "ALTER PARTITION ON TABLE events AT d\"2026-11-01T00:00:00Z\" TO COLD",
        "SHOW PARTITIONS ON TABLE events",
        "DEFINE TTL ON TABLE sessions FIELD created AFTER 1h",
        "DEFINE TTL ON TABLE sessions FIELD expires_at",
        "DROP TTL ON TABLE sessions"
    })
    @DisplayName("o texto renderizado volta ao mesmo comando (replay do plano de controle)")
    void render_roundTrips(String sql) {
        Statement first = Parser.parse(sql).statements().get(0);
        Statement second = Parser.parse(Render.stmt(first)).statements().get(0);

        assertEquals(first, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DEFINE PARTITION ON TABLE events FIELD at",
        "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 5",
        "DROP PARTITION ON TABLE events",
        "ALTER PARTITION ON TABLE events AT d\"2026-11-01T00:00:00Z\" TO WARM"
    })
    @DisplayName("comandos incompletos são recusados")
    void incomplete_rejected(String sql) {
        assertThrows(RuntimeException.class, () -> Parser.parse(sql));
    }

    @Test
    @DisplayName("partition e ttl continuam valendo como nomes comuns")
    void words_remainUsableAsNames() {
        Parser.parse("DEFINE TABLE partition SCHEMALESS");
        Parser.parse("SELECT ttl, cold, hot, interval FROM retention");
    }
}
