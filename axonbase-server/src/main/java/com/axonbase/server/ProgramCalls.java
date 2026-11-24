package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Chamada de função, procedure ou package AxonPL pelos canais restritos (MCP e GraphQL), que não aceitam AxonQL
 * arbitrário. O nome é validado e os argumentos vão como parâmetros ligados, nunca concatenados no texto.
 * As permissões são as da sessão (viewer não chama procedure).
 */
final class ProgramCalls {

    private static final Pattern NAME = Pattern.compile("(fn::)?[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)?");

    private ProgramCalls() {
    }

    static AxonValue call(Datastore ds, Session session, String name, List<AxonValue> args) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("nome de funcao invalido: " + name);
        }
        Map<String, AxonValue> vars = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("CALL ").append(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            sql.append(i > 0 ? ", " : "").append("$a").append(i);
            vars.put("a" + i, args.get(i));
        }
        return ds.execute(sql.append(')').toString(), session, vars);
    }
}
