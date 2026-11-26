package com.axonbase.core.engine;

import com.axonbase.core.storage.KvBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Um índice GEO sobre células H3 no armazenamento chave-valor.
 *
 * <pre>
 * SH |ns|db|tabela|índice|caminho|chave   entrada; o valor traz o ponto (ver {@link GeoEntry})
 * SHR|ns|db|tabela|índice|chave           caminhos gravados para o registro, separados por vírgula
 * SHV|ns|db|tabela|índice                 versão do formato: o índice foi construído inteiro
 * IXB|ns|db|tabela|índice                 marcador de construção do binário antigo (ver {@link #tripwireKey})
 * </pre>
 *
 * <p>Pontos válidos ficam sob o caminho da célula de resolução {@link H3Cells#STORAGE_RESOLUTION}. Geometrias que
 * não são ponto (linhas, polígonos) e pontos com latitude fora de -90..90 ficam sob {@value #UNLOCATED}, que toda
 * consulta lê: o filtro exato do {@code WHERE} decide, e o índice nunca esconde uma linha que a varredura acharia.</p>
 */
final class GeoIndex {

    private static final String UNLOCATED = "x";
    private static final byte[] FORMAT = {1, H3Cells.STORAGE_RESOLUTION};
    private static final byte[] TRIPWIRE = {1};
    private static final String LEGACY_ENTRIES = "SP|";

    /** Entradas lidas de uma célula antes de valer a pena descer para os filhos na busca dos mais próximos. */
    private static final int NEAREST_LEAF = 64;

    private static final double EARTH_HALF_CIRCUMFERENCE = Math.PI * 6_371_008.8;

    private final String scope;

    GeoIndex(String ns, String db, String table, String index) {
        this.scope = ns + "|" + db + "|" + table + "|" + index;
    }

    /** Recebe os candidatos dos mais próximos em ordem de distância; {@code false} encerra a busca. */
    interface Nearest {
        boolean visit(String id, double meters, double[] at);
    }

    // ------------------------------------------------------------------ geometrias

    /** {@code {lon, lat}} de uma geometria ponto, ou {@code null}. Mesma regra de {@code geo::distance}. */
    static double[] pointOf(AxonValue geometry) {
        return GeoVector.pointOf(geometry);
    }

    /** O ponto pode ser centro de uma busca ou ir para uma célula. */
    static boolean isLocatable(double[] point) {
        return point != null && Double.isFinite(point[0]) && Math.abs(point[1]) <= 90;
    }

    /** O campo do documento é um ponto. */
    static boolean hasPoint(AxonValue document, String column) {
        return document != null && document.isObject() && pointOf(document.asObject().get(column)) != null;
    }

    /** O campo do documento é exatamente o ponto que a entrada do índice registrou. */
    static boolean isAt(AxonValue document, String column, double[] at) {
        if (document == null || !document.isObject()) {
            return false;
        }
        double[] now = pointOf(document.asObject().get(column));
        return now != null && at != null && Double.compare(now[0], at[0]) == 0 && Double.compare(now[1], at[1]) == 0;
    }

    private static boolean hasCoordinatePair(AxonValue coordinates) {
        if (coordinates == null || !coordinates.isArray() || coordinates.asArray().isEmpty()) {
            return false;
        }
        List<AxonValue> items = coordinates.asArray();
        if (items.get(0).isNumber()) {
            return items.size() >= 2 && items.get(1).isNumber();
        }
        return items.stream().anyMatch(GeoIndex::hasCoordinatePair);
    }

    /**
     * A geometria como a leitura a verá depois de gravada. Os envelopes de valor tipado do JSON
     * ({@code {"$decimal": "..."}}, que o dump de exportação escreve) chegam à escrita como objetos e voltam do
     * armazenamento como números: o índice tem de enxergar o mesmo que a varredura.
     */
    private static AxonValue asRead(AxonValue geometry) {
        if (geometry == null || !geometry.isObject() || pointOf(geometry) != null) {
            return geometry;
        }
        return AxonJson.decode(AxonJson.encode(geometry));
    }

    /** Caminhos em que a geometria é gravada: um para ponto válido, o balde sem lugar para as demais. */
    private static Set<String> pathsOf(AxonValue geometry, double[] point) {
        Set<String> out = new LinkedHashSet<>();
        if (isLocatable(point)) {
            out.add(H3Cells.pointPath(point[0], point[1]));
        } else if (geometry != null && geometry.isObject()
            && hasCoordinatePair(geometry.asObject().get("coordinates"))) {
            out.add(UNLOCATED);
        }
        return out;
    }

    // ------------------------------------------------------------------ chaves

    private String entryBase() {
        return "SH|" + scope + "|";
    }

    private String reverseKey(String id) {
        return "SHR|" + scope + "|" + id;
    }

    private String formatKey() {
        return "SHV|" + scope;
    }

    private String legacyBase() {
        return LEGACY_ENTRIES + scope + "|";
    }

    /**
     * Chave do marcador de construção que o binário 0.10 usa. O índice H3 a mantém mesmo depois de pronto: ao voltar
     * para o binário antigo (downgrade), ele a entende como construção interrompida e refaz o índice geohash, em vez
     * de responder vazio com um índice que nunca escreveu. Se o marcador sumir, o binário antigo reconstruiu o dele,
     * e o índice H3 precisa ser refeito.
     */
    String tripwireKey() {
        return "IXB|" + scope;
    }

    // ------------------------------------------------------------------ escrita

    /** Grava, move ou remove ({@code geometry} sem coordenadas) a entrada do registro. */
    void update(KvBackend kv, String id, AxonValue value) {
        AxonValue geometry = asRead(value);
        String reverse = reverseKey(id);
        double[] point = pointOf(geometry);
        Set<String> paths = pathsOf(geometry, point);
        Optional<byte[]> old = kv.get(reverse);
        if (old.isPresent()) {
            for (String path : new String(old.get(), StandardCharsets.UTF_8).split(",")) {
                if (!path.isEmpty() && !paths.contains(path)) {
                    kv.delete(entryBase() + path + "|" + id);
                }
            }
        }
        if (paths.isEmpty()) {
            if (old.isPresent()) {
                kv.delete(reverse);
            }
            return;
        }
        byte[] entry = GeoEntry.encode(point);
        for (String path : paths) {
            kv.put(entryBase() + path + "|" + id, entry);
        }
        byte[] list = String.join(",", paths).getBytes(StandardCharsets.UTF_8);
        if (old.isEmpty() || !Arrays.equals(old.get(), list)) {
            kv.put(reverse, list);
        }
    }

    // ------------------------------------------------------------------ marcadores

    /** O índice precisa ser (re)construído antes de atender consultas. */
    boolean mustRebuild(KvBackend kv) {
        return !kv.get(formatKey()).map(v -> Arrays.equals(v, FORMAT)).orElse(false)
            || kv.get(tripwireKey()).isEmpty()
            || hasLegacyEntries(kv);
    }

    /** Há entradas do índice geohash: um binário antigo escreveu, e as células H3 podem estar atrasadas. */
    boolean hasLegacyEntries(KvBackend kv) {
        return hasAny(kv, legacyBase());
    }

    void markReady(KvBackend kv) {
        kv.put(formatKey(), FORMAT.clone());
        kv.put(tripwireKey(), TRIPWIRE.clone());
    }

    /**
     * Declara o índice atrasado antes de qualquer outra coisa: tira a versão (este binário o refaz, mesmo depois de
     * uma queda) e grava o marcador (o binário antigo também).
     */
    void markStale(KvBackend kv) {
        kv.delete(formatKey());
        kv.put(tripwireKey(), TRIPWIRE.clone());
    }

    /**
     * Apaga as entradas, do formato atual e do geohash. O marcador {@link #tripwireKey} fica: quem remove o índice do
     * catálogo o apaga; uma reconstrução interrompida precisa dele.
     */
    void purge(KvBackend kv, Consumer<String> deletePrefix) {
        kv.delete(formatKey());
        deletePrefix.accept(entryBase());
        deletePrefix.accept("SHR|" + scope + "|");
        purgeLegacy(kv, deletePrefix);
    }

    /** Apaga o que o índice geohash gravou. Nenhuma escrita deste binário toca nessas chaves. */
    void purgeLegacy(KvBackend kv, Consumer<String> deletePrefix) {
        kv.delete("SPV|" + scope);
        kv.delete("SPX|" + scope);
        deletePrefix.accept(legacyBase());
        deletePrefix.accept("SPR|" + scope + "|");
    }

    /** {@code [ns, db, tabela, índice]} de uma entrada do formato antigo, ou {@code null} para outra chave. */
    static List<String> legacyEntryOwner(String key) {
        if (!key.startsWith(LEGACY_ENTRIES)) {
            return null;
        }
        String[] parts = key.split("\\|", 6);
        return parts.length < 6 ? null : List.of(parts[1], parts[2], parts[3], parts[4]);
    }

    private static boolean hasAny(KvBackend kv, String prefix) {
        boolean[] found = {false};
        kv.scanPrefix(prefix, key -> {
            found[0] = true;
            return false;
        });
        return found[0];
    }

    /** Há geometria sem célula (linha, polígono, latitude inválida): a busca dos mais próximos não serve. */
    boolean hasUnlocated(KvBackend kv) {
        return hasAny(kv, entryBase() + UNLOCATED + "|");
    }

    // ------------------------------------------------------------------ consultas

    /** Chave do registro dentro de uma chave de entrada: o caminho tem tamanho fixo, a chave pode ter qualquer coisa. */
    private static String idOf(String key, int baseLength) {
        int pathLength = key.charAt(baseLength) == UNLOCATED.charAt(0) ? UNLOCATED.length() : H3Cells.PATH_LENGTH;
        return key.substring(baseLength + pathLength + 1);
    }

    /**
     * Candidatos a {@code geo::distance(campo, centro) <= metros}: todo ponto dentro do raio (com a folga do corte) e
     * toda geometria sem lugar, cada registro uma vez, das células mais próximas para as mais distantes.
     *
     * @return {@code false} quando o centro ou o raio não servem para o índice (o chamador varre a tabela)
     */
    boolean radius(KvBackend kv, double[] center, double meters, Predicate<String> ids) {
        if (!isLocatable(center) || Double.isNaN(meters)) {
            return false;
        }
        double limit = meters * 1.000001 + 1e-3;
        List<String> paths = new ArrayList<>();
        if (limit >= EARTH_HALF_CIRCUMFERENCE) {
            paths.add("");
        } else {
            H3Cover.cover(H3Cover.circle(center[0], center[1], limit), H3Cover.MAX_CELLS).stream()
                .sorted(Comparator.comparingDouble(H3Cover.Cell::lowerBound))
                .forEach(cell -> paths.add(cell.path()));
            paths.add(UNLOCATED + "|");
        }
        scan(kv, paths, (id, at) -> at != null
            && GeoVector.haversine(at[0], at[1], center[0], center[1]) > limit, ids);
        return true;
    }

    /** Candidatos dentro da caixa {@code {oeste, sul, leste, norte}} (superconjunto) e as geometrias sem lugar. */
    void box(KvBackend kv, double[] box, Predicate<String> ids) {
        List<String> paths = new ArrayList<>();
        H3Cover.cover(H3Cover.box(box[0], box[1], box[2], box[3]), H3Cover.MAX_CELLS)
            .forEach(cell -> paths.add(cell.path()));
        paths.add(UNLOCATED + "|");
        scan(kv, paths, (id, at) -> false, ids);
    }

    private interface Skip {
        boolean test(String id, double[] at);
    }

    private void scan(KvBackend kv, List<String> paths, Skip skip, Predicate<String> ids) {
        String base = entryBase();
        Set<String> seen = new java.util.HashSet<>();
        boolean[] stop = {false};
        for (String path : paths) {
            if (stop[0]) {
                return;
            }
            QueryContext.checkCancelled();
            kv.scanEntries(base + path, (key, value) -> {
                String id = idOf(key, base.length());
                if (!skip.test(id, GeoEntry.decode(value)) && seen.add(id)) {
                    stop[0] = !ids.test(id);
                }
                return !stop[0];
            });
        }
    }

    private record Node(long cell, double lowerBound) {
    }

    private record Candidate(String id, double meters, double[] at) {
    }

    /**
     * Pontos em ordem crescente de distância (empate pela chave), por busca do melhor primeiro na hierarquia H3: só
     * desce para uma célula quando ela ainda pode ter um ponto mais perto que o melhor candidato em mãos, então o
     * custo acompanha o número de resultados pedidos, não o tamanho do índice. Ignora geometrias sem lugar.
     */
    void nearest(KvBackend kv, double[] center, Nearest visitor) {
        PriorityQueue<Node> cells = new PriorityQueue<>(Comparator.comparingDouble(Node::lowerBound));
        PriorityQueue<Candidate> found = new PriorityQueue<>(
            Comparator.comparingDouble(Candidate::meters).thenComparing(Candidate::id));
        for (long base : H3Cells.baseCells()) {
            cells.add(node(base, 0, center));
        }
        while (!cells.isEmpty() || !found.isEmpty()) {
            if (!cells.isEmpty() && (found.isEmpty() || cells.peek().lowerBound() <= found.peek().meters())) {
                expand(kv, center, cells.poll().cell(), cells, found);
            } else {
                Candidate best = found.poll();
                if (!visitor.visit(best.id(), best.meters(), best.at())) {
                    return;
                }
            }
        }
    }

    private static Node node(long cell, int resolution, double[] center) {
        double[] at = H3Cells.center(cell);
        double d = GeoVector.haversine(center[0], center[1], at[0], at[1]) - H3Cells.reachMeters(resolution);
        return new Node(cell, d > 0 ? d : 0);
    }

    /** Célula com poucas entradas vira candidatos; célula cheia vira os filhos. */
    private void expand(KvBackend kv, double[] center, long cell, PriorityQueue<Node> cells,
                        PriorityQueue<Candidate> found) {
        QueryContext.checkCancelled();
        int resolution = H3Cells.resolution(cell);
        boolean leaf = resolution == H3Cells.STORAGE_RESOLUTION;
        String base = entryBase();
        List<Candidate> here = new ArrayList<>();
        boolean[] crowded = {false};
        kv.scanEntries(base + H3Cells.path(cell), (key, value) -> {
            double[] at = GeoEntry.decode(value);
            if (at == null) {
                return true;
            }
            if (!leaf && here.size() >= NEAREST_LEAF) {
                crowded[0] = true;
                return false;
            }
            here.add(new Candidate(idOf(key, base.length()),
                GeoVector.haversine(at[0], at[1], center[0], center[1]), at));
            return true;
        });
        if (!crowded[0]) {
            found.addAll(here);
            return;
        }
        for (long child : H3Cells.children(cell)) {
            cells.add(node(child, resolution + 1, center));
        }
    }
}
