package com.axonbase.parser;

import com.axonbase.parser.ast.Query;

/** Punto de entrada da AxonQL: parsear e renderizar consultas. */
public final class AxonQl {

    private AxonQl() {
    }

    /** Converte umha string de AxonQL nun {@link Query}. */
    public static Query parse(String sql) {
        return Parser.parse(sql);
    }

    /**
     * Confere que um texto montado é exatamente uma sentença do tipo esperado e o devolve. Todo código que monta AxonQL
     * com dado de fora deve passar o resultado por aqui antes de executar ou encaminhar; a conferência não dispensa
     * citar nomes e valores com {@link com.axonbase.value.AxonQlText}. O analisador não exige {@code ;} entre
     * sentenças, por isso a contagem é a das sentenças analisadas.
     *
     * @throws IllegalArgumentException nenhuma ou mais de uma sentença, ou sentença de outro tipo
     */
    public static String requireSingle(String sql, Class<? extends com.axonbase.parser.ast.Statement> expected) {
        var statements = Parser.parse(sql).statements();
        if (statements.size() != 1 || !expected.isInstance(statements.get(0))) {
            throw new IllegalArgumentException("o texto montado precisa ser uma única sentença "
                + expected.getSimpleName());
        }
        return sql;
    }

    /** Renderiza un {@link Query} de volta ao texto canónico da AxonQL. */
    public static String render(Query q) {
        return Render.query(q);
    }
}