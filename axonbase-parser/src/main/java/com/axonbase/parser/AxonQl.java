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

    /** Renderiza un {@link Query} de volta ao texto canónico da AxonQL. */
    public static String render(Query q) {
        return Render.query(q);
    }
}