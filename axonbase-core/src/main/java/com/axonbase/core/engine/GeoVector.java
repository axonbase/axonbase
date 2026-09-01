package com.axonbase.core.engine;

import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Funções geográficas GeoJSON e operações vetoriais sem dependências externas. */
final class GeoVector {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoVector() {
    }

    static AxonValue point(AxonValue lon, AxonValue lat) {
        return geometry("Point", AxonValue.array(List.of(AxonValue.num(lon.asDouble()), AxonValue.num(lat.asDouble()))));
    }

    static AxonValue line(AxonValue points) {
        return geometry("LineString", points);
    }

    static AxonValue polygon(AxonValue rings) {
        return geometry("Polygon", rings);
    }

    static AxonValue distance(AxonValue first, AxonValue second) {
        double[] a = pointOf(first);
        double[] b = pointOf(second);
        if (a == null || b == null) {
            return AxonValue.nul();
        }
        double lat1 = Math.toRadians(a[1]);
        double lat2 = Math.toRadians(b[1]);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b[0] - a[0]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return AxonValue.num(2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h)));
    }

    static AxonValue area(AxonValue geometry) {
        List<double[]> ring = ringOf(geometry);
        if (ring == null || ring.size() < 3) {
            return AxonValue.nul();
        }
        // Aproximação plana, adequada a áreas locais. O resultado é em graus².
        double twice = 0;
        for (int i = 0; i < ring.size(); i++) {
            double[] a = ring.get(i);
            double[] b = ring.get((i + 1) % ring.size());
            twice += a[0] * b[1] - b[0] * a[1];
        }
        return AxonValue.num(Math.abs(twice) / 2);
    }

    static AxonValue contains(AxonValue polygon, AxonValue point) {
        List<double[]> ring = ringOf(polygon);
        double[] p = pointOf(point);
        if (ring == null || p == null) {
            return AxonValue.bool(false);
        }
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double[] a = ring.get(i);
            double[] b = ring.get(j);
            if ((a[1] > p[1]) != (b[1] > p[1])
                && p[0] < (b[0] - a[0]) * (p[1] - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return AxonValue.bool(inside);
    }

    static AxonValue euclidean(AxonValue a, AxonValue b) {
        List<AxonValue> left = vector(a);
        List<AxonValue> right = vector(b);
        if (left.size() != right.size()) {
            return AxonValue.nul();
        }
        double sum = 0;
        for (int i = 0; i < left.size(); i++) {
            double d = left.get(i).asDouble() - right.get(i).asDouble();
            sum += d * d;
        }
        return AxonValue.num(Math.sqrt(sum));
    }

    static AxonValue cosine(AxonValue a, AxonValue b) {
        List<AxonValue> left = vector(a);
        List<AxonValue> right = vector(b);
        if (left.size() != right.size() || left.isEmpty()) {
            return AxonValue.nul();
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < left.size(); i++) {
            double x = left.get(i).asDouble();
            double y = right.get(i).asDouble();
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        return AxonValue.num(na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb));
    }

    static AxonValue cosineDistance(AxonValue a, AxonValue b) {
        AxonValue similarity = cosine(a, b);
        return similarity.isNumber() ? AxonValue.num(1d - similarity.asDouble()) : similarity;
    }

    static AxonValue manhattan(AxonValue a, AxonValue b) {
        List<AxonValue> left = vector(a);
        List<AxonValue> right = vector(b);
        if (left.size() != right.size()) {
            return AxonValue.nul();
        }
        double sum = 0;
        for (int i = 0; i < left.size(); i++) {
            sum += Math.abs(left.get(i).asDouble() - right.get(i).asDouble());
        }
        return AxonValue.num(sum);
    }

    private static AxonValue geometry(String type, AxonValue coordinates) {
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("type", AxonValue.str(type));
        out.put("coordinates", coordinates);
        return AxonValue.object(out);
    }

    private static double[] pointOf(AxonValue geometry) {
        AxonValue coordinates = coordinatesOf(geometry);
        if (coordinates == null || !coordinates.isArray() || coordinates.asArray().size() < 2
            || !coordinates.asArray().get(0).isNumber() || !coordinates.asArray().get(1).isNumber()) {
            return null;
        }
        return new double[] { coordinates.asArray().get(0).asDouble(), coordinates.asArray().get(1).asDouble() };
    }

    private static List<double[]> ringOf(AxonValue geometry) {
        AxonValue coordinates = coordinatesOf(geometry);
        if (coordinates == null || !coordinates.isArray() || coordinates.asArray().isEmpty()) {
            return null;
        }
        AxonValue first = coordinates.asArray().get(0);
        // Polygon tem uma lista de anéis; LineString é diretamente a lista de pontos.
        List<AxonValue> points = first.isArray() && !first.asArray().isEmpty()
            && first.asArray().get(0).isArray() ? first.asArray() : coordinates.asArray();
        List<double[]> out = new ArrayList<>();
        for (AxonValue point : points) {
            double[] value = pointOf(AxonValue.object(Map.of("coordinates", point)));
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    private static AxonValue coordinatesOf(AxonValue geometry) {
        return geometry != null && geometry.isObject() ? geometry.asObject().get("coordinates") : null;
    }

    private static List<AxonValue> vector(AxonValue value) {
        if (value == null || !value.isArray() || !value.asArray().stream().allMatch(AxonValue::isNumber)) {
            return List.of();
        }
        return value.asArray();
    }
}
