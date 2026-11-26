package com.axonbase.core.partition;

import com.axonbase.core.Session;
import com.axonbase.core.storage.VersionConflictException;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static com.axonbase.core.partition.TimeData.DAY1;
import static com.axonbase.core.partition.TimeData.DAY2;
import static com.axonbase.core.partition.TimeData.DAY3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E4, fatia 1: partição por tempo (escrita, leitura com poda, índices, transações e remoção). */
@DisplayName("Partição por tempo")
class PartitionTest {

    @TempDir
    Path tmp;

    private TimeData open(String storage) {
        return storage.equals("rocks") ? TimeData.rocks(tmp.resolve("db")) : TimeData.memory();
    }

    private static String fails(TimeData db, String sql) {
        return assertThrows(RuntimeException.class, () -> db.run(sql)).getMessage();
    }

    @Nested
    @DisplayName("escrita e leitura")
    class ReadWrite {

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("cada dia vira uma partição e as linhas ficam sob o prefixo dela")
        void insert_createsOnePartitionPerDay(String storage) {
            try (TimeData db = open(storage).threeDays()) {
                List<AxonValue> partitions = db.rows("SHOW PARTITIONS ON TABLE events");

                assertEquals(3, partitions.size());
                assertEquals("HOT", partitions.get(0).asObject().get("state").asString());
                assertTrue(db.physicalKeys("events").stream().allMatch(k -> k.contains("\u0000!p\u0000")));
                assertEquals(List.of("ana", "bia", "caio"), db.values("SELECT * FROM events", "who"));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("leitura por id e por faixa de tempo")
        void select_byIdAndRange(String storage) {
            try (TimeData db = open(storage).threeDays()) {
                assertEquals(List.of("bia"), db.values("SELECT * FROM events:b", "who"));
                assertEquals(List.of("bia", "caio"), db.values(
                    "SELECT * FROM events WHERE at >= d\"2026-11-02T00:00:00Z\"", "who"));
                assertEquals(List.of("ana"), db.values(
                    "SELECT * FROM events WHERE at < d\"2026-11-02T00:00:00Z\"", "who"));
                assertEquals(List.of("bia"), db.values("SELECT * FROM events WHERE at = d\"" + DAY2 + "\"", "who"));
                assertEquals(1, db.count("SELECT * FROM events WHERE who = \"ana\" LIMIT 5"));
                assertEquals(0, db.count("SELECT * FROM events:zz"));
            }
        }

        @Test
        @DisplayName("contagem, agrupamento e ORDER BY enxergam todas as partições")
        void aggregates_spanPartitions() {
            try (TimeData db = TimeData.memory().threeDays()) {
                AxonValue total = db.rows("SELECT count() AS n FROM events GROUP ALL").get(0);
                assertEquals(3L, total.asObject().get("n").asLong());
                AxonValue last = db.rows("SELECT * FROM events ORDER BY at DESC LIMIT 1").get(0);
                assertEquals("caio", last.asObject().get("who").asString());
            }
        }

        @Test
        @DisplayName("UPDATE que muda o campo da partição move a linha, sem duplicar")
        void update_movesRowBetweenPartitions() {
            try (TimeData db = TimeData.memory().threeDays()) {
                db.run("UPDATE events:a MERGE {at: d\"" + DAY3 + "\"}");

                assertEquals(3, db.count("SELECT * FROM events"));
                assertEquals(List.of("ana", "caio"), db.values(
                    "SELECT * FROM events WHERE at >= d\"2026-11-03T00:00:00Z\"", "who"));
                assertEquals(1, db.count("SELECT * FROM events:a"));
            }
        }

        @Test
        @DisplayName("CREATE de um id existente em outro dia substitui a linha")
        void create_sameIdOtherDay_replaces() {
            try (TimeData db = TimeData.memory().threeDays()) {
                db.run("CREATE events:a CONTENT {at: d\"" + DAY2 + "\", who: \"nova\"}");

                assertEquals(3, db.count("SELECT * FROM events"));
                assertEquals(List.of("nova"), db.values("SELECT * FROM events:a", "who"));
            }
        }

        @Test
        @DisplayName("ids gerados, DELETE e UPDATE por condição")
        void generatedIds_updateAndDeleteByCondition() {
            try (TimeData db = TimeData.memory().threeDays()) {
                db.run("CREATE events CONTENT {at: d\"" + DAY1 + "\", who: \"davi\"}");
                db.run("UPDATE events SET seen = true WHERE at < d\"2026-11-02T00:00:00Z\"");
                db.run("DELETE events WHERE who = \"ana\"");

                assertEquals(List.of("bia", "caio", "davi"), db.values("SELECT * FROM events", "who"));
                assertEquals(1, db.count("SELECT * FROM events WHERE seen = true"));
            }
        }

        @Test
        @DisplayName("linha sem o campo de tempo, ou com campo que não é datetime, é recusada")
        void insert_withoutTimeField_rejected() {
            try (TimeData db = TimeData.memory()) {
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");

                assertTrue(fails(db, "CREATE events:x CONTENT {who: \"ana\"}").contains("datetime"));
                assertTrue(fails(db, "CREATE events:y CONTENT {at: \"ontem\"}").contains("datetime"));
                assertEquals(0, db.count("SELECT * FROM events"));
            }
        }

        @Test
        @DisplayName("com o campo declarado datetime, o texto ISO é convertido")
        void insert_isoTextWithTypedField_accepted() {
            try (TimeData db = TimeData.memory()) {
                db.run("DEFINE FIELD at ON TABLE events TYPE datetime");
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1h");
                db.run("CREATE events:x CONTENT {at: \"" + DAY1 + "\"}");

                assertEquals(1, db.count("SELECT * FROM events"));
            }
        }
    }

    @Nested
    @DisplayName("remoção de partição")
    class Drop {

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("DROP PARTITION AT tira só aquela faixa, e a faixa pode renascer")
        void dropAt_removesOnlyThatRange(String storage) {
            try (TimeData db = open(storage).threeDays()) {
                assertEquals(1L, db.run("DROP PARTITION ON TABLE events AT d\"" + DAY2 + "\"").asLong());

                assertEquals(List.of("ana", "caio"), db.values("SELECT * FROM events", "who"));
                assertEquals(0, db.count("SELECT * FROM events:b"));
                assertEquals(2, db.count("SHOW PARTITIONS ON TABLE events"));
                db.run("CREATE events:b2 CONTENT {at: d\"" + DAY2 + "\", who: \"volta\"}");
                assertEquals(List.of("ana", "caio", "volta"), db.values("SELECT * FROM events", "who"));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("DROP PARTITION BEFORE tira as faixas que terminaram até o instante")
        void dropBefore_removesEndedRanges(String storage) {
            try (TimeData db = open(storage).threeDays()) {
                assertEquals(2L, db.run("DROP PARTITION ON TABLE events BEFORE d\"2026-11-03T00:00:00Z\"").asLong());

                assertEquals(List.of("caio"), db.values("SELECT * FROM events", "who"));
                assertEquals(0L, db.run("DROP PARTITION ON TABLE events BEFORE d\"2026-11-03T00:00:00Z\"").asLong());
            }
        }

        @Test
        @DisplayName("DROP PARTITION de um instante sem partição ou em tabela comum falha")
        void drop_missing_fails() {
            try (TimeData db = TimeData.memory().threeDays()) {
                assertTrue(fails(db, "DROP PARTITION ON TABLE events AT d\"2030-01-01T00:00:00Z\"").length() > 0);
                db.run("CREATE plain:1 CONTENT {at: d\"" + DAY1 + "\"}");
                assertTrue(fails(db, "DROP PARTITION ON TABLE plain AT d\"" + DAY1 + "\"").contains("plain"));
            }
        }

        @Test
        @DisplayName("DROP TABLE apaga partições, descritores e deixa a tabela renascer comum")
        void dropTable_removesEverything() {
            try (TimeData db = TimeData.rocks(tmp.resolve("db")).threeDays()) {
                db.run("DROP TABLE events");

                assertTrue(db.physicalKeys("events").isEmpty());
                assertTrue(db.backend.keysWithPrefix(PartitionKeys.DESCRIPTORS).isEmpty());
                db.run("CREATE events:z CONTENT {who: \"sem data\"}");
                assertEquals(1, db.count("SELECT * FROM events"));
            }
        }

        @Test
        @DisplayName("evento que tenta remover partição no meio de uma escrita é recusado, sem travar")
        void drop_insideEvent_rejected() {
            try (TimeData db = TimeData.memory().threeDays()) {
                db.run("DEFINE EVENT limpa ON TABLE gatilho WHEN $event = \"CREATE\" "
                    + "THEN (DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\")");

                assertThrows(RuntimeException.class, () -> db.run("CREATE gatilho:1 CONTENT {}"));
                assertEquals(3, db.count("SELECT * FROM events"));
            }
        }
    }

    @Nested
    @DisplayName("índices locais")
    class Indexes {

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("UNIQUE vale entre partições e some junto com a partição")
        void unique_isGlobalAndDropsWithPartition(String storage) {
            try (TimeData db = open(storage)) {
                db.run("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
                db.threeDays();

                assertTrue(fails(db, "CREATE events:d CONTENT {at: d\"" + DAY3 + "\", who: \"ana\"}")
                    .contains("quem"));
                assertEquals(3, db.count("SELECT * FROM events"));
                assertEquals(1, db.count("SELECT * FROM events WHERE who = \"ana\""));
                db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");
                db.run("CREATE events:d CONTENT {at: d\"" + DAY3 + "\", who: \"ana\"}");
                assertEquals(1, db.count("SELECT * FROM events WHERE who = \"ana\""));
            }
        }

        @Test
        @DisplayName("UPDATE troca a entrada UNIQUE, inclusive ao mudar de partição")
        void unique_followsMovedRow() {
            try (TimeData db = TimeData.memory()) {
                db.run("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
                db.threeDays();
                db.run("UPDATE events:a MERGE {at: d\"" + DAY3 + "\", who: \"ada\"}");
                db.run("CREATE events:e CONTENT {at: d\"" + DAY1 + "\", who: \"ana\"}");

                assertTrue(fails(db, "CREATE events:f CONTENT {at: d\"" + DAY2 + "\", who: \"ada\"}")
                    .contains("quem"));
                db.run("DELETE events:a");
                db.run("CREATE events:f CONTENT {at: d\"" + DAY2 + "\", who: \"ada\"}");
                assertEquals(4, db.count("SELECT * FROM events"));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("COLUMNAR responde consulta entre partições e esquece a partição removida")
        void columnar_spansPartitions(String storage) {
            try (TimeData db = open(storage).threeDays()) {
                db.run("CREATE INDEX quem_col ON TABLE events COLUMNS who COLUMNAR");
                db.run("CREATE events:d CONTENT {at: d\"" + DAY3 + "\", who: \"ana\"}");

                assertEquals(2, db.count("SELECT * FROM events WHERE who = \"ana\""));
                db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");
                assertEquals(1, db.count("SELECT * FROM events WHERE who = \"ana\""));
                AxonValue total = db.rows("SELECT count() AS n FROM events GROUP ALL").get(0);
                assertEquals(3L, total.asObject().get("n").asLong());
            }
        }

        @Test
        @DisplayName("DROP INDEX limpa as entradas de todas as partições")
        void dropIndex_purgesAllPartitions() {
            try (TimeData db = TimeData.memory()) {
                db.run("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
                db.threeDays();
                db.run("DROP INDEX quem ON TABLE events");

                assertTrue(db.physicalKeys("events").stream().noneMatch(k -> k.contains("!uidx")));
            }
        }

        @Test
        @DisplayName("índices globais são recusados, na ordem que for")
        void globalIndexes_rejected() {
            try (TimeData db = TimeData.memory().threeDays()) {
                assertTrue(fails(db, "CREATE INDEX onde ON TABLE events COLUMNS pos GEO").contains("onde"));
                db.run("DEFINE ANALYZER pt LOWERCASE");
                db.run("CREATE INDEX busca ON TABLE other COLUMNS txt SEARCH ANALYZER pt");
                assertTrue(fails(db, "DEFINE PARTITION ON TABLE other FIELD at INTERVAL 1d").contains("busca"));
            }
        }
    }

    @Nested
    @DisplayName("definição")
    class Definition {

        @Test
        @DisplayName("só tabela vazia pode ser particionada, e o intervalo mínimo é 1 segundo")
        void define_guards() {
            try (TimeData db = TimeData.memory()) {
                db.run("CREATE cheia:1 CONTENT {at: d\"" + DAY1 + "\"}");

                assertTrue(fails(db, "DEFINE PARTITION ON TABLE cheia FIELD at INTERVAL 1d").contains("cheia"));
                fails(db, "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 10ms");
                fails(db, "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d RETENTION 1d COLD AFTER 2d");
            }
        }

        @Test
        @DisplayName("repetir troca a retenção; mudar o intervalo com partições é recusado")
        void redefine_rules() {
            try (TimeData db = TimeData.memory().threeDays()) {
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d RETENTION 90d");

                fails(db, "DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1h");
                assertEquals(90L * 86_400_000L, db.ds.findDatabase("test", "dev").catalog()
                    .partitioning("events").retentionMillis());
            }
        }

        @Test
        @DisplayName("partições, índices e definição sobrevivem ao reinício")
        void restart_keepsPartitions() {
            try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
                db.run("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
                db.threeDays();
                db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");

                db.restart();

                assertEquals(List.of("bia", "caio"), db.values("SELECT * FROM events", "who"));
                assertEquals(2, db.count("SHOW PARTITIONS ON TABLE events"));
                db.run("CREATE events:d CONTENT {at: d\"" + DAY1 + "\", who: \"ana\"}");
                assertTrue(fails(db, "CREATE events:e CONTENT {at: d\"" + DAY3 + "\", who: \"bia\"}")
                    .contains("quem"));
            }
        }

        @Test
        @DisplayName("o dump lógico recria a partição e os dados em outro motor")
        void export_recreatesPartitionedTable() {
            try (TimeData db = TimeData.memory().threeDays(); TimeData copy = TimeData.memory()) {
                String dump = db.ds.exportDatabase("test", "dev");

                copy.run(dump);

                assertEquals(3, copy.count("SHOW PARTITIONS ON TABLE events"));
                assertEquals(List.of("ana", "bia", "caio"), copy.values("SELECT * FROM events", "who"));
            }
        }
    }

    @Nested
    @DisplayName("transações")
    class Transactions {

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("BEGIN, escrita e COMMIT numa tabela particionada, inclusive criando a partição")
        void commit_writesPartitionedRows(String storage) {
            try (TimeData db = open(storage)) {
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
                Session tx = TimeData.newSession();
                db.run(tx, "BEGIN");
                db.run(tx, "CREATE events:a CONTENT {at: d\"" + DAY1 + "\", who: \"ana\"}");
                db.run(tx, "CREATE events:b CONTENT {at: d\"" + DAY1 + "\", who: \"bia\"}");
                assertEquals(0, db.count("SELECT * FROM events"));
                db.run(tx, "COMMIT");

                assertEquals(2, db.count("SELECT * FROM events"));
                assertEquals(1, db.count("SHOW PARTITIONS ON TABLE events"));
            }
        }

        @Test
        @DisplayName("duas transações que escrevem na mesma partição não conflitam")
        void commit_samePartition_noConflict() {
            try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
                Session one = TimeData.newSession();
                Session two = TimeData.newSession();
                db.run(one, "BEGIN");
                db.run(two, "BEGIN");
                db.run(one, "CREATE events:a CONTENT {at: d\"" + DAY1 + "\"}");
                db.run(two, "CREATE events:b CONTENT {at: d\"" + DAY1 + "\"}");

                db.run(one, "COMMIT");
                db.run(two, "COMMIT");

                assertEquals(2, db.count("SELECT * FROM events"));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"memory", "rocks"})
        @DisplayName("COMMIT depois de a partição ser removida falha e não grava nada")
        void commit_afterDrop_conflicts(String storage) {
            try (TimeData db = open(storage).threeDays()) {
                Session tx = TimeData.newSession();
                db.run(tx, "BEGIN");
                db.run(tx, "CREATE events:x CONTENT {at: d\"" + DAY1 + "\", who: \"x\"}");
                db.run("DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");

                assertThrows(VersionConflictException.class, () -> db.run(tx, "COMMIT"));

                assertEquals(List.of("bia", "caio"), db.values("SELECT * FROM events", "who"));
            }
        }

        @Test
        @DisplayName("DROP PARTITION numa transação vale no COMMIT; antes disso os outros ainda veem")
        void drop_insideTransaction_appliesOnCommit() {
            try (TimeData db = TimeData.rocks(tmp.resolve("db")).threeDays()) {
                Session tx = TimeData.newSession();
                db.run(tx, "BEGIN");
                db.run(tx, "DROP PARTITION ON TABLE events AT d\"" + DAY1 + "\"");

                assertEquals(2, db.run(tx, "SELECT * FROM events").asArray().size());
                assertEquals(3, db.count("SELECT * FROM events"));
                db.run(tx, "COMMIT");
                assertEquals(2, db.count("SELECT * FROM events"));
            }
        }

        @Test
        @DisplayName("CANCEL descarta a linha e a partição criada na transação")
        void cancel_discardsPartition() {
            try (TimeData db = TimeData.memory()) {
                db.run("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d");
                Session tx = TimeData.newSession();
                db.run(tx, "BEGIN");
                db.run(tx, "CREATE events:a CONTENT {at: d\"" + DAY1 + "\"}");
                db.run(tx, "CANCEL");

                assertEquals(0, db.count("SHOW PARTITIONS ON TABLE events"));
            }
        }
    }
}
