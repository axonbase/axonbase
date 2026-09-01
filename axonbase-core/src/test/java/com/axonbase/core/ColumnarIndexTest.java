package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Columnar index: consultas analíticas e por igualdade")
class ColumnarIndexTest {

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private Datastore ds() {
        Datastore ds = new Datastore(new MemoryBackend());
        ds.createDatabase("test", "dev");
        ds.execute("DEFINE TABLE users SCHEMALESS", session(), null);
        ds.execute("DEFINE INDEX age_idx ON TABLE users COLUMNS age COLUMNAR", session(), null);
        ds.execute("DEFINE INDEX city_idx ON TABLE users COLUMNS city COLUMNAR", session(), null);
        return ds;
    }

    @Test
    void selectCountComIndexColunar() {
        Datastore ds = ds();
        for (int i = 0; i < 100; i++) {
            ds.execute("CREATE users CONTENT { age: " + (20 + i % 50) + ", city: \"city-" + (i % 10) + "\" }",
                session(), null);
        }
        AxonValue result = ds.execute("SELECT count() FROM users", session(), null);
        assertTrue(result.isArray());
        assertEquals(100, result.asArray().get(0).asObject().get("count").asLong());
    }

    @Test
    void selectSumComIndexColunar() {
        Datastore ds = ds();
        for (int i = 0; i < 10; i++) {
            ds.execute("CREATE users CONTENT { age: " + (i + 1) + ", city: \"city-0\" }", session(), null);
        }
        AxonValue result = ds.execute("SELECT sum(age) FROM users", session(), null);
        assertTrue(result.isArray());
        assertEquals(55, result.asArray().get(0).asObject().get("sum").asLong());
    }

    @Test
    void selectAvgComIndexColunar() {
        Datastore ds = ds();
        for (int i = 0; i < 10; i++) {
            ds.execute("CREATE users CONTENT { age: " + (i + 1) + " }", session(), null);
        }
        AxonValue result = ds.execute("SELECT avg(age) FROM users", session(), null);
        assertTrue(result.isArray());
        assertEquals(5.5, result.asArray().get(0).asObject().get("avg").asDecimal().doubleValue(), 0.001);
    }

    @Test
    void selectMinMaxComIndexColunar() {
        Datastore ds = ds();
        for (int i = 0; i < 10; i++) {
            ds.execute("CREATE users CONTENT { age: " + (i + 1) + " }", session(), null);
        }
        AxonValue result = ds.execute("SELECT min(age), max(age) FROM users", session(), null);
        assertTrue(result.isArray());
        var row = result.asArray().get(0).asObject();
        assertEquals(1, row.get("min").asLong());
        assertEquals(10, row.get("max").asLong());
    }

    @Test
    void igualdadeUsaIndexColunar() {
        Datastore ds = ds();
        ds.execute("CREATE users:id1 CONTENT { age: 25, city: \"SP\" }", session(), null);
        ds.execute("CREATE users:id2 CONTENT { age: 30, city: \"RJ\" }", session(), null);
        ds.execute("CREATE users:id3 CONTENT { age: 25, city: \"BH\" }", session(), null);

        AxonValue result = ds.execute("SELECT * FROM users WHERE age = 25", session(), null);
        assertTrue(result.isArray());
        assertEquals(2, result.asArray().size());
    }

    @Test
    void groupByComIndexColunar() {
        Datastore ds = ds();
        ds.execute("CREATE users CONTENT { age: 25, city: \"SP\" }", session(), null);
        ds.execute("CREATE users CONTENT { age: 30, city: \"RJ\" }", session(), null);
        ds.execute("CREATE users CONTENT { age: 25, city: \"BH\" }", session(), null);
        ds.execute("CREATE users CONTENT { age: 30, city: \"SP\" }", session(), null);

        AxonValue result = ds.execute("SELECT count() FROM users GROUP BY city", session(), null);
        assertTrue(result.isArray());
        // SP has 2 users, RJ has 1, BH has 1
        assertEquals(3, result.asArray().size());
        for (AxonValue row : result.asArray()) {
            long count = row.asObject().get("count").asLong();
            assertTrue(count == 2 || count == 1, "Unexpected count: " + count);
        }
    }

    @Test
    void indexMantidoNoUpdate() {
        Datastore ds = ds();
        ds.execute("CREATE users:id1 CONTENT { age: 20, city: \"SP\" }", session(), null);
        ds.execute("UPDATE users:id1 SET age = 35", session(), null);

        AxonValue result = ds.execute("SELECT * FROM users WHERE age = 35", session(), null);
        assertEquals(1, result.asArray().size());

        result = ds.execute("SELECT * FROM users WHERE age = 20", session(), null);
        assertEquals(0, result.asArray().size());
    }

    @Test
    void indexMantidoNoDelete() {
        Datastore ds = ds();
        ds.execute("CREATE users:id1 CONTENT { age: 25, city: \"SP\" }", session(), null);
        ds.execute("DELETE users:id1", session(), null);

        AxonValue result = ds.execute("SELECT * FROM users WHERE age = 25", session(), null);
        assertEquals(0, result.asArray().size());
    }

    @Test
    void explicaPlanColunar() {
        Datastore ds = ds();
        ds.execute("CREATE users CONTENT { age: 30, city: \"SP\" }", session(), null);
        AxonValue explain = ds.execute("EXPLAIN SELECT * FROM users WHERE age = 30", session(), null);
        assertTrue(explain.isObject(), "EXPLAIN should return an object, got: " + explain);
        String strategy = explain.asObject().get("strategy").asString();
        assertEquals("COLUMNAR", strategy, "Expected COLUMNAR strategy, got: " + strategy);
        assertEquals("age_idx", explain.asObject().get("indexName").asString());
    }
}