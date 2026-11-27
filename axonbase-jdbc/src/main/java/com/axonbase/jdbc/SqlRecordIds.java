package com.axonbase.jdbc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ponte entre o {@code id} do SQL, que é só a chave, e o do AxonBase, que é o record id {@code tabela:chave}.
 * Na ida reescreve filtros e ordenação; na volta diz quais colunas trazem o identificador.
 */
final class SqlRecordIds {

    private static final Pattern TOKEN = Pattern.compile(
        "'(?:\\\\.|''|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"|[A-Za-z_]\\w*|\\d+(?:\\.\\d+)?|<>|!=|<=|>=|\\s+|.",
        Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile(
        "(?:\\w+\\.)?(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?", Pattern.CASE_INSENSITIVE);

    /** Colunas de um SELECT, na ordem escrita, e as que correspondem ao {@code id}. */
    record Shape(String table, List<String> labels, Set<String> idLabels) {
    }

    private SqlRecordIds() {
    }

    /** As colunas do SELECT de uma tabela só com nomes simples; {@code null} para as demais formas. */
    static Shape shape(String sql) {
        Matcher select = SqlTranslator.SELECT.matcher(sql.strip());
        if (!select.matches()) return null;
        String table = select.group(2).trim().split("\\s+")[0];
        List<String> labels = new ArrayList<>();
        Set<String> idLabels = new LinkedHashSet<>();
        for (String item : select.group(1).split(",")) {
            Matcher column = COLUMN.matcher(item.trim());
            // "VALUE nome" não é uma coluna com apelido
            if (!column.matches() || column.group(1).equalsIgnoreCase("value") && column.group(2) != null) {
                return null;
            }
            String label = column.group(2) != null ? column.group(2) : column.group(1);
            labels.add(label);
            if (column.group(1).equalsIgnoreCase("id")) idLabels.add(label);
        }
        return new Shape(table, List.copyOf(labels), Set.copyOf(idLabels));
    }

    /** Tira o apelido da tabela e compara {@code id} com record id em WHERE e ORDER BY. */
    static String rewrite(String sql) {
        String text = sql.strip();
        Matcher select = SqlTranslator.SELECT.matcher(text);
        if (select.matches()) {
            String[] from = select.group(2).trim().split("\\s+");
            String alias = from.length > 1 ? from[1] : null;
            StringBuilder out = new StringBuilder(text);
            // do fim para o começo, para as posições anteriores continuarem valendo
            if (select.group(4) != null) {
                out.replace(select.start(4), select.end(4), clause(select.group(4), from[0], alias));
            }
            if (select.group(3) != null) {
                out.replace(select.start(3), select.end(3), clause(select.group(3), from[0], alias));
            }
            return out.toString();
        }
        for (Pattern pattern : List.of(SqlTranslator.UPDATE, SqlTranslator.DELETE)) {
            Matcher write = pattern.matcher(text);
            int where = pattern == SqlTranslator.UPDATE ? 3 : 2;
            if (write.matches() && write.group(where) != null) {
                return new StringBuilder(text)
                    .replace(write.start(where), write.end(where), clause(write.group(where), write.group(1), null))
                    .toString();
            }
        }
        return sql;
    }

    private static String clause(String clause, String table, String alias) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(clause);
        while (matcher.find()) tokens.add(matcher.group());
        for (int i = 0; i + 2 < tokens.size(); i++) {
            boolean qualifier = tokens.get(i).equalsIgnoreCase(table) || tokens.get(i).equalsIgnoreCase(alias);
            if (qualifier && tokens.get(i + 1).equals(".") && (i == 0 || !tokens.get(i - 1).equals("."))) {
                tokens.remove(i + 1);
                tokens.remove(i);
            }
        }
        // numa subconsulta o id é de outra tabela
        if (tokens.stream().anyMatch(t -> t.equalsIgnoreCase("select"))) {
            return String.join("", tokens);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < tokens.size()) {
            boolean isId = tokens.get(i).equalsIgnoreCase("id")
                && (i == 0 || !(tokens.get(i - 1).equals(".") || tokens.get(i - 1).equals(":")));
            int next = isId ? comparison(tokens, i, table, out) : i;
            if (next == i) {
                out.append(tokens.get(i));
                next = i + 1;
            }
            i = next;
        }
        return out.toString();
    }

    /** Reescreve a comparação que começa no {@code id}; devolve o próximo token, ou {@code id} se não mexeu. */
    private static int comparison(List<String> tokens, int id, String table, StringBuilder out) {
        int op = significant(tokens, id);
        if (op < 0) return id;
        String operator = tokens.get(op).toLowerCase();
        boolean negated = operator.equals("not");
        if (negated) {
            op = significant(tokens, op);
            if (op < 0) return id;
            operator = tokens.get(op).toLowerCase();
        }
        if (operator.equals("between")) {
            throw rangeUnsupported();
        }
        if (operator.equals("in")) {
            return list(tokens, id, op, table, negated, out);
        }
        boolean equality = operator.equals("=") || operator.equals("!=") || operator.equals("<>");
        boolean range = operator.equals("<") || operator.equals(">") || operator.equals("<=")
            || operator.equals(">=");
        if (negated || !(equality || range)) return id;
        StringBuilder value = new StringBuilder();
        int end = literal(tokens, significant(tokens, op), value);
        if (end < 0) return id;
        if (range) {
            throw rangeUnsupported();
        }
        out.append("id ").append(operator.equals("=") ? "=" : "!=").append(' ')
            .append(recordId(table, value.toString()));
        return end;
    }

    private static int list(List<String> tokens, int id, int in, String table, boolean negated, StringBuilder out) {
        int at = significant(tokens, in);
        if (at < 0 || !tokens.get(at).equals("(")) return id;
        List<String> terms = new ArrayList<>();
        while (true) {
            StringBuilder value = new StringBuilder();
            int end = literal(tokens, significant(tokens, at), value);
            if (end < 0) return id;
            terms.add("id " + (negated ? "!=" : "=") + " " + recordId(table, value.toString()));
            at = end - 1;
            at = significant(tokens, at);
            if (at < 0) return id;
            if (tokens.get(at).equals(")")) break;
            if (!tokens.get(at).equals(",")) return id;
        }
        out.append('(').append(String.join(negated ? " AND " : " OR ", terms)).append(')');
        return at + 1;
    }

    /** Lê um texto ou número a partir de {@code at}; devolve o token seguinte, ou -1 se não há literal ali. */
    private static int literal(List<String> tokens, int at, StringBuilder value) {
        if (at < 0) return -1;
        if (tokens.get(at).equals("-")) {
            value.append('-');
            at = significant(tokens, at);
            if (at < 0 || !Character.isDigit(tokens.get(at).charAt(0))) return -1;
        }
        String token = tokens.get(at);
        char first = token.charAt(0);
        boolean text = token.length() > 1 && (first == '\'' || first == '"');
        if (!text && !Character.isDigit(first)) return -1;
        value.append(token);
        return at + 1;
    }

    private static int significant(List<String> tokens, int after) {
        for (int i = after + 1; i < tokens.size(); i++) {
            if (!tokens.get(i).isBlank()) return i;
        }
        return -1;
    }

    private static String recordId(String table, String literal) {
        // um texto que já é o record id completo segue como veio
        boolean complete = literal.length() > 1 && literal.substring(1).startsWith(table + ":");
        return complete ? literal : table + ":" + literal;
    }

    private static IllegalArgumentException rangeUnsupported() {
        return new IllegalArgumentException("range comparison on id is not supported by sql.translate");
    }
}
