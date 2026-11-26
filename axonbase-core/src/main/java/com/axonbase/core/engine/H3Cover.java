package com.axonbase.core.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Escolhe células H3 cujas subárvores cobrem uma região. Desce a hierarquia a partir das 122 células base: a célula
 * fora da região é descartada, a inteiramente dentro entra como um prefixo só, e a parcial é dividida nos filhos
 * enquanto o total couber no limite. A decisão usa o círculo de raio {@link H3Cells#reachMeters} em volta do centro,
 * que contém todo ponto indexado sob a célula, então a cobertura nunca perde um ponto: no máximo sobra, e o filtro
 * exato corta a sobra. Como tudo é calculado na esfera, polos, meridiano de 180 graus e pentágonos não são casos
 * especiais.
 */
final class H3Cover {

    /** Limite de prefixos lidos por consulta. Precisa ser pelo menos o número de células base (122). */
    static final int MAX_CELLS = 200;

    private static final double EARTH_RADIUS = 6_371_008.8;

    private H3Cover() {
    }

    enum Relation {
        OUTSIDE, PARTIAL, INSIDE
    }

    /** Região consultada, vista por uma célula: um centro e o alcance dos seus descendentes. */
    interface Region {
        Relation relate(double lon, double lat, double reach);

        /** Menor distância possível, em metros, até um ponto da célula; zero quando a região não ordena por distância. */
        default double lowerBound(double lon, double lat, double reach) {
            return 0;
        }
    }

    /** Prefixo de caminho a varrer e a menor distância possível até um ponto dele. */
    record Cell(String path, double lowerBound) {
    }

    /** Disco de raio {@code meters} em volta do ponto, pela mesma conta de {@code geo::distance}. */
    static Region circle(double lon, double lat, double meters) {
        return new Region() {
            @Override
            public Relation relate(double cellLon, double cellLat, double reach) {
                double d = GeoVector.haversine(lon, lat, cellLon, cellLat);
                // distância indefinida (arredondamento nos antípodas) conta como parcial
                if (d - reach > meters) {
                    return Relation.OUTSIDE;
                }
                return d + reach <= meters ? Relation.INSIDE : Relation.PARTIAL;
            }

            @Override
            public double lowerBound(double cellLon, double cellLat, double reach) {
                double d = GeoVector.haversine(lon, lat, cellLon, cellLat) - reach;
                return d > 0 ? d : 0;
            }
        };
    }

    /**
     * Retângulo em graus ({@code oeste..leste}, {@code sul..norte}), como o {@code geo::contains} enxerga um
     * polígono. As longitudes podem passar de -180..180: a comparação é circular.
     */
    static Region box(double west, double south, double east, double north) {
        return (lon, lat, reach) -> {
            double angle = reach / EARTH_RADIUS;
            double degrees = Math.toDegrees(angle);
            double low = lat - degrees;
            double high = lat + degrees;
            if (low > north || high < south) {
                return Relation.OUTSIDE;
            }
            boolean latInside = low >= south && high <= north;
            boolean allAround = east - west >= 360;
            double halfWidth = 180;
            if (high < 90 && low > -90) {
                double ratio = Math.sin(angle) / Math.cos(Math.toRadians(lat));
                if (ratio < 1) {
                    halfWidth = Math.toDegrees(Math.asin(ratio));
                }
            }
            if (halfWidth >= 180) {
                return latInside && allAround ? Relation.INSIDE : Relation.PARTIAL;
            }
            double from = lon - halfWidth;
            double to = lon + halfWidth;
            // existe uma volta k em que [from, to] + 360k toca [west, east]?
            if (Math.ceil((west - to) / 360) > Math.floor((east - from) / 360)) {
                return Relation.OUTSIDE;
            }
            // INSIDE só vale quando a célula inteira cabe na caixa sem dar a volta
            boolean lonInside = allAround || (from >= west && to <= east);
            return latInside && lonInside ? Relation.INSIDE : Relation.PARTIAL;
        };
    }

    /**
     * Cobre a região com no máximo {@code maxCells} prefixos disjuntos (ou as células base que a tocam, se forem
     * mais que isso). Refina um nível por vez e para quando o próximo nível estouraria o limite.
     */
    static List<Cell> cover(Region region, int maxCells) {
        List<Cell> done = new ArrayList<>();
        List<Long> partial = new ArrayList<>();
        List<Cell> partialCells = new ArrayList<>();
        for (long base : H3Cells.baseCells()) {
            classify(region, base, 0, done, partial, partialCells);
        }
        for (int resolution = 1; resolution <= H3Cells.STORAGE_RESOLUTION && !partial.isEmpty(); resolution++) {
            List<Cell> nextDone = new ArrayList<>();
            List<Long> next = new ArrayList<>();
            List<Cell> nextCells = new ArrayList<>();
            boolean fits = true;
            for (long parent : partial) {
                for (long child : H3Cells.children(parent)) {
                    classify(region, child, resolution, nextDone, next, nextCells);
                }
                if (done.size() + nextDone.size() + next.size() > maxCells) {
                    fits = false;
                    break;
                }
            }
            if (!fits) {
                break;
            }
            done.addAll(nextDone);
            partial = next;
            partialCells = nextCells;
        }
        done.addAll(partialCells);
        return done;
    }

    private static void classify(Region region, long cell, int resolution, List<Cell> inside, List<Long> partial,
                                 List<Cell> partialCells) {
        double[] center = H3Cells.center(cell);
        double reach = H3Cells.reachMeters(resolution);
        Relation relation = region.relate(center[0], center[1], reach);
        if (relation == Relation.OUTSIDE) {
            return;
        }
        Cell out = new Cell(H3Cells.path(cell), region.lowerBound(center[0], center[1], reach));
        if (relation == Relation.INSIDE) {
            inside.add(out);
        } else {
            partial.add(cell);
            partialCells.add(out);
        }
    }
}
