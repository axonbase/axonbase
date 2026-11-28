package com.axonbase.core.shard;

import com.axonbase.value.AxonValue;

import java.util.List;

/**
 * Restringe uma leitura aos slots que o shard possui, enquanto há uma movimentação: registros copiados pela metade no
 * destino, ou ainda não apagados na origem, não podem ser contados duas vezes.
 *
 * @param table  tabela lida
 * @param field  campo de shard dela
 * @param ranges slots que valem
 */
public record SlotFilter(String table, String field, List<SlotRange> ranges) {

    /** As últimas faixas lidas: a função é chamada uma vez por registro, quase sempre com o mesmo texto. */
    private record Parsed(String text, List<SlotRange> ranges) {
    }

    private static volatile Parsed last;

    public SlotFilter {
        ranges = List.copyOf(ranges);
    }

    /** A condição equivalente na AxonQL: {@code shard::owned(campo, "tabela", "campo", "faixas")}. */
    public String condition() {
        return "shard::owned(" + com.axonbase.value.AxonQlText.identifier(field) + ", " + com.axonbase.value.AxonQlText.string(table) + ", "
            + com.axonbase.value.AxonQlText.string(field) + ", " + com.axonbase.value.AxonQlText.string(SlotRange.format(ranges)) + ")";
    }

    /**
     * A função {@code shard::owned}: o registro com este valor de chave mora num dos slots das faixas. Um valor que não
     * serve de chave não mora em slot nenhum.
     */
    public static boolean owns(AxonValue value, String table, String field, String ranges) {
        int slot;
        try {
            slot = GatewayRules.slot(field, table, value);
        } catch (ShardException unusable) {
            return false;
        }
        Parsed cached = last;
        if (cached == null || !cached.text().equals(ranges)) {
            cached = new Parsed(ranges, SlotRange.parse(ranges));
            last = cached;
        }
        for (SlotRange range : cached.ranges()) {
            if (range.contains(slot)) {
                return true;
            }
        }
        return false;
    }
}
