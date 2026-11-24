package com.axonbase.core.pl;

import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.List;

/** Consultas db.* disparadas por uma execução com rastro (base da futura "query timeline"). */
final class QueryProfile {

    /** Uma consulta: SQL, duração, quantidade de linhas devolvidas e linha do programa que a disparou. */
    record Query(String sql, long nanos, long rows, int line) {
    }

    private static final int MAX = 2000;

    private final List<Query> queries = new ArrayList<>();
    private volatile int currentLine;

    /** Linha (do corpo do usuário) do último comando que chamou o host. */
    void currentLine(int line) {
        this.currentLine = line;
    }

    synchronized void registrar(String sql, long nanos, long rows) {
        if (queries.size() < MAX) {
            queries.add(new Query(sql, nanos, rows, currentLine));
        }
    }

    synchronized List<Query> queries() {
        return List.copyOf(queries);
    }

    /** Quantidade de linhas de um resultado: tamanho do array, 0 para nulo, 1 para os demais. */
    static long rows(AxonValue r) {
        if (r == null || r.isNull() || r.isNone()) {
            return 0;
        }
        return r.isArray() ? r.asArray().size() : 1;
    }
}
