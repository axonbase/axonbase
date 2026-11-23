package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET .../tables/{t}/stats e campos GeoJSON ponta a ponta por /sql. */
class TableStatsApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Datastore ds;
    private AxonServer server;
    private String token;

    @BeforeEach
    void subir() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("app", "main");
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.port() + "/signin"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        token = AxonJson.parseDocument(r.body()).asObject().get("token").asString();
    }

    @AfterEach
    void descer() throws Exception {
        server.stop();
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private HttpResponse<String> stats(String table) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + server.port()
            + "/admin/v1/databases/app/main/tables/" + table + "/stats"))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sql(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + server.port() + "/sql"))
            .header("Authorization", "Bearer " + token).header("Axon-Ns", "app").header("Axon-Db", "main")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> strings(AxonValue arr) {
        List<String> out = new ArrayList<>();
        arr.asArray().forEach(v -> out.add(v.asString()));
        return out;
    }

    @Test
    void estatisticasComIndiceColumnarGeoEVetor() throws Exception {
        ds.execute("DEFINE TABLE loja SCHEMAFULL; DEFINE FIELD nome ON TABLE loja TYPE string; "
            + "DEFINE FIELD pos ON TABLE loja TYPE geometry; DEFINE INDEX loja_col ON TABLE loja COLUMNS nome COLUMNAR; "
            + "DEFINE INDEX loja_geo ON TABLE loja COLUMNS pos GEO;", session(), null);
        for (int i = 0; i < 25; i++) {
            ds.execute("CREATE loja:" + i + " CONTENT {nome: 'n" + i + "', pos: geometry::point(" + i + ", 1)}",
                session(), null);
        }
        HttpResponse<String> r = stats("loja");
        assertEquals(200, r.statusCode(), r.body());
        AxonValue o = AxonJson.parseDocument(r.body()).asObject().get("data") != null
            ? AxonJson.parseDocument(r.body()).asObject().get("data") : AxonJson.parseDocument(r.body());
        assertEquals(25, o.asObject().get("count").asLong());
        assertEquals(2, o.asObject().get("fields").asArray().size());
        assertEquals(2, o.asObject().get("indexes").asArray().size());
        assertEquals(List.of("pos"), strings(o.asObject().get("geoFields")));
        assertTrue(o.asObject().get("vectorFields").asArray().isEmpty());
    }

    @Test
    void tabelaSemEsquemaDescobreGeoPorAmostraEContaSemIndice() throws Exception {
        ds.execute("DEFINE TABLE ponto SCHEMALESS; DEFINE TABLE vet SCHEMALESS; "
            + "DEFINE INDEX vi ON TABLE vet COLUMNS emb HNSW DIMENSION 3 DIST euclidean;", session(), null);
        for (int i = 0; i < 60; i++) {
            ds.execute("CREATE ponto:" + i + " CONTENT {nome: 'x', local: geometry::point(" + i + ", 2)}",
                session(), null);
        }
        AxonValue o = AxonJson.parseDocument(stats("ponto").body());
        o = o.asObject().get("data") != null ? o.asObject().get("data") : o;
        assertEquals(60, o.asObject().get("count").asLong());
        assertEquals(List.of("local"), strings(o.asObject().get("geoFields")));
        AxonValue v = AxonJson.parseDocument(stats("vet").body());
        v = v.asObject().get("data") != null ? v.asObject().get("data") : v;
        assertEquals(0, v.asObject().get("count").asLong());
        assertEquals(List.of("emb"), strings(v.asObject().get("vectorFields")));
        assertEquals(404, stats("inexistente").statusCode());
    }

    @Test
    void camposGeoJsonViajamIdenticosPorSqlEFuncoesGeoFuncionamNoWhere() throws Exception {
        assertEquals(200, sql("DEFINE TABLE geo SCHEMALESS").statusCode());
        String point = "{\"type\":\"Point\",\"coordinates\":[1,1]}";
        String line = "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1],[2,2]]}";
        String poly = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[4,0],[4,4],[0,4],[0,0]]]}";
        HttpResponse<String> c = sql("CREATE geo:a CONTENT {p: " + point + ", l: " + line + ", a: " + poly + "}");
        assertEquals(200, c.statusCode(), c.body());
        AxonValue doc = ds.execute("SELECT * FROM geo", session(), null).asArray().get(0);
        assertEquals(AxonJson.parseDocument(point), doc.asObject().get("p"));
        assertEquals(AxonJson.parseDocument(line), doc.asObject().get("l"));
        assertEquals(AxonJson.parseDocument(poly), doc.asObject().get("a"));

        String newPoint = "{\"type\":\"Point\",\"coordinates\":[3,3]}";
        HttpResponse<String> u = sql("UPDATE geo:a SET p = " + newPoint);
        assertEquals(200, u.statusCode(), u.body());
        AxonValue after = ds.execute("SELECT * FROM geo", session(), null).asArray().get(0);
        assertEquals(AxonJson.parseDocument(newPoint), after.asObject().get("p"));
        assertEquals(AxonJson.parseDocument(line), after.asObject().get("l"));

        // geo::distance e geo::contains em WHERE, com literais JSON
        HttpResponse<String> near = sql("SELECT * FROM geo WHERE geo::distance(p, " + newPoint + ") < 1");
        assertEquals(200, near.statusCode(), near.body());
        assertTrue(near.body().contains("\"LineString\""), near.body());
        HttpResponse<String> far = sql("SELECT * FROM geo WHERE geo::distance(p, " + point + ") < 1");
        assertTrue(!far.body().contains("LineString"), far.body());
        HttpResponse<String> inside = sql("SELECT * FROM geo WHERE geo::contains(a, p)");
        assertTrue(inside.body().contains("LineString"), inside.body());
        HttpResponse<String> outside = sql("SELECT * FROM geo WHERE geo::contains(a, "
            + "{\"type\":\"Point\",\"coordinates\":[9,9]})");
        assertTrue(!outside.body().contains("LineString"), outside.body());
    }
}
