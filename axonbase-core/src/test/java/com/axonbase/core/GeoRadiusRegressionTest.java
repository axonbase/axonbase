package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressão do índice GEO: raios grandes (dezenas de km) devem encontrar todos
 * os pontos dentro do raio, mesmo quando a cobertura geohash usa uma precisão
 * mais grossa que a de armazenamento.
 */
class GeoRadiusRegressionTest {

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private Datastore ds() {
        Datastore ds = new Datastore(new MemoryBackend());
        ds.createDatabase("test", "dev");
        return ds;
    }

    @Test
    void raioGrandeEncontraTodosOsPontos() {
        Datastore ds = ds();
        Session s = session();
        ds.execute("DEFINE INDEX shop_geo ON TABLE shop COLUMNS loc GEO", s, null);
        ds.execute("CREATE shop:a CONTENT {name: 'Loja A', loc: geometry::point(-43.2, -22.9)}", s, null);
        ds.execute("CREATE shop:b CONTENT {name: 'Loja B', loc: geometry::point(-43.3, -22.8)}", s, null);

        // Raio 500 km: ambos os pontos (~11.7 km de distância do centro) devem aparecer.
        AxonValue result = ds.execute(
            "SELECT name FROM shop WHERE geo::distance(loc, geometry::point(-43.2, -22.85)) < 500000",
            s, null);
        assertTrue(result.isArray());
        assertEquals(2, result.asArray().size(), "raio 500km deveria retornar A e B");
    }

    @Test
    void raioMedioEncontraTodosOsPontos() {
        Datastore ds = ds();
        Session s = session();
        ds.execute("DEFINE INDEX shop_geo ON TABLE shop COLUMNS loc GEO", s, null);
        ds.execute("CREATE shop:a CONTENT {name: 'Loja A', loc: geometry::point(-43.2, -22.9)}", s, null);
        ds.execute("CREATE shop:b CONTENT {name: 'Loja B', loc: geometry::point(-43.3, -22.8)}", s, null);

        // Raio 20 km: A (~5.5 km) e B (~11.7 km) dentro do raio.
        AxonValue result = ds.execute(
            "SELECT name FROM shop WHERE geo::distance(loc, geometry::point(-43.2, -22.85)) < 20000",
            s, null);
        assertTrue(result.isArray());
        assertEquals(2, result.asArray().size(), "raio 20km deveria retornar A e B");
    }

    @Test
    void raioPequenoFiltraCorretamente() {
        Datastore ds = ds();
        Session s = session();
        ds.execute("DEFINE INDEX shop_geo ON TABLE shop COLUMNS loc GEO", s, null);
        ds.execute("CREATE shop:a CONTENT {name: 'Loja A', loc: geometry::point(-43.2, -22.9)}", s, null);
        ds.execute("CREATE shop:b CONTENT {name: 'Loja B', loc: geometry::point(-43.3, -22.8)}", s, null);

        // Raio 2 km: nenhum dentro (A está a ~5.5 km do centro).
        AxonValue result = ds.execute(
            "SELECT name FROM shop WHERE geo::distance(loc, geometry::point(-43.2, -22.85)) < 2000",
            s, null);
        assertTrue(result.isArray());
        assertEquals(0, result.asArray().size(), "raio 2km não deveria retornar nenhum");
    }

    @Test
    void raioComBackfillIndiceCriadoDepois() {
        // Backup de TDD para o caso do índice criado depois dos dados (backfill).
        Datastore ds = ds();
        Session s = session();
        ds.execute("CREATE shop:a CONTENT {name: 'Loja A', loc: geometry::point(-43.2, -22.9)}", s, null);
        ds.execute("CREATE shop:b CONTENT {name: 'Loja B', loc: geometry::point(-43.3, -22.8)}", s, null);
        ds.execute("DEFINE INDEX shop_geo ON TABLE shop COLUMNS loc GEO", s, null);

        AxonValue result = ds.execute(
            "SELECT name FROM shop WHERE geo::distance(loc, geometry::point(-43.2, -22.85)) < 500000",
            s, null);
        assertTrue(result.isArray());
        assertEquals(2, result.asArray().size(), "backfill deveria funcionar com raio 500km");
    }
}