package com.axonbase.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Células H3: caminho hierárquico e alcance dos descendentes")
class H3CellsTest {

    private static List<Long> allCells(int resolution) {
        List<Long> out = new ArrayList<>();
        for (long base : H3Cells.baseCells()) {
            if (resolution == 0) {
                out.add(base);
            } else {
                out.addAll(H3Cells.core().cellToChildren(base, resolution));
            }
        }
        return out;
    }

    private static double meters(double[] a, double[] b) {
        return GeoVector.haversine(a[0], a[1], b[0], b[1]);
    }

    @Test
    @DisplayName("o caminho de cada ancestral é prefixo do caminho da célula de armazenamento")
    void ancestorPathIsPrefix() {
        Random rnd = new Random(1);
        for (int i = 0; i < 3000; i++) {
            double[] p = GeoSamples.uniform(rnd);
            long cell = H3Cells.cellOf(p[0], p[1], H3Cells.STORAGE_RESOLUTION);
            String path = H3Cells.path(cell);
            assertEquals(H3Cells.PATH_LENGTH, path.length());
            assertEquals(path, H3Cells.pointPath(p[0], p[1]));
            for (int r = 0; r <= H3Cells.STORAGE_RESOLUTION; r++) {
                String parent = H3Cells.path(H3Cells.core().cellToParent(cell, r));
                assertEquals(3 + r, parent.length());
                assertTrue(path.startsWith(parent), path + " não começa com " + parent);
            }
        }
    }

    @Test
    @DisplayName("filhos têm caminhos distintos que estendem o do pai, inclusive nos pentágonos")
    void childrenExtendTheParentPath() {
        List<Long> parents = new ArrayList<>(H3Cells.core().getPentagons(2));
        parents.add(H3Cells.cellOf(-46.63, -23.55, 5));
        for (long parent : parents) {
            List<Long> children = H3Cells.children(parent);
            Set<String> paths = new HashSet<>();
            for (long child : children) {
                String path = H3Cells.path(child);
                assertTrue(path.startsWith(H3Cells.path(parent)));
                assertEquals(H3Cells.path(parent).length() + 1, path.length());
                paths.add(path);
            }
            assertEquals(children.size(), paths.size());
            assertTrue(children.size() == 6 || children.size() == 7);
        }
    }

    @Test
    @DisplayName("longitude fora de -180..180 cai na mesma célula do ponto equivalente")
    void longitudeWraps() {
        assertEquals(H3Cells.pointPath(-170, 10), H3Cells.pointPath(190, 10));
        assertEquals(H3Cells.pointPath(-170, 10), H3Cells.pointPath(550, 10));
        assertEquals(H3Cells.pointPath(170, 10), H3Cells.pointPath(-190, 10));
    }

    @Test
    @DisplayName("nenhum ponto fica além do alcance de qualquer ancestral da sua célula")
    void reachCoversEveryDescendantPoint() {
        Random rnd = new Random(2);
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            points.add(GeoSamples.uniform(rnd));
        }
        for (double[] center : GeoSamples.hardCenters()) {
            for (int i = 0; i < 500; i++) {
                points.add(GeoSamples.destination(center, rnd.nextDouble() * 2 * Math.PI,
                    Math.pow(10, rnd.nextDouble() * 6)));
            }
            points.add(center);
        }
        double worst = 0;
        for (double[] p : points) {
            long cell = H3Cells.cellOf(p[0], p[1], H3Cells.STORAGE_RESOLUTION);
            for (int r = 0; r <= H3Cells.STORAGE_RESOLUTION; r++) {
                double d = meters(p, H3Cells.center(H3Cells.core().cellToParent(cell, r)));
                double ratio = d / H3Cells.reachMeters(r);
                worst = Math.max(worst, ratio);
                assertTrue(ratio <= 1, "res " + r + " ponto " + p[0] + "," + p[1] + " razão " + ratio);
            }
        }
        System.out.println("H3 alcance: pior razão distância/alcance = " + worst);
    }

    @Test
    @DisplayName("alcance vale por indução nas resoluções grossas, conferido célula por célula")
    void reachHoldsByInduction() {
        double worst = 0;
        for (int r = 0; r <= 2; r++) {
            for (long cell : allCells(r)) {
                double[] center = H3Cells.center(cell);
                double far = 0;
                for (long descendant : H3Cells.core().cellToChildren(cell, r + 3)) {
                    far = Math.max(far, meters(center, H3Cells.center(descendant)));
                }
                double ratio = (far + H3Cells.reachMeters(r + 3)) / H3Cells.reachMeters(r);
                worst = Math.max(worst, ratio);
                assertTrue(ratio <= 1, "res " + r + " célula " + Long.toHexString(cell) + " razão " + ratio);
            }
        }
        System.out.println("H3 alcance: pior razão da indução = " + worst);
    }

    @Test
    @DisplayName("todo vértice de célula está dentro do alcance da própria célula")
    void reachCoversCellBoundaries() {
        for (int r = 0; r <= 3; r++) {
            for (long cell : allCells(r)) {
                double[] center = H3Cells.center(cell);
                for (var vertex : H3Cells.core().cellToBoundary(cell)) {
                    double d = meters(center, new double[] {vertex.lng, vertex.lat});
                    assertTrue(d <= H3Cells.reachMeters(r), "res " + r + " célula " + Long.toHexString(cell));
                }
            }
        }
    }
}
