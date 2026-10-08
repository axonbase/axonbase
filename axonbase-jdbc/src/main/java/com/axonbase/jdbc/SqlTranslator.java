package com.axonbase.jdbc;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates standard SQL (DDL + DML) into AxonQL.
 * Activated via JDBC property {@code sql.translate=true}.
 */
public class SqlTranslator {

    private static final Pattern INSERT = Pattern.compile(
        "INSERT\\s+INTO\\s+(\\w+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\((.+?)\\)(?:\\s*;\\s*)?$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SELECT = Pattern.compile(
        "SELECT\\s+(.+?)\\s+FROM\\s+(\\w+(?:\\s+\\w+)?)(?:\\s+WHERE\\s+(.+?))?(?:\\s+ORDER\\s+BY\\s+(.+?))?(?:\\s+LIMIT\\s+(\\d+))?(?:\\s+OFFSET\\s+(\\d+))?\\s*;?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UPDATE = Pattern.compile(
        "UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+?))?\\s*;?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DELETE = Pattern.compile(
        "DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+?))?\\s*;?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DROP = Pattern.compile(
        "DROP\\s+TABLE(?:\\s+IF\\s+EXISTS)?\\s+(\\w+)(?:\\s+IF\\s+EXISTS)?\\s*;?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_ADD = Pattern.compile(
        "ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+(COLUMN\\s+)?(.+?)\\s*;?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TRUNCATE = Pattern.compile(
        "TRUNCATE\\s+TABLE\\s+(\\w+)\\s*;?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Map<String, String> TYPE_MAP = new LinkedHashMap<>();
    static {
        TYPE_MAP.put("bigint", "int");
        TYPE_MAP.put("integer", "int");
        TYPE_MAP.put("int", "int");
        TYPE_MAP.put("smallint", "int");
        TYPE_MAP.put("tinyint", "int");
        TYPE_MAP.put("numeric", "decimal");
        TYPE_MAP.put("decimal", "decimal");
        TYPE_MAP.put("real", "float");
        TYPE_MAP.put("double", "float");
        TYPE_MAP.put("float", "float");
        TYPE_MAP.put("varchar", "string");
        TYPE_MAP.put("char", "string");
        TYPE_MAP.put("text", "string");
        TYPE_MAP.put("clob", "string");
        TYPE_MAP.put("timestamp", "datetime");
        TYPE_MAP.put("datetime", "datetime");
        TYPE_MAP.put("date", "datetime");
        TYPE_MAP.put("boolean", "bool");
        TYPE_MAP.put("bool", "bool");
        TYPE_MAP.put("blob", "bytes");
        TYPE_MAP.put("bytes", "bytes");
    }

    public static String translate(String sql) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) return "";
        String first = firstKeyword(trimmed);
        return switch (first.toUpperCase()) {
            case "CREATE" -> translateCreate(trimmed);
            case "INSERT" -> translateInsert(trimmed);
            case "SELECT" -> translateSelect(trimmed);
            case "UPDATE" -> translateUpdate(trimmed);
            case "DELETE" -> translateDelete(trimmed);
            case "DROP" -> translateDrop(trimmed);
            case "ALTER" -> translateAlter(trimmed);
            case "TRUNCATE" -> translateTruncate(trimmed);
            default -> trimmed;
        };
    }

    // ------------------------------------------------------------------
    // CREATE TABLE
    // ------------------------------------------------------------------
    static String translateCreate(String sql) {
        String table = null;
        int startParen = -1;
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    // Find table name: skip "CREATE TABLE [IF NOT EXISTS] "
                    String prefix = sql.substring(0, i);
                    java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
                        "CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE
                    ).matcher(prefix);
                    if (tm.find()) table = tm.group(1);
                    startParen = i;
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && table != null) {
                    String colDefs = sql.substring(startParen + 1, i);
                    List<ColumnDef> cols = parseColumns(colDefs);
                    StringBuilder sb = new StringBuilder();
                    sb.append("DEFINE TABLE ").append(table).append(" SCHEMAFULL;");
                    for (ColumnDef col : cols) {
                        if ("id".equalsIgnoreCase(col.name)) continue;
                        String axonType = TYPE_MAP.getOrDefault(col.type.toLowerCase(), col.type.toLowerCase());
                        sb.append(" DEFINE FIELD ").append(col.name).append(" ON TABLE ").append(table).append(" TYPE ").append(axonType).append(";");
                    }
                    return sb.toString();
                }
            }
        }
        return sql;
    }

    static List<ColumnDef> parseColumns(String colDefs) {
        List<ColumnDef> out = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < colDefs.length(); i++) {
            char c = colDefs.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == quote && (i + 1 >= colDefs.length() || colDefs.charAt(i + 1) != quote)) inStr = false;
                continue;
            }
            if (c == '\'' || c == '"') { inStr = true; quote = c; cur.append(c); continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                String part = cur.toString().trim();
                if (!part.isBlank() && !part.toUpperCase().startsWith("PRIMARY") && !part.toUpperCase().startsWith("UNIQUE")
                    && !part.toUpperCase().startsWith("FOREIGN") && !part.toUpperCase().startsWith("CONSTRAINT")
                    && !part.toUpperCase().startsWith("CHECK") && !part.toUpperCase().startsWith("INDEX")) {
                    ColumnDef col = parseColumn(part);
                    if (col != null) out.add(col);
                }
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String last = cur.toString().trim();
        if (!last.isBlank() && !last.toUpperCase().startsWith("PRIMARY") && !last.toUpperCase().startsWith("UNIQUE")
            && !last.toUpperCase().startsWith("FOREIGN") && !last.toUpperCase().startsWith("CONSTRAINT")
            && !last.toUpperCase().startsWith("CHECK") && !last.toUpperCase().startsWith("INDEX")) {
            ColumnDef col = parseColumn(last);
            if (col != null) out.add(col);
        }
        return out;
    }

    static ColumnDef parseColumn(String s) {
        // Strip common constraint suffixes
        s = s.replaceAll("(?i)\\s+GENERATED\\s+BY\\s+DEFAULT\\s+AS\\s+IDENTITY", "");
        s = s.replaceAll("(?i)\\s+GENERATED\\s+ALWAYS\\s+AS\\s+IDENTITY", "");
        s = s.replaceAll("(?i)\\s+AS\\s+IDENTITY", "");
        s = s.replaceAll("(?i)\\s+(NOT\\s+)?NULL\\b", "");
        s = s.replaceAll("(?i)\\s+DEFAULT\\s+\\S+", "");
        s = s.replaceAll("(?i)\\s+UNIQUE\\b", "");

        String[] parts = s.trim().split("\\s+", 2);
        if (parts.length < 1 || parts[0].isBlank()) return null;
        String name = parts[0];
        String type = parts.length > 1 ? parts[1] : "string";
        // Strip precision (VARCHAR(255) → VARCHAR)
        int paren = type.indexOf('(');
        if (paren > 0) type = type.substring(0, paren);
        return new ColumnDef(name, type.toUpperCase());
    }

    static record ColumnDef(String name, String type) {}

    // ------------------------------------------------------------------
    // INSERT INTO
    // ------------------------------------------------------------------
    static String translateInsert(String sql) {
        Matcher m = INSERT.matcher(sql);
        if (!m.matches()) return sql;
        String table = m.group(1);
        String[] colNames = m.group(2).split("\\s*,\\s*");
        String[] values = splitValues(m.group(3));
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE ").append(table).append(" CONTENT {");
        for (int i = 0; i < colNames.length && i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(colNames[i].trim()).append(": ").append(values[i].trim());
        }
        sb.append("}");
        return sb.toString();
    }

    static String[] splitValues(String vals) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < vals.length(); i++) {
            char c = vals.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == quote && (i + 1 >= vals.length() || vals.charAt(i + 1) != quote)) inStr = false;
                continue;
            }
            if (c == '\'' || c == '"') { inStr = true; quote = c; cur.append(c); continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) out.add(last);
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // SELECT
    // ------------------------------------------------------------------
    static String translateSelect(String sql) {
        Matcher m = SELECT.matcher(sql);
        if (!m.matches()) return sql;
        String cols = m.group(1).trim();
        String table = m.group(2).trim();
        String where = m.group(3);
        String orderBy = m.group(4);
        String limit = m.group(5);
        String offset = m.group(6);

        // Normalize COUNT(*) → count(), COUNT(1) → count()
        cols = cols.replaceAll("(?i)COUNT\\s*\\(\\s*\\*\\s*\\)", "count()");
        cols = cols.replaceAll("(?i)COUNT\\s*\\(\\s*[^)]+\\s*\\)", "count()");

        // Remove table alias prefix from columns (e.g. p.name → name)
        cols = cols.replaceAll("\\w+\\.", "");

        // Remove FROM table alias
        String fromClause = table;
        String[] tableParts = table.split("\\s+");
        if (tableParts.length > 1) {
            fromClause = tableParts[0];
        }

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(cols).append(" FROM ").append(fromClause);
        if (where != null && !where.isBlank()) sb.append(" WHERE ").append(where.trim());
        if (orderBy != null && !orderBy.isBlank()) sb.append(" ORDER BY ").append(orderBy.trim());
        if (limit != null && !limit.isBlank()) sb.append(" LIMIT ").append(limit.trim());
        if (offset != null && !offset.isBlank()) sb.append(" START ").append(offset.trim());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // UPDATE
    // ------------------------------------------------------------------
    static String translateUpdate(String sql) {
        Matcher m = UPDATE.matcher(sql);
        if (!m.matches()) return sql;
        String table = m.group(1);
        String set = m.group(2);
        String where = m.group(3);
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ").append(table).append(" SET ").append(set.trim());
        if (where != null && !where.isBlank()) sb.append(" WHERE ").append(where.trim());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // DELETE
    // ------------------------------------------------------------------
    static String translateDelete(String sql) {
        Matcher m = DELETE.matcher(sql);
        if (!m.matches()) return sql;
        String table = m.group(1);
        String where = m.group(2);
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE ").append(table);
        if (where != null && !where.isBlank()) sb.append(" WHERE ").append(where.trim());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // DROP
    // ------------------------------------------------------------------
    static String translateDrop(String sql) {
        Matcher m = DROP.matcher(sql);
        if (!m.matches()) return sql;
        return "REMOVE TABLE " + m.group(1);
    }

    // ------------------------------------------------------------------
    // ALTER TABLE ADD COLUMN
    // ------------------------------------------------------------------
    static String translateAlter(String sql) {
        Matcher m = ALTER_ADD.matcher(sql);
        if (!m.matches()) return sql;
        String table = m.group(1);
        String colDef = m.group(3);
        ColumnDef col = parseColumn(colDef);
        String axonType = TYPE_MAP.getOrDefault(col.type.toLowerCase(), col.type.toLowerCase());
        return "DEFINE FIELD " + col.name + " ON TABLE " + table + " TYPE " + axonType;
    }

    // ------------------------------------------------------------------
    // TRUNCATE
    // ------------------------------------------------------------------
    static String translateTruncate(String sql) {
        Matcher m = TRUNCATE.matcher(sql);
        if (!m.matches()) return sql;
        return "DELETE " + m.group(1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private static String firstKeyword(String sql) {
        int i = 0;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) i++;
        StringBuilder kw = new StringBuilder();
        while (i < sql.length() && Character.isLetter(sql.charAt(i))) {
            kw.append(sql.charAt(i));
            i++;
        }
        return kw.toString();
    }
}