package com.axonbase.value;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Citação segura de identificadores e valores em texto AxonQL. Todo código que monta uma consulta com dado de fora
 * (nome de tabela, chave de registro, valor) deve passar por aqui. Quando possível, prefira enviar o valor como
 * parâmetro ({@code $nome}).
 */
public final class AxonQlText {

    /** Palavras-chave da AxonQL. O lexer as reconhece sem diferenciar maiúsculas e as devolve em minúsculas. */
    public static final Set<String> KEYWORDS = Set.of(
        "use", "set", "let", "create", "insert", "into", "ignore", "relation",
        "update", "upsert", "merge", "patch", "replace", "content", "delete", "select",
        "from", "where", "order", "by", "limit", "start", "fetch", "only", "return",
        "relate", "define", "table", "field", "index", "event", "type", "on", "columns",
        "unique", "count", "sum", "avg", "min", "max", "assert", "readonly", "value", "default", "schemafull",
        "schemaless", "drop", "permissions", "for", "group", "namespace", "root", "database",
        "if", "then", "else", "end", "when", "error", "kill", "info", "and", "or", "not",
        "true", "false", "null", "none", "contains", "inside", "outside", "intersects",
        "begin", "commit", "cancel", "any", "option", "live", "diff",
        "savepoint", "release", "rollback",
        "in", "as", "asc", "desc", "user", "access", "password", "passhash", "roles",
        "jks", "path", "certificate", "fingerprint", "collect", "oid", "icpbrasil",
        "analyzer", "search", "lowercase", "stopwords", "stemming", "geo", "hnsw", "dimension", "dist",
        "references", "join",
        "int", "float", "number", "decimal", "string", "bool", "datetime", "uuid",
        "array", "object", "bytes", "duration", "record", "geometry", "vector",
        "explain", "analyze", "at", "before", "timestamp", "statement",
        "link", "connect", "with", "saga", "correlation", "databases", "describe", "show",
        "data", "rule", "rules", "apply", "mask", "fields", "columnar",
        "ai", "audit", "warning", "danger", "reason", "case", "cases",
        "authorized", "denied", "audited", "remove",
        "values", "primary", "key", "check", "schema", "flexible",
        "alter", "add", "column", "modify", "revoke", "grant"
    );

    /** Palavras que, sem crases, seriam lidas como sintaxe onde se espera um nome. */
    private static final Set<String> RESERVED = Set.of(
        "true", "false", "null", "none", "not", "select", "only", "check", "primary", "constraint", "foreign");

    private static final Pattern PLAIN = Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*");

    private static final Pattern INTEGER = Pattern.compile("0|-?[1-9]\\d{0,17}");

    private AxonQlText() {
    }

    /**
     * Nome (tabela, campo, índice...) como a AxonQL o relê: sem crases quando é simples, senão entre crases ou
     * {@code ⟨ ⟩}.
     *
     * @throws IllegalArgumentException nome nulo, ou com crase e {@code ⟩} ao mesmo tempo (não há como citar)
     */
    public static String identifier(String name) {
        if (name == null) {
            throw new IllegalArgumentException("identificador AxonQL nulo");
        }
        return isBare(name) ? name : quotedIdentifier(name);
    }

    /**
     * Nome sempre entre crases, ou entre {@code ⟨ ⟩} quando ele tem crase, mesmo que seja simples.
     *
     * @throws IllegalArgumentException nome nulo, ou com crase e {@code ⟩} ao mesmo tempo (não há como citar)
     */
    public static String quotedIdentifier(String name) {
        if (name == null) {
            throw new IllegalArgumentException("identificador AxonQL nulo");
        }
        if (name.indexOf('`') < 0) {
            return "`" + name + "`";
        }
        if (name.indexOf('⟩') < 0) {
            return "⟨" + name + "⟩";
        }
        throw new IllegalArgumentException("identificador AxonQL não pode conter crase e ⟩: " + name);
    }

    private static boolean isBare(String name) {
        if (!PLAIN.matcher(name).matches()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !RESERVED.contains(lower) && (lower.equals(name) || !KEYWORDS.contains(lower));
    }

    /** Caminho de campo ({@code a.b.c}), com cada segmento citado. */
    public static String path(String dotted) {
        if (dotted == null) {
            throw new IllegalArgumentException("identificador AxonQL nulo");
        }
        String[] segments = dotted.split("\\.", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(identifier(segments[i]));
        }
        return sb.toString();
    }

    /** Literal de texto. */
    public static String string(String text) {
        return AxonJson.write(AxonValue.str(text));
    }

    /**
     * Chave de registro. Texto que é um inteiro canônico sai como número (é assim que {@code t:7} é lido); os demais
     * textos saem nus só quando simples, senão entre aspas.
     */
    public static String recordKey(Object key) {
        if (key instanceof AxonValue value) {
            return switch (value.type()) {
                case STRING -> textKey(value.asString());
                case NUMBER -> numberKey(value);
                case UUID -> string(value.asUuidValue().toString());
                default -> value(value);
            };
        }
        if (key instanceof BigDecimal decimal) {
            return numberKey(AxonValue.num(decimal));
        }
        if (key instanceof Double || key instanceof Float) {
            return numberKey(AxonValue.num(((Number) key).doubleValue()));
        }
        if (key instanceof Number number) {
            return numberKey(AxonValue.num(number.longValue()));
        }
        String text = String.valueOf(key);
        return INTEGER.matcher(text).matches() ? text : textKey(text);
    }

    private static String textKey(String text) {
        return PLAIN.matcher(text).matches() && !KEYWORDS.contains(text.toLowerCase(Locale.ROOT))
            ? text : string(text);
    }

    /** {@code tabela:chave} com as duas partes citadas. */
    public static String recordId(String table, Object key) {
        return identifier(table) + ":" + recordKey(key);
    }

    /**
     * Literal AxonQL de um valor, para montar uma consulta: texto, chaves de objeto e nomes saem escapados, e uma
     * referência a registro continua sendo um record id.
     */
    public static String value(AxonValue v) {
        StringBuilder sb = new StringBuilder();
        append(sb, v, true);
        return sb.toString();
    }

    /**
     * Como {@link #value(AxonValue)}, mas a referência a registro sai como o texto que o armazenamento guarda. É o
     * formato do dump lógico.
     */
    public static String storedValue(AxonValue v) {
        StringBuilder sb = new StringBuilder();
        append(sb, v, false);
        return sb.toString();
    }

    /**
     * Número como vale numa chave de registro, com o sinal no próprio literal. O decimal leva o sufixo {@code dec};
     * sem casas ele precisa do expoente ({@code 7e0dec}), porque {@code 7dec} seria lido como uma duração.
     */
    public static String numberKey(AxonValue v) {
        Object number = v.raw();
        if (number instanceof Long whole && whole == Long.MIN_VALUE) {
            // o lexer não lê o módulo deste inteiro
            return whole + "e0dec";
        }
        if (number instanceof BigDecimal decimal) {
            String digits = decimal.scale() > 0
                ? decimal.toPlainString()
                : decimal.unscaledValue() + "e" + (-decimal.scale());
            return digits + "dec";
        }
        return number.toString();
    }

    private static void append(StringBuilder sb, AxonValue v, boolean liveRecords) {
        switch (v.type()) {
            case NONE -> sb.append("NONE");
            case NULL -> sb.append("null");
            case BOOL -> sb.append(v.asBool());
            case NUMBER -> number(sb, v);
            // o escape do JSON é entendido pelo lexer e mantém cada registro numa linha
            case STRING -> sb.append(AxonJson.write(v));
            // o lexer só aceita os prefixos d, u e b com aspas duplas
            case DATETIME -> sb.append("d\"").append(v.asInstant()).append('"');
            case UUID -> sb.append("u\"").append(v.asUuidValue()).append('"');
            case BYTES -> sb.append("b\"").append(HexFormat.of().formatHex((byte[]) v.raw())).append('"');
            case DURATION -> sb.append(v.asDuration().millis()).append("ms");
            case ARRAY -> sequence(sb, '[', v.asArray(), ']', liveRecords);
            // "{}" seria um objeto vazio
            case SET -> {
                if (v.asSet().isEmpty()) {
                    sb.append("[]");
                } else {
                    sequence(sb, '{', v.asSet(), '}', liveRecords);
                }
            }
            case OBJECT -> {
                sb.append('{');
                String separator = "";
                for (Map.Entry<String, AxonValue> entry : v.asObject().entrySet()) {
                    sb.append(separator).append(string(entry.getKey())).append(": ");
                    append(sb, entry.getValue(), liveRecords);
                    separator = ", ";
                }
                sb.append('}');
            }
            case TABLE -> sb.append("type::table(").append(string(v.asTable())).append(')');
            // sem aspas, uma chave com espaço ou hífen seria lida como outra expressão
            case RECORD_ID -> sb.append(liveRecords
                ? recordId(v.asRecordId().table(), v.asRecordId().key())
                : AxonJson.write(v));
        }
    }

    private static void number(StringBuilder sb, AxonValue v) {
        Object number = v.raw();
        if (number instanceof Long whole && whole == Long.MIN_VALUE) {
            sb.append('(').append(Long.MIN_VALUE + 1).append(" - 1)");
            return;
        }
        // compare também separa o zero negativo, que "real < 0" não pega
        if (number instanceof Double real && Double.compare(real, 0.0) < 0) {
            // o menos unário transforma real em decimal; o cast devolve o tipo
            sb.append("<float> ");
        }
        sb.append(numberKey(v));
    }

    private static void sequence(StringBuilder sb, char open, List<AxonValue> items, char close,
                                 boolean liveRecords) {
        sb.append(open);
        String separator = "";
        for (AxonValue item : items) {
            sb.append(separator);
            append(sb, item, liveRecords);
            separator = ", ";
        }
        sb.append(close);
    }
}
