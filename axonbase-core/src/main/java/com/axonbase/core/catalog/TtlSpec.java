package com.axonbase.core.catalog;

/** Prazo de validade das linhas de uma tabela: cada uma vence em {@code valor do campo + afterMillis}. */
public record TtlSpec(String field, long afterMillis) {
}
