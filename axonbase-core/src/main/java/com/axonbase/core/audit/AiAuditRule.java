package com.axonbase.core.audit;

import java.util.List;

/**
 * Regra atômica gerada pela IA no momento da criação do AI AUDIT.
 * Usada para matching local (sem chamar IA) em cada SQL executado.
 */
public record AiAuditRule(String type, String pattern, List<String> commands,
                          List<String> words, Boolean requireWhere) {

    public boolean matches(String sql) {
        String upper = sql.toUpperCase().trim();
        return switch (type) {
            case "regex" -> pattern != null && java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql).find();
            case "command" -> commands != null && commands.stream().anyMatch(cmd -> upper.startsWith(cmd.toUpperCase()) || upper.contains(" " + cmd.toUpperCase()));
            case "keyword" -> words != null && words.stream().anyMatch(upper::contains);
            case "require_where" -> {
                boolean isUpdateDelete = upper.startsWith("UPDATE") || upper.startsWith("DELETE");
                boolean hasWhere = upper.contains(" WHERE ");
                yield isUpdateDelete && !hasWhere;
            }
            default -> false;
        };
    }
}