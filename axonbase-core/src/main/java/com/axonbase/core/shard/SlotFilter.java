package com.axonbase.core.shard;

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

    public SlotFilter {
        ranges = List.copyOf(ranges);
    }
}
