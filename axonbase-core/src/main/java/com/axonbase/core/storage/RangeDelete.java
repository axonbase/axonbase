package com.axonbase.core.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remoção por faixa codificada dentro de um lote comum: um {@code put} numa chave reservada cujo nome traz o começo
 * da faixa e cujo valor traz o fim (exclusivo).
 *
 * <p>Assim a remoção viaja, sem mudar formato algum, por todo caminho que já leva lotes (replicação, journal da
 * restauração a um instante, backup lógico, cópia para um nó novo) e leva o carimbo do lote. Quem grava é que a
 * interpreta: antes de aplicar os demais {@code put} e {@code delete} do mesmo lote, apaga a faixa. A marca em si
 * continua gravada como uma chave qualquer.</p>
 */
public final class RangeDelete {

    /** Começo de toda marca. Não tem o separador NUL das chaves de registro. */
    public static final String PREFIX = "!rd/";

    /** Faixa {@code [from, to)}. */
    public record Range(String from, String to) {
        public Range {
            if (from.isEmpty() || from.compareTo(to) >= 0) {
                throw new IllegalArgumentException("faixa de remoção vazia ou invertida");
            }
        }

        public boolean contains(String key) {
            return key.compareTo(from) >= 0 && key.compareTo(to) < 0;
        }
    }

    private RangeDelete() {
    }

    public static boolean isMarker(String key) {
        return key.startsWith(PREFIX);
    }

    /** Chave da marca que apaga {@code range}. */
    public static String key(Range range) {
        return PREFIX + HexFormat.of().formatHex(range.from().getBytes(StandardCharsets.UTF_8));
    }

    /** Valor da marca que apaga {@code range}. */
    public static byte[] value(Range range) {
        return range.to().getBytes(StandardCharsets.UTF_8);
    }

    /** A faixa de todas as chaves que começam com {@code prefix}, cujo último caractere precisa ser ASCII. */
    public static Range ofPrefix(String prefix) {
        int last = prefix.length() - 1;
        char next = (char) (prefix.charAt(last) + 1);
        return new Range(prefix, prefix.substring(0, last) + next);
    }

    /** A faixa descrita pela marca. */
    public static Range parse(String key, byte[] value) {
        String from = new String(HexFormat.of().parseHex(key.substring(PREFIX.length())), StandardCharsets.UTF_8);
        return new Range(from, new String(value, StandardCharsets.UTF_8));
    }

    /** As faixas pedidas por um lote; vazio no caso comum, sem alocar. */
    public static List<Range> in(Map<String, byte[]> puts) {
        List<Range> ranges = null;
        for (var entry : puts.entrySet()) {
            if (isMarker(entry.getKey())) {
                if (ranges == null) {
                    ranges = new ArrayList<>(1);
                }
                ranges.add(parse(entry.getKey(), entry.getValue()));
            }
        }
        return ranges == null ? List.of() : ranges;
    }

    /** Para backends sem remoção por faixa: acrescenta a {@code deletes} as chaves existentes dentro das faixas. */
    static void expand(List<Range> ranges, Iterable<String> existing, Map<String, byte[]> puts, Set<String> deletes) {
        for (String key : existing) {
            if (!puts.containsKey(key) && ranges.stream().anyMatch(r -> r.contains(key))) {
                deletes.add(key);
            }
        }
    }
}
