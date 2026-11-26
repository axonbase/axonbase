package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Massa dos testes geográficos: duas tabelas com as mesmas linhas, {@code g} com índice GEO e {@code h} sem índice.
 * A varredura de {@code h} é a força bruta com a distância exata, e as respostas de {@code g} têm de ser iguais.
 */
final class GeoTestData {

    static final String INDEX = "DEFINE INDEX g_pos ON TABLE g COLUMNS pos GEO";

    private GeoTestData() {
    }

    static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    static AxonValue run(Datastore ds, String sql) {
        return ds.execute(sql, session(), null);
    }

    static String point(double lon, double lat) {
        return "{type: 'Point', coordinates: [" + plain(lon) + ", " + plain(lat) + "]}";
    }

    /** Decimal sem notação científica, com todos os dígitos do double: o literal volta ao mesmo valor. */
    static String plain(double v) {
        return new java.math.BigDecimal(Double.toString(v)).toPlainString();
    }

    static List<Long> ns(Datastore ds, String sql) {
        return run(ds, sql).asArray().stream().map(r -> r.asObject().get("n").asLong()).toList();
    }

    static List<Long> sortedNs(Datastore ds, String sql) {
        return ns(ds, sql).stream().sorted().toList();
    }

    static String strategy(Datastore ds, String sql) {
        return run(ds, "EXPLAIN " + sql).asObject().get("strategy").asString();
    }

    /** Cria a linha {@code n} nas duas tabelas. */
    static void insert(Datastore ds, int n, double[] at) {
        String body = "{n: " + n + ", status: '" + (n % 3 == 0 ? "x" : "y") + "', pos: " + point(at[0], at[1]) + "}";
        run(ds, "CREATE g:" + n + " CONTENT " + body);
        run(ds, "CREATE h:" + n + " CONTENT " + body);
    }

    /**
     * Pontos {@code {lon, lat}} nos lugares em que índices geográficos erram: cidade densa, dos dois lados do
     * meridiano de 180 graus, nos dois polos, em centros e vértices de células H3 (bordas) e no globo inteiro.
     */
    static List<double[]> points(long seed) {
        Random rnd = new Random(seed);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            out.add(new double[] {-46.70 + rnd.nextDouble() * 0.12, -23.60 + rnd.nextDouble() * 0.12});
        }
        for (int i = 0; i < 150; i++) {
            double offset = rnd.nextDouble() * 0.2;
            out.add(new double[] {i % 2 == 0 ? 180 - offset : -180 + offset, -16.9 + rnd.nextDouble() * 0.2});
        }
        out.add(new double[] {180, -16.8});
        out.add(new double[] {-180, -16.8});
        for (int i = 0; i < 80; i++) {
            double lon = rnd.nextDouble() * 360 - 180;
            out.add(new double[] {lon, (i % 2 == 0 ? 1 : -1) * (90 - rnd.nextDouble() * 0.2)});
        }
        out.add(new double[] {0, 90});
        out.add(new double[] {130, 90});
        out.add(new double[] {-45, -90});
        out.addAll(cellEdges(-46.6333, -23.5505));
        for (int i = 0; i < 250; i++) {
            out.add(new double[] {rnd.nextDouble() * 360 - 180,
                Math.toDegrees(Math.asin(rnd.nextDouble() * 2 - 1))});
        }
        // repetidos: empates de distância
        for (int i = 0; i < 40; i++) {
            out.add(out.get(i % 8));
        }
        return out;
    }

    /** Centro e vértices de células em várias resoluções em volta do ponto. */
    static List<double[]> cellEdges(double lon, double lat) {
        H3Core h3;
        try {
            h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<double[]> out = new ArrayList<>();
        for (int resolution : new int[] {4, 6, 8, 10, 12}) {
            long cell = h3.latLngToCell(lat, lon, resolution);
            LatLng center = h3.cellToLatLng(cell);
            out.add(new double[] {center.lng, center.lat});
            for (LatLng v : h3.cellToBoundary(cell)) {
                out.add(new double[] {v.lng, v.lat});
            }
        }
        return out;
    }
}
