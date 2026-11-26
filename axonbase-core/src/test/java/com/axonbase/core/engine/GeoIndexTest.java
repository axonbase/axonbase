package com.axonbase.core.engine;

import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Índice GEO sobre H3: chaves, consultas e marcadores")
class GeoIndexTest {

    private final MemoryBackend kv = new MemoryBackend();
    private final GeoIndex index = new GeoIndex("ns", "db", "t", "i");

    private static AxonValue point(double[] p) {
        return GeoVector.point(AxonValue.num(p[0]), AxonValue.num(p[1]));
    }

    private static AxonValue line(double[]... points) {
        List<AxonValue> out = new ArrayList<>();
        for (double[] p : points) {
            out.add(AxonValue.array(List.of(AxonValue.num(p[0]), AxonValue.num(p[1]))));
        }
        return GeoVector.line(AxonValue.array(out));
    }

    /** Pontos em volta dos lugares difíceis e espalhados no globo; as chaves têm separadores usados no índice. */
    private Map<String, double[]> seed(int perCenter, long seed) {
        Random rnd = new Random(seed);
        Map<String, double[]> out = new LinkedHashMap<>();
        int n = 0;
        for (double[] center : GeoSamples.hardCenters()) {
            for (int i = 0; i < perCenter; i++) {
                double[] p = GeoSamples.destination(center, rnd.nextDouble() * 2 * Math.PI,
                    Math.pow(10, rnd.nextDouble() * 5));
                out.put("sk|" + (n++) + ",x", p);
            }
        }
        for (int i = 0; i < 500; i++) {
            out.put("sg" + i, GeoSamples.uniform(rnd));
        }
        out.forEach((id, p) -> index.update(kv, id, point(p)));
        return out;
    }

    private static double meters(double[] a, double[] b) {
        return GeoVector.haversine(a[0], a[1], b[0], b[1]);
    }

    private TreeSet<String> radius(double[] center, double r) {
        TreeSet<String> out = new TreeSet<>();
        List<String> raw = new ArrayList<>();
        assertTrue(index.radius(kv, center, r, id -> {
            raw.add(id);
            return out.add(id) || true;
        }));
        assertEquals(out.size(), raw.size(), "candidato repetido");
        return out;
    }

    @Test
    @DisplayName("raio devolve exatamente os pontos da força bruta, em qualquer lugar do globo")
    void radiusEqualsBruteForce() {
        Map<String, double[]> data = seed(150, 1);
        Random rnd = new Random(2);
        List<double[]> centers = new ArrayList<>(GeoSamples.hardCenters());
        for (int i = 0; i < 40; i++) {
            centers.add(GeoSamples.uniform(rnd));
        }
        for (double[] center : centers) {
            for (double r : new double[] {1, 250, 4_000, 60_000, 1_500_000, 15_000_000, 3e7}) {
                TreeSet<String> expected = new TreeSet<>();
                data.forEach((id, p) -> {
                    if (meters(p, center) <= r) {
                        expected.add(id);
                    }
                });
                TreeSet<String> got = radius(center, r);
                assertTrue(got.containsAll(expected), "faltou ponto: centro " + center[0] + "," + center[1] + " r " + r);
                // a folga do corte é de uma parte em um milhão mais um milímetro
                got.removeAll(expected);
                got.forEach(id -> assertTrue(meters(data.get(id), center) <= r * 1.000001 + 1e-3, id));
            }
        }
    }

    @Test
    @DisplayName("raio para quando o visitante pede")
    void radiusStops() {
        seed(100, 3);
        int[] seen = {0};
        index.radius(kv, new double[] {0, 0}, 3e7, id -> ++seen[0] < 7);
        assertEquals(7, seen[0]);
    }

    @Test
    @DisplayName("centro inválido não é atendido pelo índice")
    void invalidCenterIsRefused() {
        assertFalse(index.radius(kv, new double[] {0, 91}, 10, id -> true));
        assertFalse(index.radius(kv, new double[] {Double.NaN, 0}, 10, id -> true));
        assertFalse(index.radius(kv, new double[] {0, 0}, Double.NaN, id -> true));
    }

    @Test
    @DisplayName("mais próximos saem na mesma ordem da força bruta, com empates pela chave")
    void nearestEqualsBruteForce() {
        Map<String, double[]> data = seed(120, 4);
        double[] twin = {-46.6333, -23.5505};
        for (int i = 0; i < 150; i++) {
            String id = String.format("szz%03d", i);
            data.put(id, twin);
            index.update(kv, id, point(twin));
        }
        Random rnd = new Random(5);
        List<double[]> centers = new ArrayList<>(GeoSamples.hardCenters());
        centers.add(twin);
        for (int i = 0; i < 30; i++) {
            centers.add(GeoSamples.uniform(rnd));
        }
        for (double[] center : centers) {
            List<String> expected = data.keySet().stream()
                .sorted(Comparator.<String>comparingDouble(id -> meters(data.get(id), center))
                    .thenComparing(Comparator.naturalOrder()))
                .limit(60).toList();
            List<String> got = new ArrayList<>();
            index.nearest(kv, center, (id, d, at) -> {
                assertEquals(meters(data.get(id), center), d, 0);
                got.add(id);
                return got.size() < 60;
            });
            assertEquals(expected, got, center[0] + "," + center[1]);
        }
    }

    @Test
    @DisplayName("mais próximos percorrem o índice inteiro quando o visitante não para")
    void nearestVisitsEverything() {
        Map<String, double[]> data = seed(20, 6);
        List<String> got = new ArrayList<>();
        index.nearest(kv, new double[] {10, 10}, (id, d, at) -> got.add(id) || true);
        assertEquals(data.size(), got.size());
    }

    @Test
    @DisplayName("caixa devolve todo ponto de dentro, inclusive além do meridiano de 180 graus")
    void boxContainsEverything() {
        Map<String, double[]> data = seed(150, 7);
        List<double[]> boxes = List.of(new double[] {-46.7, -23.6, -46.6, -23.5}, new double[] {170, -30, 180, 70},
            new double[] {-180, -30, -170, 70}, new double[] {-180, 80, 180, 90}, new double[] {-1, -1, 1, 1},
            new double[] {-180, -90, 180, 90});
        for (double[] box : boxes) {
            TreeSet<String> got = new TreeSet<>();
            index.box(kv, box, id -> got.add(id) || true);
            data.forEach((id, p) -> {
                if (p[0] >= box[0] && p[0] <= box[2] && p[1] >= box[1] && p[1] <= box[3]) {
                    assertTrue(got.contains(id), "faltou " + id + " na caixa " + box[0] + "," + box[1]);
                }
            });
        }
    }

    @Test
    @DisplayName("mover e apagar não deixam entradas antigas")
    void updateAndRemove() {
        index.update(kv, "sa", point(new double[] {10, 10}));
        index.update(kv, "sa", point(new double[] {-60, -20}));
        assertEquals(2, kv.keysWithPrefix("").size(), "uma entrada e uma chave reversa");
        assertTrue(radius(new double[] {10, 10}, 100).isEmpty());
        assertEquals(new TreeSet<>(List.of("sa")), radius(new double[] {-60, -20}, 100));
        index.update(kv, "sa", null);
        assertTrue(kv.keysWithPrefix("").isEmpty());
        index.update(kv, "sa", AxonValue.str("sem coordenadas"));
        index.update(kv, "sa", line());
        assertTrue(kv.keysWithPrefix("").isEmpty());
    }

    @Test
    @DisplayName("geometria que não é ponto e latitude fora da faixa são sempre candidatas, e desligam o KNN")
    void unlocatedEntries() {
        index.update(kv, "sp", point(new double[] {0, 0}));
        assertFalse(index.hasUnlocated(kv));
        index.update(kv, "sline", line(new double[] {50, 50}, new double[] {51, 51}));
        assertTrue(index.hasUnlocated(kv));
        assertEquals(new TreeSet<>(List.of("sline", "sp")), radius(new double[] {0, 0}, 10));
        TreeSet<String> boxed = new TreeSet<>();
        index.box(kv, new double[] {-1, -1, 1, 1}, id -> boxed.add(id) || true);
        assertTrue(boxed.contains("sline") && boxed.contains("sp"));
        TreeSet<String> all = radius(new double[] {0, 0}, 3e7);
        assertEquals(2, all.size(), "o raio global não repete candidatos");
        index.update(kv, "sline", null);
        assertFalse(index.hasUnlocated(kv));

        // latitude 100: a conta de distância é a mesma do WHERE, então o filtro do índice concorda com ele
        double[] odd = {10, 100};
        index.update(kv, "sodd", point(odd));
        assertTrue(index.hasUnlocated(kv));
        double d = meters(odd, new double[] {0, 0});
        assertTrue(radius(new double[] {0, 0}, d + 1).contains("sodd"));
        assertFalse(radius(new double[] {0, 0}, d / 2).contains("sodd"));
    }

    @Test
    @DisplayName("o índice só está em dia com o marcador de pronto, o de construção antiga e sem entradas do geohash")
    void markers() {
        assertTrue(index.mustRebuild(kv), "índice sem marcador");
        index.markReady(kv);
        assertFalse(index.mustRebuild(kv));

        kv.delete(index.tripwireKey());
        assertTrue(index.mustRebuild(kv), "o binário antigo reconstruiu depois de um downgrade");
        index.markReady(kv);

        kv.put("SP|ns|db|t|i|6gyf4b|sa", new byte[0]);
        assertTrue(index.hasLegacyEntries(kv));
        assertTrue(index.mustRebuild(kv), "há escrita no formato antigo");
        assertFalse(new GeoIndex("ns", "db", "t", "i2").hasLegacyEntries(kv), "outro índice não é afetado");
    }

    @Test
    @DisplayName("apagar o índice remove entradas novas, antigas e marcadores, e só os dele")
    void purge() {
        GeoIndex other = new GeoIndex("ns", "db", "t", "i2");
        index.update(kv, "sa", point(new double[] {1, 1}));
        other.update(kv, "sa", point(new double[] {1, 1}));
        other.markReady(kv);
        index.markReady(kv);
        for (String legacy : List.of("SP|ns|db|t|i|6gyf4b|sa", "SPR|ns|db|t|i|sa", "SPV|ns|db|t|i", "SPX|ns|db|t|i")) {
            kv.put(legacy, new byte[] {1});
        }
        index.purge(kv, prefix -> kv.keysWithPrefix(prefix).forEach(kv::delete));
        assertTrue(kv.keysWithPrefix("").stream().allMatch(k -> k.contains("|i2")),
            kv.keysWithPrefix("").toString());
        assertFalse(other.mustRebuild(kv));
    }

    @Test
    @DisplayName("reconhece o dono de uma chave escrita no formato antigo")
    void legacyOwner() {
        assertEquals(List.of("ns", "db", "t", "i"), GeoIndex.legacyEntryOwner("SP|ns|db|t|i|6gyf4b|sa|x"));
        assertEquals(null, GeoIndex.legacyEntryOwner("SPR|ns|db|t|i|sa"));
        assertEquals(null, GeoIndex.legacyEntryOwner("SH|ns|db|t|i|012|sa"));
        assertEquals(null, GeoIndex.legacyEntryOwner("SP|curta"));
    }
}
