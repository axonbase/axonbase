package com.axonbase.core.shard;

import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Procura, em qualquer ponto de uma árvore, o que atravessa tabelas e portanto pode precisar de dados de outro shard:
 * subconsultas, travessia de grafo, busca textual e chamadas de programa. A árvore é percorrida pelos componentes dos
 * records, então um nó novo na linguagem é visitado sem mudar nada aqui.
 */
final class StatementScan {

    private final Set<String> subqueryTables = new LinkedHashSet<>();
    private boolean subquery;
    private boolean opaqueSubquery;
    private boolean graph;
    private boolean match;
    private boolean program;

    private StatementScan() {
    }

    static StatementScan of(Object node) {
        StatementScan scan = new StatementScan();
        scan.walk(node, false);
        return scan;
    }

    /** Há subconsulta em algum lugar. */
    boolean subquery() {
        return subquery;
    }

    /** Há subconsulta cuja origem não é o nome de uma tabela, então não se sabe o que ela lê. */
    boolean opaqueSubquery() {
        return opaqueSubquery;
    }

    /** As tabelas lidas pelas subconsultas. */
    Set<String> subqueryTables() {
        return subqueryTables;
    }

    boolean graph() {
        return graph;
    }

    boolean match() {
        return match;
    }

    boolean program() {
        return program;
    }

    /** A tabela de uma origem simples ({@code t} ou {@code t:chave}). */
    static Optional<String> tableOf(Expr source) {
        if (source instanceof Expr.Ident ident) {
            return Optional.of(ident.name());
        }
        if (source instanceof Expr.RecordId id && id.table() instanceof Expr.Ident table) {
            return Optional.of(table.name());
        }
        return Optional.empty();
    }

    private void walk(Object node, boolean inSubquery) {
        if (node instanceof List<?> list) {
            list.forEach(item -> walk(item, inSubquery));
            return;
        }
        if (!(node instanceof Record record)) {
            return;
        }
        boolean nested = inSubquery;
        if (node instanceof Expr.SubQuery) {
            subquery = true;
            nested = true;
        } else if (node instanceof Statement.Select select && inSubquery) {
            source(select.from());
            select.joins().forEach(join -> source(join.source()));
        } else if (node instanceof Expr.Idiom idiom
            && idiom.parts().stream().anyMatch(part -> part instanceof Expr.Part.Graph)) {
            graph = true;
        } else if (node instanceof Expr.Binary binary && binary.op() == Expr.BinaryOp.MATCH) {
            match = true;
        } else if (node instanceof Expr.Call call && call.name().startsWith("fn::")) {
            program = true;
        } else if (node instanceof Statement.CallProgram) {
            program = true;
        }
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                walk(component.getAccessor().invoke(record), nested);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("não foi possível percorrer " + record.getClass(), e);
            }
        }
    }

    private void source(Expr from) {
        if (from == null) {
            return;
        }
        tableOf(from).ifPresentOrElse(subqueryTables::add, () -> {
            if (!(from instanceof Expr.SubQuery)) {
                opaqueSubquery = true;
            }
        });
    }
}
