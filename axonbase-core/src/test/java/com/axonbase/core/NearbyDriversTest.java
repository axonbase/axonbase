package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Motoristas por perto: raio com índice GEO e ORDER BY por apelido")
class NearbyDriversTest {

    private static final String ME = "{type: 'Point', coordinates: [-46.6333, -23.5505]}";

    private Datastore ds;
    private Session session;

    @BeforeEach
    void setUp() {
        ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        session = Session.create();
        session.namespace("test");
        session.database("dev");
        run("DEFINE TABLE motorista SCHEMALESS; DEFINE INDEX motorista_geo ON TABLE motorista COLUMNS pos GEO");
        // ~500 m a leste, ~1500 m ao norte, ~1990 m a oeste (dentro), ~2100 m ao sul e ~6 km a leste (fora)
        driver(1, "Leste", "livre", -46.6287, -23.5505);
        driver(2, "Norte", "ocupado", -46.6333, -23.5370);
        driver(3, "Oeste", "livre", -46.6513, -23.5505);
        driver(4, "Sul", "livre", -46.6333, -23.5694);
        driver(5, "Longe", "livre", -46.5790, -23.5505);
    }

    private void driver(int n, String nome, String status, double lon, double lat) {
        run("CREATE motorista:" + n + " CONTENT {nome: '" + nome + "', status: '" + status
            + "', pos: {type: 'Point', coordinates: [" + lon + ", " + lat + "]}}");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private static List<String> names(AxonValue rows) {
        List<String> out = new ArrayList<>();
        rows.asArray().forEach(r -> out.add(r.asObject().get("nome").asString()));
        return out;
    }

    @Test
    @DisplayName("devolve só quem está a até 2 km e usa o índice GEO")
    void radiusFilterUsesTheGeoIndex() {
        AxonValue rows = run("SELECT nome FROM motorista WHERE geo::distance(pos, " + ME + ") <= 2000");
        List<String> found = names(rows);
        found.sort(String::compareTo);
        assertEquals(List.of("Leste", "Norte", "Oeste"), found);
        assertEquals("GEO", run("EXPLAIN SELECT nome FROM motorista WHERE geo::distance(pos, " + ME + ") <= 2000")
            .asObject().get("strategy").asString());
    }

    @Test
    @DisplayName("ORDER BY pelo apelido da distância ordena do mais perto ao mais longe")
    void orderByAliasOfComputedColumn() {
        AxonValue rows = run("SELECT nome, geo::distance(pos, " + ME + ") AS metros FROM motorista WHERE "
            + "geo::distance(pos, " + ME + ") <= 2000 ORDER BY metros");
        assertEquals(List.of("Leste", "Norte", "Oeste"), names(rows));
        double first = rows.asArray().get(0).asObject().get("metros").asDouble();
        assertTrue(first > 400 && first < 600, "distância em metros: " + first);

        AxonValue desc = run("SELECT nome, geo::distance(pos, " + ME + ") AS metros FROM motorista "
            + "ORDER BY metros DESC LIMIT 2");
        assertEquals(List.of("Longe", "Sul"), names(desc));
    }

    @Test
    @DisplayName("o raio combina com outro filtro e continua no índice")
    void radiusAndStatus() {
        String where = "geo::distance(pos, " + ME + ") <= 2000 AND status = 'livre'";
        List<String> found = names(run("SELECT nome FROM motorista WHERE " + where));
        found.sort(String::compareTo);
        assertEquals(List.of("Leste", "Oeste"), found);
        assertEquals("GEO", run("EXPLAIN SELECT nome FROM motorista WHERE " + where).asObject().get("strategy").asString());
    }

    @Test
    @DisplayName("apelido que nomeia uma coluna simples não muda a ordenação")
    void plainAliasKeepsWorking() {
        AxonValue rows = run("SELECT nome AS n FROM motorista ORDER BY nome DESC LIMIT 1");
        assertEquals("Sul", rows.asArray().get(0).asObject().get("n").asString());
    }
}
