package com.axonbase.parser.ast;

import java.util.List;

/** Uma consulta AxonQL é uma sequência de sentenças. */
public record Query(List<Statement> statements) {

    public static final Query EMPTY = new Query(List.of());
}