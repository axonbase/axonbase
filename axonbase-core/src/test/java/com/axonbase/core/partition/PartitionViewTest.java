package com.axonbase.core.partition;

import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A poda de partições e o formato das chaves, sem passar pelo executor. */
@DisplayName("Partição: chaves e poda")
class PartitionViewTest {

    private static final String T = "d\"2026-11-02T00:00:00Z\"";
    private static final long MILLIS = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli();

    private static TimeRange range(String where) {
        Statement.Select select = (Statement.Select) AxonQl.parse("SELECT * FROM e WHERE " + where)
            .statements().get(0);
        return TimeRange.of(select.cond(), "at", e -> ((Expr.Literal) e).value());
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "at >= d\"2026-11-02T00:00:00Z\"; true; false",
        "at > d\"2026-11-02T00:00:00Z\"; true; false",
        "at < d\"2026-11-02T00:00:00Z\"; false; true",
        "at <= d\"2026-11-02T00:00:00Z\"; false; true",
        "d\"2026-11-02T00:00:00Z\" <= at; true; false",
        "at = d\"2026-11-02T00:00:00Z\"; true; true"
    })
    @DisplayName("comparações com um instante fecham um ou os dois lados da faixa")
    void comparisons_boundTheRange(String where, boolean lower, boolean upper) {
        TimeRange range = range(where);

        assertEquals(lower ? MILLIS : Long.MIN_VALUE, range.min());
        assertEquals(upper ? MILLIS : Long.MAX_VALUE, range.max());
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "who = \"ana\"",
        "at != d\"2026-11-02T00:00:00Z\"",
        "at >= \"2026-11-02T00:00:00Z\"",
        "at >= other",
        "at >= d\"2026-11-02T00:00:00Z\" OR who = \"ana\""
    })
    @DisplayName("na dúvida a faixa fica aberta")
    void unknownShapes_keepRangeOpen(String where) {
        assertEquals(TimeRange.ALL, range(where));
    }

    @Test
    @DisplayName("condições ligadas por AND se somam")
    void conjunction_intersects() {
        TimeRange range = range("at >= " + T + " AND who = \"ana\" AND at < d\"2026-11-03T00:00:00Z\"");

        assertEquals(MILLIS, range.min());
        assertEquals(MILLIS + 86_400_000L, range.max());
    }

    @Test
    @DisplayName("a poda do contexto tira das varreduras as partições fora da faixa e depois é desfeita")
    void contextPruning_skipsPartitions() {
        try (TimeData db = TimeData.memory().threeDays()) {
            var database = db.ds.findDatabase("test", "dev");
            PartitionContext context = new PartitionContext();
            var view = PartitionedKv.wrap(database, database.records(), context, ColdRecords.UNCONFIGURED);
            String prefix = PartitionKeys.tablePrefix("test", "dev", "events") + "s";
            List<String> pruned = new ArrayList<>();

            try (var scope = context.prune(PartitionedKv.rangeKey(database, "events"),
                    new TimeRange(MILLIS, MILLIS))) {
                pruned.addAll(view.keysWithPrefix(prefix));
            }

            assertEquals(List.of(prefix.substring(0, prefix.length() - 1) + "sb"), pruned);
            assertEquals(3, view.keysWithPrefix(prefix).size());
        }
    }

    @Test
    @DisplayName("registro é reconhecido pela chave física, particionada ou não; índice não é registro")
    void storedRecord_parsing() {
        String partition = PartitionKeys.partitionPrefix("n", "d", "t", 86_400_000L);
        String body = partition.substring(PartitionKeys.dbPrefix("n", "d").length());

        assertEquals(new PartitionKeys.StoredRecord("t", "sana"), PartitionKeys.record(body + "sana"));
        assertEquals(new PartitionKeys.StoredRecord("t", "n1"), PartitionKeys.record("t\u0000n1"));
        assertNull(PartitionKeys.record(body + "!uidx\u0000who\u0000ana\u0000sana"));
        assertNull(PartitionKeys.record("t\u0000!ttl\u00000000000000000000001\u0000sana"));
        assertTrue(PartitionKeys.pad(5).compareTo(PartitionKeys.pad(40)) < 0);
    }

    @Test
    @DisplayName("o descritor vai e volta pela codificação")
    void descriptor_roundTrips() {
        PartitionDescriptor hot = PartitionDescriptor.hot(0, 10);
        PartitionDescriptor cold = hot.cold("parts/x.axc", 7, 99);

        assertEquals(hot, PartitionDescriptor.decode(hot.encode()));
        assertEquals(cold, PartitionDescriptor.decode(cold.encode()));
        assertEquals(AxonValue.num(7).asLong(), PartitionDescriptor.decode(cold.encode()).rows());
    }
}
