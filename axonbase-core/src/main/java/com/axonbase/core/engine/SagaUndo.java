package com.axonbase.core.engine;

import com.axonbase.common.Messages;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.Render;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonQlText;
import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * As sentenças que desfazem um passo de SAGA, montadas em estruturas do analisador. Nome de tabela, chave,
 * propriedade e valor entram como dados e nunca são lidos como texto de comando. Só um participante remoto recebe
 * texto ({@link #remoteText}), citado e conferido para ser exatamente a sentença montada.
 */
final class SagaUndo {

    private SagaUndo() {
    }

    /** As sentenças que repõem o estado anterior ao passo, na ordem; vazia quando não há o que desfazer. */
    static List<Statement> statements(Map<String, AxonValue> step) {
        String operation = text(step, "operation");
        String table = text(step, "table_name");
        AxonValue before = step.get("before");
        if ("RELATE".equalsIgnoreCase(operation)) {
            if (before == null || !before.isObject()) {
                throw refused(table);
            }
            return edge(table, before.asObject());
        }
        Expr.Literal key = storedKey(step);
        if (before == null || !before.isObject() || before.asObject().isEmpty()) {
            if (!"CREATE".equalsIgnoreCase(operation) || key == null) {
                return List.of();
            }
            return List.of(new Statement.Delete(recordId(table, key), null, Statement.ReturnSpec.AFTER));
        }
        if (key == null) {
            throw refused(table);
        }
        // o id é a própria chave do alvo: a escrita o mantém, ou o repõe quando recria o registro
        Map<String, AxonValue> content = new LinkedHashMap<>(before.asObject());
        content.remove("id");
        return List.of(new Statement.Update(Statement.UpdateMode.UPSERT, false, recordId(table, key),
            new Statement.Data.Content(new Expr.Literal(AxonValue.object(content))), null,
            Statement.ReturnSpec.AFTER));
    }

    /** Remove a aresta e, quando ela já existia, recria-a só com as propriedades anteriores. */
    private static List<Statement> edge(String kind, Map<String, AxonValue> edge) {
        Expr from = recordId(text(edge, "from_table"), literalKey(text(edge, "from_key")));
        Expr to = recordId(text(edge, "to_table"), literalKey(text(edge, "to_key")));
        Expr name = new Expr.Ident(kind);
        List<Statement> statements = new ArrayList<>();
        statements.add(new Statement.Unrelate(from, name, to));
        AxonValue existed = edge.get("existed");
        if (existed == null || !existed.isBool() || !existed.asBool()) {
            return statements;
        }
        List<Statement.Assignment> assignments = new ArrayList<>();
        AxonValue properties = edge.get("properties");
        if (properties != null && properties.isObject()) {
            for (Map.Entry<String, AxonValue> property : properties.asObject().entrySet()) {
                assignments.add(new Statement.Assignment(new Expr.Ident(property.getKey()),
                    new Expr.Literal(property.getValue())));
            }
        }
        statements.add(new Statement.Relate(from, name, to,
            assignments.isEmpty() ? null : new Statement.Data.SetClause(assignments), Statement.ReturnSpec.AFTER));
        return statements;
    }

    private static Expr.RecordId recordId(String table, Expr.Literal key) {
        return new Expr.RecordId(new Expr.Ident(table), key);
    }

    /**
     * A chave de um passo, ou {@code null} quando ele não tem uma. Passos gravados por versões anteriores só têm o
     * texto cru: o que parece número é número, como antes, e o resto é texto.
     */
    private static Expr.Literal storedKey(Map<String, AxonValue> step) {
        String literal = text(step, "record_literal");
        if (!literal.isEmpty()) {
            return literalKey(literal);
        }
        String raw = text(step, "record_key");
        if (raw.isEmpty()) {
            return null;
        }
        if (!raw.matches("-?\\d+(\\.\\d+)?")) {
            return new Expr.Literal(AxonValue.str(raw));
        }
        if (raw.indexOf('.') < 0 && raw.length() < 19) {
            return new Expr.Literal(AxonValue.num(Long.parseLong(raw)));
        }
        return new Expr.Literal(AxonValue.num(new BigDecimal(raw)));
    }

    /** Lê o literal gravado no passo: só vale um texto ou um número, sem outra sentença nem condição. */
    private static Expr.Literal literalKey(String literal) {
        List<Statement> parsed = AxonQl.parse("DELETE x:" + literal).statements();
        if (parsed.size() == 1 && parsed.get(0) instanceof Statement.Delete delete && delete.cond() == null
            && delete.target() instanceof Expr.RecordId target && target.table().equals(new Expr.Ident("x"))
            && target.key() instanceof Expr.Literal key && (key.value().isString() || key.value().isNumber())) {
            return key;
        }
        throw refused(literal);
    }

    /**
     * O texto de uma sentença para o participante remoto: nomes entre aspas, valores como literais. O texto é lido de
     * volta e só segue se for uma única sentença do tipo montado, sobre o mesmo alvo.
     */
    static String remoteText(Statement statement) {
        String text = switch (statement) {
            case Statement.Delete d -> "DELETE " + target(d.target());
            case Statement.Update u when u.data() instanceof Statement.Data.Content c
                && c.content() instanceof Expr.Literal content ->
                "UPSERT " + target(u.target()) + " CONTENT " + Render.value(content.value());
            case Statement.Unrelate u -> "UNRELATE " + path(u.from(), u.kind(), u.to());
            case Statement.Relate r -> "RELATE " + path(r.from(), r.kind(), r.to()) + set(r.data());
            default -> throw refused(statement.getClass().getSimpleName());
        };
        List<Statement> parsed = AxonQl.parse(text).statements();
        if (parsed.size() != 1 || !sameTarget(statement, parsed.get(0))) {
            throw refused(text);
        }
        return text;
    }

    /** A leitura que confere, no participante remoto, se o registro do DELETE ainda existe. */
    static String remoteProbe(Statement.Delete delete) {
        String text = "SELECT * FROM " + target(delete.target());
        List<Statement> parsed = AxonQl.parse(text).statements();
        if (parsed.size() != 1 || !(parsed.get(0) instanceof Statement.Select select) || select.cond() != null
            || !delete.target().equals(select.from())) {
            throw refused(text);
        }
        return text;
    }

    private static boolean sameTarget(Statement wanted, Statement parsed) {
        return switch (wanted) {
            case Statement.Delete d -> parsed instanceof Statement.Delete p && p.cond() == null
                && p.target().equals(d.target());
            case Statement.Update u -> parsed instanceof Statement.Update p && p.cond() == null
                && p.mode() == u.mode() && p.target().equals(u.target());
            case Statement.Unrelate u -> parsed.equals(u);
            case Statement.Relate r -> parsed instanceof Statement.Relate p && p.from().equals(r.from())
                && p.kind().equals(r.kind()) && p.to().equals(r.to()) && sameFields(r.data(), p.data());
            default -> false;
        };
    }

    private static boolean sameFields(Statement.Data wanted, Statement.Data parsed) {
        return fields(wanted).equals(fields(parsed));
    }

    private static List<Expr> fields(Statement.Data data) {
        return data instanceof Statement.Data.SetClause set
            ? set.assignments().stream().map(Statement.Assignment::path).toList() : List.of();
    }

    private static String target(Expr expr) {
        if (expr instanceof Expr.RecordId rid && rid.table() instanceof Expr.Ident table
            && rid.key() instanceof Expr.Literal key) {
            AxonValue value = key.value();
            return quoted(table.name()) + ":"
                + (value.isNumber() ? Render.numberKey(value) : Render.value(value));
        }
        throw refused(String.valueOf(expr));
    }

    private static String path(Expr from, Expr kind, Expr to) {
        if (!(kind instanceof Expr.Ident name)) {
            throw refused(String.valueOf(kind));
        }
        return target(from) + "->" + quoted(name.name()) + "->" + target(to);
    }

    private static String set(Statement.Data data) {
        if (!(data instanceof Statement.Data.SetClause set)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        String separator = " SET ";
        for (Statement.Assignment assignment : set.assignments()) {
            if (!(assignment.path() instanceof Expr.Ident field)
                || !(assignment.value() instanceof Expr.Literal value)) {
                throw refused(String.valueOf(assignment.path()));
            }
            text.append(separator).append(identifier(field.name())).append(" = ")
                .append(Render.value(value.value()));
            separator = ", ";
        }
        return text.toString();
    }

    /** As tabelas e o tipo de aresta que a sentença cita. */
    static List<String> tableNames(Statement statement) {
        return switch (statement) {
            case Statement.Delete d -> List.of(tableName(d.target()));
            case Statement.Update u -> List.of(tableName(u.target()));
            case Statement.Unrelate u -> List.of(tableName(u.from()), kindName(u.kind()), tableName(u.to()));
            case Statement.Relate r -> List.of(tableName(r.from()), kindName(r.kind()), tableName(r.to()));
            default -> throw refused(statement.getClass().getSimpleName());
        };
    }

    private static String tableName(Expr expr) {
        if (expr instanceof Expr.RecordId rid && rid.table() instanceof Expr.Ident table) {
            return table.name();
        }
        throw refused(String.valueOf(expr));
    }

    private static String kindName(Expr kind) {
        if (kind instanceof Expr.Ident name) {
            return name.name();
        }
        throw refused(String.valueOf(kind));
    }

    private static String quoted(String name) {
        return AxonQlText.string(name);
    }

    /** Nome de propriedade sempre citado, para uma palavra-chave não mudar o sentido do comando. */
    private static String identifier(String name) {
        try {
            return AxonQlText.quotedIdentifier(name);
        } catch (IllegalArgumentException e) {
            throw refused(name);
        }
    }

    private static String text(Map<String, AxonValue> map, String key) {
        AxonValue value = map.get(key);
        return value != null && value.isString() ? value.asString() : "";
    }

    private static IllegalStateException refused(String detail) {
        return new IllegalStateException(Messages.get("saga_compensation_refused", detail));
    }
}
