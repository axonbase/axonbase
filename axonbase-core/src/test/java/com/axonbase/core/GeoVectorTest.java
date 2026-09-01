package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoVectorTest {

    private Datastore datastore() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private Session session() {
        Session session = Session.create();
        session.namespace("test");
        session.database("dev");
        return session;
    }

    @Test
    void geojsonDistanciaAreaEContencao() {
        Datastore ds = datastore();
        Session s = session();
        AxonValue point = ds.execute("RETURN geometry::point(-43.2, -22.9)", s, null);
        assertEquals("Point", point.asObject().get("type").asString());

        AxonValue distance = ds.execute("RETURN geo::distance(geometry::point(0, 0), geometry::point(0, 1))", s, null);
        assertTrue(distance.asDouble() > 110_000 && distance.asDouble() < 112_000);

        AxonValue polygon = ds.execute("RETURN geometry::polygon([[[0, 0], [2, 0], [2, 2], [0, 2]]])", s, null);
        assertEquals(4, ds.execute("RETURN geo::area($p)", s, java.util.Map.of("p", polygon)).asLong());
        assertTrue(ds.execute("RETURN geo::contains($p, geometry::point(1, 1))", s,
            java.util.Map.of("p", polygon)).asBool());
    }

    @Test
    void distanciaESimilaridadeVetorial() {
        Datastore ds = datastore();
        Session s = session();
        assertEquals(5, ds.execute("RETURN vector::distance::euclidean([0, 0], [3, 4])", s, null).asLong());
        assertEquals(1, ds.execute("RETURN vector::similarity::cosine([1, 0], [5, 0])", s, null).asLong());
        assertEquals(0, ds.execute("RETURN vector::distance::cosine([1, 0], [5, 0])", s, null).asLong());
        assertEquals(2, ds.execute("RETURN vector::distance::cosine([1, 0], [-1, 0])", s, null).asLong());
        assertEquals(7, ds.execute("RETURN vector::distance::manhattan([1, 2], [4, 6])", s, null).asLong());
    }

    @Test
    void indiceVetorialRestringeAOrdenacaoExataPorDistancia() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("CREATE item:a CONTENT {name: \"perto\", embedding: [0, 1]}", s, null);
        ds.execute("CREATE item:b CONTENT {name: \"longe\", embedding: [8, 8]}", s, null);
        ds.execute("DEFINE INDEX embedding_hnsw ON TABLE item COLUMNS embedding HNSW DIMENSION 2 DIST euclidean", s, null);

        AxonValue rows = ds.execute("SELECT name FROM item ORDER BY "
            + "vector::distance::euclidean(embedding, [0, 0]) LIMIT 1", s, null);
        assertEquals("perto", rows.asArray().get(0).asObject().get("name").asString());
    }

    @Test
    void indiceHnswCosineForneceVizinhoMaisProximoEPlanoVetorial() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("CREATE item:exact CONTENT {name: \"exact\", embedding: [5, 0]}", s, null);
        ds.execute("CREATE item:orthogonal CONTENT {name: \"orthogonal\", embedding: [0, 1]}", s, null);
        ds.execute("CREATE item:opposite CONTENT {name: \"opposite\", embedding: [-1, 0]}", s, null);
        ds.execute("DEFINE INDEX embedding_hnsw ON TABLE item COLUMNS embedding HNSW DIMENSION 2 DIST cosine", s, null);

        AxonValue rows = ds.execute("SELECT name FROM item ORDER BY "
            + "vector::distance::cosine(embedding, [1, 0]) LIMIT 1", s, null);
        assertEquals("exact", rows.asArray().get(0).asObject().get("name").asString());

        AxonValue plan = ds.execute("EXPLAIN SELECT name FROM item ORDER BY "
            + "vector::distance::cosine(embedding, [1, 0]) LIMIT 1", s, null);
        assertEquals("VECTOR", plan.asObject().get("strategy").asString());
        assertEquals("embedding_hnsw", plan.asObject().get("indexName").asString());
    }

    @Test
    void indiceGeoNaoPerdeRegistrosAposUpdate() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE INDEX point_geo ON TABLE place COLUMNS point GEO", s, null);
        ds.execute("CREATE place:a CONTENT {point: geometry::point(0, 0)}", s, null);
        ds.execute("UPDATE place:a SET point = geometry::point(1, 1)", s, null);
        AxonValue rows = ds.execute("SELECT * FROM place WHERE "
            + "geo::distance(point, geometry::point(1, 1)) < 1", s, null);
        assertEquals(1, rows.asArray().size());
    }

    @Test
    void indiceGeoIndexaGeometriasNaoPontuais() {
        Datastore ds = datastore();
        Session s = session();
        ds.execute("DEFINE INDEX geo ON TABLE area COLUMNS shape GEO", s, null);
        ds.execute("CREATE area:r CONTENT {shape: geometry::polygon([[[0, 0], [2, 0], [2, 2], [0, 2], [0, 0]]])}", s, null);
        ds.execute("CREATE area:l CONTENT {shape: geometry::line([[0, 0], [1, 1]])}", s, null);

        AxonValue rows = ds.execute("SELECT * FROM area WHERE "
            + "geo::distance(shape, geometry::point(0.05, 0.05)) < 5000", s, null);
        assertEquals(2, rows.asArray().size());
    }
}
