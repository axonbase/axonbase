package com.axonbase.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pontos de teste na esfera: sorteios uniformes, pontos a uma distância dada e lugares difíceis. */
final class GeoSamples {

    private static final double EARTH_RADIUS = 6_371_008.8;

    private GeoSamples() {
    }

    /** {@code {lon, lat}} uniforme na esfera. */
    static double[] uniform(Random rnd) {
        double lon = rnd.nextDouble() * 360 - 180;
        double lat = Math.toDegrees(Math.asin(rnd.nextDouble() * 2 - 1));
        return new double[] {lon, lat};
    }

    /** Ponto a {@code meters} de {@code from} no rumo {@code bearing} (radianos), sobre o círculo máximo. */
    static double[] destination(double[] from, double bearing, double meters) {
        double lat1 = Math.toRadians(from[1]);
        double lon1 = Math.toRadians(from[0]);
        double d = meters / EARTH_RADIUS;
        double sin = Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(bearing);
        double lat2 = Math.asin(Math.max(-1, Math.min(1, sin)));
        double lon2 = lon1 + Math.atan2(Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
            Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        double lon = Math.toDegrees(lon2);
        lon = ((lon + 180) % 360 + 360) % 360 - 180;
        return new double[] {lon, Math.toDegrees(lat2)};
    }

    /** Centros onde grades geográficas costumam errar: polos, meridiano de 180 graus, equador e pentágonos. */
    static List<double[]> hardCenters() {
        List<double[]> out = new ArrayList<>(List.of(
            new double[] {0, 90}, new double[] {0, -90}, new double[] {45, 89.999}, new double[] {-120, -89.99},
            new double[] {180, 0}, new double[] {-180, 0}, new double[] {179.9999, -16.8},
            new double[] {-179.9999, 65.5}, new double[] {0, 0}, new double[] {-46.6333, -23.5505}));
        for (long pentagon : H3Cells.core().getPentagons(3)) {
            out.add(H3Cells.center(pentagon));
        }
        return out;
    }
}
