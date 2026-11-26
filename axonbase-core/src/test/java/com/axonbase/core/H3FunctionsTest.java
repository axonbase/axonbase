package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.axonbase.core.GeoTestData.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Funções geo::h3 em AxonQL")
class H3FunctionsTest {

    private static final String SP = point(-46.6333, -23.5505);

    private final Datastore ds = new Datastore(new MemoryBackend());

    private AxonValue run(String sql) {
        ds.createDatabase("test", "dev");
        return GeoTestData.run(ds, sql);
    }

    private String cell(int resolution) {
        return run("RETURN geo::h3(" + SP + ", " + resolution + ")").asString();
    }

    @Test
    @DisplayName("célula do ponto, na forma hexadecimal do H3")
    void cellOfAPoint() {
        String cell = cell(9);
        assertEquals(15, cell.length());
        assertTrue(cell.startsWith("89"), cell);
        assertEquals(9, run("RETURN geo::h3::resolution('" + cell + "')").asLong());
        // longitude equivalente cai na mesma célula
        assertEquals(cell, run("RETURN geo::h3(" + point(313.3667, -23.5505) + ", 9)").asString());
    }

    @Test
    @DisplayName("centro e contorno desenham a célula que contém o ponto")
    void centerAndBoundary() {
        String cell = cell(8);
        AxonValue center = run("RETURN geo::h3::center('" + cell + "')");
        assertEquals("Point", center.asObject().get("type").asString());
        assertTrue(run("RETURN geo::distance(geo::h3::center('" + cell + "'), " + SP + ")").asDouble() < 800);
        AxonValue boundary = run("RETURN geo::h3::boundary('" + cell + "')");
        assertEquals("Polygon", boundary.asObject().get("type").asString());
        List<AxonValue> ring = boundary.asObject().get("coordinates").asArray().get(0).asArray();
        assertEquals(7, ring.size(), "hexágono fechado");
        assertEquals(ring.get(0), ring.get(6));
        assertTrue(run("RETURN geo::contains(geo::h3::boundary('" + cell + "'), " + SP + ")").asBool());
    }

    @Test
    @DisplayName("pai, filhos e vizinhos")
    void hierarchyAndNeighbours() {
        String cell = cell(9);
        String parent = run("RETURN geo::h3::parent('" + cell + "', 7)").asString();
        assertEquals(7, run("RETURN geo::h3::resolution('" + parent + "')").asLong());
        List<AxonValue> children = run("RETURN geo::h3::children('" + parent + "', 9)").asArray();
        assertEquals(49, children.size());
        assertTrue(children.contains(AxonValue.str(cell)));
        assertEquals(1, run("RETURN geo::h3::disk('" + cell + "', 0)").asArray().size());
        List<AxonValue> disk = run("RETURN geo::h3::disk('" + cell + "', 2)").asArray();
        assertEquals(19, disk.size());
        assertTrue(disk.contains(AxonValue.str(cell)));
    }

    @Test
    @DisplayName("agrupa linhas por célula")
    void groupsRows() {
        run("CREATE ping:1 CONTENT {pos: " + SP + "}");
        run("CREATE ping:2 CONTENT {pos: " + point(-46.63331, -23.55051) + "}");
        run("CREATE ping:3 CONTENT {pos: " + point(-43.2, -22.9) + "}");
        AxonValue rows = run("SELECT geo::h3(pos, 7) AS celula, count() AS n FROM ping GROUP BY celula ORDER BY n DESC");
        assertEquals(2, rows.asArray().size());
        assertEquals(2, rows.asArray().get(0).asObject().get("n").asLong());
    }

    @Test
    @DisplayName("entrada inválida devolve nulo")
    void invalidInputIsNull() {
        String cell = cell(9);
        for (String call : List.of("geo::h3('x', 9)", "geo::h3(" + SP + ", 16)", "geo::h3(" + SP + ", -1)",
            "geo::h3(" + SP + ", 'a')", "geo::h3(" + point(0, 95) + ", 5)", "geo::h3::center('zzz')",
            "geo::h3::center(12)", "geo::h3::boundary('0')", "geo::h3::resolution('')",
            "geo::h3::parent('" + cell + "', 10)", "geo::h3::children('" + cell + "', 3)",
            "geo::h3::disk('" + cell + "', -1)")) {
            assertTrue(run("RETURN " + call).isNull(), call);
        }
    }

    @Test
    @DisplayName("pedidos que gerariam células demais dão erro claro")
    void tooManyCellsFails() {
        String cell = cell(2);
        assertThrows(AxonError.class, () -> run("RETURN geo::h3::children('" + cell + "', 12)"));
        assertThrows(AxonError.class, () -> run("RETURN geo::h3::disk('" + cell + "', 500)"));
    }
}
