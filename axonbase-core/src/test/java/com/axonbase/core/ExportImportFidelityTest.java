package com.axonbase.core;

import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.parser.Render;
import com.axonbase.parser.ast.Expr;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Exportar e importar preserva tipos, chaves e índices")
class ExportImportFidelityTest {

    /** Um motor em memória com acesso às chaves físicas, para comparar os registros byte a byte. */
    private record Engine(Datastore ds, MemoryBackend backend) {

        static Engine create() {
            MemoryBackend backend = new MemoryBackend();
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            return new Engine(ds, backend);
        }

        AxonValue run(String sql) {
            return run(sql, null);
        }

        AxonValue run(String sql, Map<String, AxonValue> vars) {
            Session session = Session.create();
            session.namespace("test");
            session.database("dev");
            return ds.execute(sql, session, vars);
        }

        Engine copy() throws InterruptedException {
            Engine target = create();
            target.ds.importDatabase("test", "dev", ds.exportDatabase("test", "dev"));
            assertTrue(IndexBuilder.awaitAll(60_000), "construção de índice não terminou");
            return target;
        }

        /**
         * Chave física para o texto guardado, só dos registros ({@code ns, db, tabela, chave}). Os campos saem em
         * ordem alfabética: um DEFAULT entra depois do {@code id} na criação, e antes dele quando vem no dump.
         */
        Map<String, String> rows() {
            Map<String, String> rows = new TreeMap<>();
            for (String key : backend.keysWithPrefix("test\u0000dev\u0000")) {
                String[] parts = key.split("\u0000", -1);
                if (parts.length == 4 && (parts[3].startsWith("n") || parts[3].startsWith("s"))) {
                    AxonValue record = AxonJson.decode(backend.get(key).orElseThrow());
                    rows.put(key, AxonJson.write(AxonValue.object(new TreeMap<>(record.asObject()))));
                }
            }
            return rows;
        }
    }

    private static AxonValue decimal(String text) {
        return AxonValue.num(new BigDecimal(text));
    }

    private static Map<String, AxonValue> everyType() {
        Map<String, AxonValue> inner = new LinkedHashMap<>();
        inner.put("d", AxonValue.duration(5));
        Map<String, AxonValue> object = new LinkedHashMap<>();
        object.put("chave com espaço", decimal("-3.25"));
        object.put("select", AxonValue.str("x"));
        object.put("interno", AxonValue.object(inner));

        Map<String, AxonValue> all = new LinkedHashMap<>();
        all.put("nulo", AxonValue.nul());
        all.put("sim", AxonValue.bool(true));
        all.put("nao", AxonValue.bool(false));
        all.put("inteiro", AxonValue.num(42L));
        all.put("negativo", AxonValue.num(-42L));
        all.put("menor", AxonValue.num(Long.MIN_VALUE));
        all.put("maior", AxonValue.num(Long.MAX_VALUE));
        all.put("real", AxonValue.num(1.5d));
        all.put("realInteiro", AxonValue.num(2.0d));
        all.put("realNegativo", AxonValue.num(-1.5d));
        all.put("realGrande", AxonValue.num(1.0e300d));
        all.put("realPequeno", AxonValue.num(-4.9e-300d));
        all.put("decimal", decimal("1234567890.123456789012345678"));
        all.put("decimalNegativo", decimal("-0.50"));
        all.put("decimalInteiro", decimal("7"));
        all.put("decimalNegativoInteiro", decimal("-7"));
        all.put("decimalGrande", decimal("123456789012345678901234567890"));
        all.put("texto", AxonValue.str("aspas \" ' barra \\ nova\nlinha\r\ttab\f\u0001 😀 -- ; /* */ $x"));
        all.put("textoNumero", AxonValue.str("42"));
        all.put("data", AxonValue.datetime(Instant.parse("2024-02-29T23:59:59.123456789Z")));
        all.put("dataAntiga", AxonValue.datetime(Instant.parse("1969-12-31T00:00:00Z")));
        all.put("uuid", AxonValue.uuid(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")));
        all.put("duracao", AxonValue.duration(90_061_001L));
        all.put("duracaoZero", AxonValue.duration(0));
        all.put("bytes", AxonValue.bytes(new byte[]{0, 1, -1, 127, -128}));
        all.put("bytesVazios", AxonValue.bytes(new byte[0]));
        all.put("tabela", AxonValue.table("pessoa"));
        all.put("lista", AxonValue.array(List.of(decimal("-1.5"),
            AxonValue.datetime(Instant.parse("2024-01-01T00:00:00Z")),
            AxonValue.array(List.of(AxonValue.num(-1L), AxonValue.num(-2.0d))), AxonValue.object(object))));
        all.put("listaVazia", AxonValue.array(List.of()));
        all.put("objeto", AxonValue.object(object));
        all.put("objetoVazio", AxonValue.object(Map.of()));
        return all;
    }

    @Test
    @DisplayName("o literal AxonQL de cada tipo avalia de volta ao mesmo valor e tipo")
    void renderedLiteralEvaluatesToTheSameValue() {
        Engine engine = Engine.create();
        everyType().forEach((name, value) -> {
            String literal = Render.expr(new Expr.Literal(value));
            assertEquals(AxonJson.write(value), AxonJson.write(engine.run("RETURN " + literal)), name + ": " + literal);
        });
    }

    @Test
    @DisplayName("registros com todos os tipos de valor voltam idênticos")
    void everyValueTypeSurvives() throws InterruptedException {
        Engine source = Engine.create();
        source.run("CREATE tipos:1 CONTENT $c", Map.of("c", AxonValue.object(everyType())));
        source.run("CREATE tipos:2 CONTENT {saldo: -10.5, pos: {type: 'Point', coordinates: [-46.6, -23.5]}}");

        Engine target = source.copy();

        assertEquals(source.rows(), target.rows());
    }

    @Test
    @DisplayName("chaves inteiras, reais, decimais, textuais e negativas voltam idênticas")
    void everyRecordKeySurvives() throws InterruptedException {
        Engine source = Engine.create();
        source.run("CREATE chave:1 CONTENT {v: 1}");
        source.run("CREATE chave:2.5 CONTENT {v: 2}");
        source.run("CREATE chave:3.0 CONTENT {v: 3}");
        source.run("CREATE chave:ana CONTENT {v: 4}");
        source.run("CREATE chave:\"com espaço, \\\"aspas\\\" e\\nlinha\" CONTENT {v: 5}");
        source.run("CREATE chave:\"123\" CONTENT {v: 6}");
        source.run("CREATE chave CONTENT {id: -7, v: 7}");
        source.run("CREATE chave:12345678901234567.25dec CONTENT {v: 8}");

        Engine target = source.copy();

        assertEquals(8, source.rows().size());
        assertEquals(source.rows(), target.rows());
    }

    @Test
    @DisplayName("chave numérica negativa pode ser escrita no próprio id")
    void negativeRecordKeyLiteral() throws InterruptedException {
        Engine source = Engine.create();
        source.run("CREATE conta:-3 CONTENT {v: 1}");
        source.run("CREATE conta:-2.5 CONTENT {v: 2}");
        source.run("CREATE conta:-4.25dec CONTENT {v: 3}");

        Engine target = source.copy();

        assertEquals(3, source.rows().size());
        assertEquals(source.rows(), target.rows());
        assertEquals(1, target.run("SELECT * FROM conta:-3").asArray().size());
    }

    // ------------------------------------------------------------------ índices

    private static final String ANALYZER = "CREATE ANALYZER pt LOWERCASE STEMMING STOPWORDS \"de\", \"o\"";

    private static final List<String> INDEXES = List.of(
        "DEFINE INDEX uq ON TABLE item COLUMNS codigo UNIQUE",
        "DEFINE INDEX busca ON TABLE item COLUMNS corpo SEARCH ANALYZER pt",
        "DEFINE INDEX vec ON TABLE item COLUMNS emb HNSW DIMENSION 3 DIST cosine M 12 EFC 64 EFS 100",
        "DEFINE INDEX col_saldo ON TABLE item COLUMNS saldo COLUMNAR",
        "DEFINE INDEX col_cat ON TABLE item COLUMNS cat COLUMNAR",
        "DEFINE INDEX lugar ON TABLE item COLUMNS pos GEO",
        "DEFINE INDEX contagem ON TABLE item COLUMNS cat COUNT",
        "DEFINE INDEX comum ON TABLE item COLUMNS cat, quando");

    private static final String VECTOR = "[-0.5, 0.25, 0.0]";

    /** Consultas que respondem o mesmo com ou sem índice. */
    private static final List<String> ANY_PLAN = List.of(
        "SELECT codigo, saldo, quando FROM item ORDER BY codigo",
        "SELECT codigo FROM item WHERE codigo = -1.5",
        "SELECT codigo FROM item WHERE saldo < -2.0 ORDER BY codigo",
        "SELECT codigo FROM item WHERE quando > d\"2024-01-03T00:00:00Z\" ORDER BY codigo",
        "SELECT count() AS n, sum(saldo) AS soma, min(saldo) AS menor, max(saldo) AS maior FROM item GROUP ALL",
        "SELECT cat, count() AS n, sum(saldo) AS soma FROM item GROUP BY cat ORDER BY cat",
        "SELECT codigo FROM item WHERE geo::distance(pos, {type: 'Point', coordinates: [-46.61, -23.55]}) <= 2500 "
            + "ORDER BY codigo",
        "SELECT codigo FROM item ORDER BY vector::distance::cosine(emb, " + VECTOR + ") ASC LIMIT 3");

    /** Consultas que só fazem sentido com o índice. */
    private static final List<String> INDEX_ONLY = List.of(
        "SELECT codigo FROM item WHERE corpo @@ 'motoristas' ORDER BY codigo",
        "SELECT codigo FROM item WHERE emb @@ " + VECTOR + " ORDER BY vector::distance::cosine(emb, " + VECTOR
            + ") ASC LIMIT 3");

    private static String item(int i) {
        return "CREATE item:" + i + " CONTENT {codigo: -" + i + ".5, saldo: -" + i + ".25, "
            + "cat: '" + (i % 2 == 0 ? "a" : "b") + "', "
            + "corpo: '" + (i % 2 == 0 ? "o motorista parado" : "o passageiro esperando") + "', "
            + "emb: [-0." + i + ", 0.25, 0.0], "
            + "pos: {type: 'Point', coordinates: [-46.6" + (i % 10) + ", -23.55]}, "
            + "quando: d\"2024-01-0" + (1 + i % 8) + "T00:00:00Z\"}";
    }

    private static Engine items(boolean indexed) {
        Engine engine = Engine.create();
        engine.run(ANALYZER);
        engine.run("DEFINE TABLE item SCHEMALESS");
        if (indexed) {
            INDEXES.forEach(engine::run);
        }
        for (int i = 1; i <= 8; i++) {
            engine.run(item(i));
        }
        return engine;
    }

    private static Map<String, List<Object>> indexDefinitions(Engine engine, String table) {
        Map<String, List<Object>> out = new TreeMap<>();
        for (Catalog.IndexDef ix : engine.ds.findDatabase("test", "dev").catalog().table(table).indexes().values()) {
            List<Object> options = new ArrayList<>();
            Collections.addAll(options, ix.columns(), ix.unique(), ix.count(), ix.searchAnalyzer(), ix.geo(),
                ix.columnar(), ix.vectorDimension(), ix.vectorDistance(), ix.hnswM(), ix.hnswEfc(), ix.hnswEfs());
            out.put(ix.name(), options);
        }
        return out;
    }

    private static String strategy(Engine engine, String query) {
        return engine.run("EXPLAIN " + query).asObject().get("strategy").asString();
    }

    private static void assertSameAnswers(Engine source, Engine imported, Engine unindexed) {
        for (String query : ANY_PLAN) {
            AxonValue expected = source.run(query);
            assertEquals(AxonJson.write(expected), AxonJson.write(imported.run(query)), query);
            assertEquals(unindexed.run(query), imported.run(query), query);
            assertEquals(strategy(source, query), strategy(imported, query), query);
        }
        for (String query : INDEX_ONLY) {
            assertEquals(AxonJson.write(source.run(query)), AxonJson.write(imported.run(query)), query);
            assertEquals(strategy(source, query), strategy(imported, query), query);
        }
    }

    @Test
    @DisplayName("todos os tipos de índice voltam com as mesmas opções")
    void everyIndexOptionSurvives() throws InterruptedException {
        Engine source = items(true);

        Engine target = source.copy();

        assertEquals(INDEXES.size(), indexDefinitions(target, "item").size());
        assertEquals(indexDefinitions(source, "item"), indexDefinitions(target, "item"));
        assertEquals(source.ds.findDatabase("test", "dev").catalog().analyzers(),
            target.ds.findDatabase("test", "dev").catalog().analyzers());
    }

    @Test
    @DisplayName("a base importada responde igual à original, com e sem índice, antes e depois de novas escritas")
    void importedDatabaseAnswersLikeTheSource() throws InterruptedException {
        Engine source = items(true);
        Engine imported = source.copy();
        Engine unindexed = items(false);

        assertEquals(source.rows(), imported.rows());
        assertSameAnswers(source, imported, unindexed);

        for (Engine engine : List.of(source, imported)) {
            assertThrows(RuntimeException.class, () -> engine.run("CREATE item:99 CONTENT {codigo: -1.5}"));
        }
        for (Engine engine : List.of(source, imported, unindexed)) {
            engine.run(item(9));
            engine.run("UPDATE item:2 SET saldo = -50.75, cat = 'b', codigo = -22.5");
            engine.run("DELETE item:3");
            // o código da linha apagada volta a ficar livre
            engine.run(item(30).replace("codigo: -30.5", "codigo: -3.5"));
        }
        assertSameAnswers(source, imported, unindexed);
        assertEquals(source.rows(), imported.rows());
    }

    @Test
    @DisplayName("SCHEMAFULL com campos tipados aceita o dump e mantém os DEFAULT")
    void typedSchemaAndDefaultsSurvive() throws InterruptedException {
        Engine source = Engine.create();
        for (String definition : List.of(
            "DEFINE TABLE rigida SCHEMAFULL",
            "DEFINE FIELD quando ON TABLE rigida TYPE datetime",
            "DEFINE FIELD saldo ON TABLE rigida TYPE decimal",
            "DEFINE FIELD nivel ON TABLE rigida TYPE float",
            "DEFINE FIELD criado ON TABLE rigida TYPE datetime DEFAULT d\"2020-01-02T03:04:05Z\"",
            "DEFINE FIELD taxa ON TABLE rigida DEFAULT -2.50dec",
            "DEFINE FIELD razao ON TABLE rigida DEFAULT 2.0",
            "DEFINE FIELD grande ON TABLE rigida DEFAULT 10000000000.0",
            "DEFINE FIELD ident ON TABLE rigida DEFAULT u\"123e4567-e89b-12d3-a456-426614174000\"",
            "DEFINE FIELD blob ON TABLE rigida DEFAULT b\"00ff\"",
            "DEFINE FIELD prazo ON TABLE rigida DEFAULT 90s")) {
            source.run(definition);
        }
        source.run("CREATE rigida:1 CONTENT {quando: d\"2024-05-05T00:00:00Z\", saldo: -3.75, nivel: -1.5}");

        Engine target = source.copy();

        assertEquals(source.rows(), target.rows());
        for (Engine engine : List.of(source, target)) {
            engine.run("CREATE rigida:2 CONTENT {quando: d\"2024-06-06T00:00:00Z\", saldo: -1.25, nivel: 3}");
        }
        assertEquals(source.rows(), target.rows());
    }

    // ------------------------------------------------------------------ dumps antigos

    /** Formato escrito pelas versões anteriores: o CONTENT em JSON, com os tipos no envelope marcado. */
    private static final String OLD_DUMP = String.join("\n",
        "DEFINE TABLE antiga SCHEMAFULL;",
        "DEFINE FIELD quando ON TABLE antiga TYPE datetime;",
        "DEFINE INDEX ux ON TABLE antiga COLUMNS saldo UNIQUE;",
        "DEFINE INDEX col ON TABLE antiga COLUMNS peso COLUMNAR;",
        "CREATE antiga:1 CONTENT {\"saldo\":{\"$decimal\":\"-1.50\"},\"peso\":{\"$decimal\":\"-2.25\"},"
            + "\"quando\":{\"$datetime\":\"2024-01-01T00:00:00Z\"},\"prazo\":{\"$duration\":\"90s\"},"
            + "\"ident\":{\"$uuid\":\"123e4567-e89b-12d3-a456-426614174000\"},\"blob\":{\"$bytes\":\"AP8=\"},"
            + "\"alvo\":{\"$table\":\"pessoa\"},\"lista\":[{\"$decimal\":\"-3\"},{\"fundo\":{\"$decimal\":\"-4\"}}],"
            + "\"comum\":{\"$decimal\":5},\"outro\":{\"$foo\":\"x\"}};",
        "CREATE antiga:\"b\" CONTENT {\"saldo\":2,\"peso\":{\"$decimal\":\"-0.75\"},"
            + "\"quando\":{\"$datetime\":\"2024-01-02T00:00:00Z\"},\"nivel\":-2.5};",
        "");

    @Test
    @DisplayName("dump antigo continua importando, agora com os tipos certos nos índices e no SCHEMAFULL")
    void oldDumpImportsWithItsTypes() {
        Engine target = Engine.create();

        target.ds.importDatabase("test", "dev", OLD_DUMP);

        assertEquals(2, target.rows().size());
        AxonValue first = target.run("SELECT * FROM antiga:1").asArray().get(0);
        assertEquals("{\"$decimal\":\"-1.50\"}", AxonJson.write(first.asObject().get("saldo")));
        assertEquals("{\"$decimal\":5}", AxonJson.write(first.asObject().get("comum")));
        assertEquals("{\"$foo\":\"x\"}", AxonJson.write(first.asObject().get("outro")));
        assertThrows(RuntimeException.class, () -> target.run(
            "CREATE antiga:3 CONTENT {saldo: -1.50dec, quando: d\"2024-01-03T00:00:00Z\"}"));
        assertEquals("COLUMNAR_AGG", strategy(target, "SELECT sum(peso) AS s FROM antiga GROUP ALL"));
        assertEquals(new BigDecimal("-3.00").stripTrailingZeros(), target.run("SELECT sum(peso) AS s FROM antiga GROUP ALL")
            .asArray().get(0).asObject().get("s").asDecimal().stripTrailingZeros());
        assertEquals(1, target.run("SELECT * FROM antiga WHERE quando > d\"2024-01-01T12:00:00Z\"").asArray().size());
    }
}
