package com.axonbase.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Cobertura H3: nenhum ponto da região fica fora das células escolhidas")
class H3CoverTest {

    private static boolean covered(List<H3Cover.Cell> cells, double[] p) {
        String path = H3Cells.pointPath(p[0], p[1]);
        return cells.stream().anyMatch(c -> path.startsWith(c.path()));
    }

    private static void assertDisjoint(List<H3Cover.Cell> cells) {
        List<String> paths = new ArrayList<>(cells.stream().map(H3Cover.Cell::path).toList());
        paths.sort(String::compareTo);
        for (int i = 1; i < paths.size(); i++) {
            assertTrue(!paths.get(i).startsWith(paths.get(i - 1)),
                "células sobrepostas: " + paths.get(i - 1) + " e " + paths.get(i));
        }
    }

    private static void checkCircle(double[] center, double meters, Random rnd) {
        List<H3Cover.Cell> cells = H3Cover.cover(H3Cover.circle(center[0], center[1], meters), H3Cover.MAX_CELLS);
        assertTrue(cells.size() <= H3Cover.MAX_CELLS, "células demais: " + cells.size());
        assertDisjoint(cells);
        for (int i = 0; i < 200; i++) {
            // metade espalhada no disco, metade colada na borda
            double fraction = i % 2 == 0 ? Math.sqrt(rnd.nextDouble()) : 0.999999;
            double[] p = GeoSamples.destination(center, rnd.nextDouble() * 2 * Math.PI, meters * fraction);
            if (GeoVector.haversine(center[0], center[1], p[0], p[1]) <= meters) {
                assertTrue(covered(cells, p), "fora da cobertura: centro " + center[0] + "," + center[1]
                    + " raio " + meters + " ponto " + p[0] + "," + p[1]);
            }
        }
        assertTrue(covered(cells, center), "o centro ficou fora");
    }

    @Test
    @DisplayName("círculos sorteados no globo, de 1 metro a 3 mil quilômetros")
    void randomCircles() {
        Random rnd = new Random(10);
        for (int q = 0; q < 400; q++) {
            checkCircle(GeoSamples.uniform(rnd), Math.pow(10, rnd.nextDouble() * 6.5), rnd);
        }
    }

    @Test
    @DisplayName("círculos nos polos, no meridiano de 180 graus e sobre pentágonos")
    void hardCircles() {
        Random rnd = new Random(11);
        for (double[] center : GeoSamples.hardCenters()) {
            for (double meters : new double[] {0, 1, 30, 500, 5_000, 80_000, 900_000, 5_000_000}) {
                checkCircle(center, meters, rnd);
            }
        }
    }

    @Test
    @DisplayName("raio que alcança o globo todo cobre qualquer ponto")
    void wholeGlobe() {
        Random rnd = new Random(12);
        List<H3Cover.Cell> cells = H3Cover.cover(H3Cover.circle(10, 20, 3e7), H3Cover.MAX_CELLS);
        for (int i = 0; i < 2000; i++) {
            assertTrue(covered(cells, GeoSamples.uniform(rnd)));
        }
    }

    @Test
    @DisplayName("a cobertura de um raio pequeno é seletiva: não alcança pontos distantes")
    void smallCircleIsSelective() {
        Random rnd = new Random(13);
        for (int q = 0; q < 60; q++) {
            double[] center = GeoSamples.uniform(rnd);
            double meters = 500 + rnd.nextDouble() * 20_000;
            List<H3Cover.Cell> cells = H3Cover.cover(H3Cover.circle(center[0], center[1], meters), H3Cover.MAX_CELLS);
            for (int i = 0; i < 50; i++) {
                double[] far = GeoSamples.destination(center, rnd.nextDouble() * 2 * Math.PI,
                    meters * (3 + rnd.nextDouble() * 10));
                assertTrue(!covered(cells, far), "cobertura larga demais: raio " + meters);
            }
        }
    }

    @Test
    @DisplayName("o limite inferior de distância de cada célula não passa da distância de um ponto dela")
    void lowerBoundNeverOverestimates() {
        Random rnd = new Random(14);
        double[] center = {-46.63, -23.55};
        List<H3Cover.Cell> cells = H3Cover.cover(H3Cover.circle(center[0], center[1], 5_000), H3Cover.MAX_CELLS);
        for (int i = 0; i < 4000; i++) {
            double[] p = GeoSamples.destination(center, rnd.nextDouble() * 2 * Math.PI, rnd.nextDouble() * 8_000);
            String path = H3Cells.pointPath(p[0], p[1]);
            double d = GeoVector.haversine(center[0], center[1], p[0], p[1]);
            cells.stream().filter(c -> path.startsWith(c.path()))
                .forEach(c -> assertTrue(c.lowerBound() <= d, c.lowerBound() + " > " + d));
        }
    }

    private static void checkBox(double west, double south, double east, double north, Random rnd) {
        List<H3Cover.Cell> cells = H3Cover.cover(H3Cover.box(west, south, east, north), H3Cover.MAX_CELLS);
        assertTrue(cells.size() <= H3Cover.MAX_CELLS, "células demais: " + cells.size());
        assertDisjoint(cells);
        for (int i = 0; i < 300; i++) {
            double lon = i % 3 == 0 ? (rnd.nextBoolean() ? west : east) : west + rnd.nextDouble() * (east - west);
            double lat = i % 3 == 1 ? (rnd.nextBoolean() ? south : north) : south + rnd.nextDouble() * (north - south);
            if (Math.abs(lat) <= 90) {
                assertTrue(covered(cells, new double[] {lon, lat}), "fora da cobertura: caixa " + west + "," + south
                    + "," + east + "," + north + " ponto " + lon + "," + lat);
            }
        }
    }

    @Test
    @DisplayName("caixas sorteadas, inclusive nos polos e além do meridiano de 180 graus")
    void boxes() {
        Random rnd = new Random(15);
        for (int q = 0; q < 300; q++) {
            double west = rnd.nextDouble() * 360 - 180;
            double south = rnd.nextDouble() * 180 - 90;
            double width = Math.pow(10, rnd.nextDouble() * 6 - 4);
            double height = Math.pow(10, rnd.nextDouble() * 6 - 4);
            checkBox(west, south, Math.min(west + width, west + 360), Math.min(90, south + height), rnd);
        }
        checkBox(170, -10, 190, 10, rnd);
        checkBox(-190, -1, -170, 1, rnd);
        checkBox(-180, 85, 180, 90, rnd);
        checkBox(-180, -90, 180, 90, rnd);
        checkBox(179.9, 89.9, 180, 90, rnd);
        checkBox(-46.66, -23.58, -46.62, -23.54, rnd);
        checkBox(10, 10, 10, 10, rnd);
    }
}
