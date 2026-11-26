package com.axonbase.core.catalog;

/**
 * Definição de partição por tempo de uma tabela. As faixas têm {@code intervalMillis} e são alinhadas ao instante
 * zero. {@code retentionMillis} e {@code coldAfterMillis} nulos significam "nunca".
 */
public record PartitionSpec(String field, long intervalMillis, Long retentionMillis, Long coldAfterMillis) {

    /** Começo da faixa que contém o instante, que precisa ser a partir de zero. */
    public long startOf(long epochMillis) {
        return Math.floorDiv(epochMillis, intervalMillis) * intervalMillis;
    }
}
