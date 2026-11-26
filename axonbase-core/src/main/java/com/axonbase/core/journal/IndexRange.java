package com.axonbase.core.journal;

/** Faixa de índices de lote, com as duas pontas incluídas. */
public record IndexRange(long from, long to) {
}
