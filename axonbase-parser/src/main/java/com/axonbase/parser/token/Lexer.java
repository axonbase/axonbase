package com.axonbase.parser.token;

import com.axonbase.common.AxonError;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Lexer da AxonQL feito à mão. Produz tokens com keywords case-insensitive. */
public final class Lexer {

    private static final Set<String> KEYWORDS = Set.of(
        "use", "set", "let", "create", "insert", "into", "ignore", "relation",
        "update", "upsert", "merge", "patch", "replace", "content", "delete", "select",
        "from", "where", "order", "by", "limit", "start", "fetch", "only", "return",
        "relate", "define", "table", "field", "index", "event", "type", "on", "columns",
        "unique", "count", "sum", "avg", "min", "max", "assert", "readonly", "value", "default", "schemafull",
        "schemaless", "drop", "permissions", "for", "group", "namespace", "root", "database",
        "if", "then", "else", "end", "when", "error", "kill", "info", "and", "or", "not",
        "true", "false", "null", "none", "contains", "inside", "outside", "intersects",
        "begin", "commit", "cancel", "any", "option", "live", "diff",
        "in", "as", "asc", "desc", "user", "access", "password", "passhash", "roles",
        "analyzer", "search", "lowercase", "stopwords", "stemming", "geo", "hnsw", "dimension", "dist",
        "int", "float", "number", "decimal", "string", "bool", "datetime", "uuid",
        "array", "object", "bytes", "duration", "record", "geometry", "vector"
    );

    private static final List<String> DUR_UNITS = List.of("ms", "us", "ns", "y", "w", "d", "h", "m", "s");

    private final String src;
    private int pos;
    private final List<Token> tokens = new ArrayList<>();

    public Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) {
        return new Lexer(src).scan();
    }

    private List<Token> scan() {
        int n = src.length();
        while (pos < n) {
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", n, n, null));
        return tokens;
    }

    private void scanToken() {
        char c = src.charAt(pos);
        switch (c) {
            case ' ', '\t', '\r', '\n' -> skipWs();
            case '-' -> minusOrComment();
            case '/' -> slash();
            case '`', '\u27e8' -> quotedIdent();
            case '$' -> param();
            case '\'', '"' -> string();
            default -> {
                if (isPrefixString(c)) {
                    prefixed();
                } else if (isIdentStart(c)) {
                    ident();
                } else if (Character.isDigit(c)) {
                    number();
                } else {
                    operator();
                }
            }
        }
    }

    private void skipWs() {
        while (pos < src.length() && " \t\r\n".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
    }

    private void minusOrComment() {
        if (pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
            skipLine();
        } else if (pos + 1 < src.length() && src.charAt(pos + 1) == '>') {
            int start = pos;
            pos += 2;
            emit(TokenType.ARROW, src.substring(start, pos));
        } else {
            single(TokenType.MINUS);
        }
    }

    private void slash() {
        if (pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
            skipLine();
        } else if (pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
            skipBlock();
        } else {
            single(TokenType.SLASH);
        }
    }

    private void skipLine() {
        while (pos < src.length() && src.charAt(pos) != '\n') {
            pos++;
        }
    }

    private void skipBlock() {
        int end = src.indexOf("*/", pos + 2);
        if (end < 0) {
            throw error("comentário de bloco não fechado");
        }
        pos = end + 2;
    }

    private boolean isPrefixString(char c) {
        return (c == 'd' || c == 'u' || c == 'b' || c == 's' || c == 'r')
            && pos + 1 < src.length() && src.charAt(pos + 1) == '"';
    }

    private void prefixed() {
        char prefix = src.charAt(pos);
        pos++;
        int rawStart = pos - 1;
        String content = readStringContent();
        String raw = src.substring(rawStart, pos);
        switch (prefix) {
            case 'd' -> emit(TokenType.DATETIME, raw, content);
            case 'u' -> emit(TokenType.UUID, raw, content);
            case 'b' -> emit(TokenType.BYTES, raw, content);
            case 'r' -> emit(TokenType.RECORD_ID_STR, raw, content);
            default -> emit(TokenType.STRING, raw, content);
        }
    }

    private void string() {
        int rawStart = pos;
        String content = readStringContent();
        String raw = src.substring(rawStart, pos);
        emit(TokenType.STRING, raw, content);
    }

    private String readStringContent() {
        char quote = src.charAt(pos);
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("string não fechada");
            }
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length()) {
                pos++;
                char e = src.charAt(pos);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '0' -> sb.append('\0');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case 'u' -> {
                        if (pos + 4 < src.length()) {
                            sb.append((char) Integer.parseInt(src.substring(pos + 1, pos + 5), 16));
                            pos += 4;
                        }
                    }
                    default -> sb.append(e);
                }
                pos++;
            } else if (c == quote) {
                pos++;
                break;
            } else {
                sb.append(c);
                pos++;
            }
        }
        return sb.toString();
    }

    private void single(TokenType t) {
        int start = pos++;
        emit(t, src.substring(start, pos));
    }

    private void emit(TokenType type, String text) {
        emit(type, text, null);
    }

    private void emit(TokenType type, String text, Object literal) {
        tokens.add(new Token(type, text, pos, pos + text.length(), literal));
    }

    private static boolean isIdentStart(char c) {
        return Character.isAlphabetic(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == '!';
    }

    private void ident() {
        int start = pos++;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        String text = src.substring(start, pos);
        if (KEYWORDS.contains(text.toLowerCase())) {
            emit(TokenType.KEYWORD, text.toLowerCase());
        } else {
            emit(TokenType.IDENT, text);
        }
    }

    private void quotedIdent() {
        char open = src.charAt(pos);
        char close = open == '`' ? '`' : '\u27e9';
        int closeIdx = src.indexOf(close, pos + 1);
        if (closeIdx < 0) {
            throw error("identificador não fechado");
        }
        String content = src.substring(pos + 1, closeIdx);
        int rawStart = pos;
        pos = closeIdx + 1;
        tokens.add(new Token(TokenType.IDENT, content, rawStart, pos, content));
    }

    private void param() {
        pos++;
        int start = pos;
        while (pos < src.length() && (Character.isAlphabetic(src.charAt(pos))
            || Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
        }
        if (start == pos) {
            throw error("parâmetro sem nome");
        }
        String name = src.substring(start, pos);
        tokens.add(new Token(TokenType.PARAM, name, start - 1, pos, name));
    }

    private void number() {
        int start = pos;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
        }
        boolean isFloat = false;
        if (pos < src.length() && src.charAt(pos) == '.' && pos + 1 < src.length()
            && Character.isDigit(src.charAt(pos + 1))) {
            isFloat = true;
            pos++;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
        }
        String digits = src.substring(start, pos).replace("_", "");

        if (!isFloat) {
            String unit = matchDurationUnit();
            if (unit != null) {
                long millis = durationMillis(digits, unit);
                int end = pos + unit.length();
                tokens.add(new Token(TokenType.DURATION, src.substring(start, end), start, end, millis));
                pos = end;
                return;
            }
        }
        if (pos < src.length() && src.charAt(pos) == 'f') {
            pos++;
            int end = pos;
            tokens.add(new Token(TokenType.FLOAT, src.substring(start, end), start, end, Double.parseDouble(digits)));
            return;
        }
        if (pos + 3 <= src.length() && src.startsWith("dec", pos)) {
            pos += 3;
            int end = pos;
            tokens.add(new Token(TokenType.DECIMAL, src.substring(start, end), start, end, new BigDecimal(digits)));
            return;
        }
        if (isFloat) {
            tokens.add(new Token(TokenType.FLOAT, src.substring(start, pos), start, pos, Double.parseDouble(digits)));
        } else {
            tokens.add(new Token(TokenType.INT, src.substring(start, pos), start, pos, Long.parseLong(digits)));
        }
    }

    private String matchDurationUnit() {
        for (String u : DUR_UNITS) {
            if (src.startsWith(u, pos)) {
                return u;
            }
        }
        return null;
    }

    private static long durationMillis(String digits, String unit) {
        long n = Long.parseLong(digits);
        return switch (unit) {
            case "y" -> n * 31_536_000_000L;
            case "w" -> n * 604_800_000L;
            case "d" -> n * 86_400_000L;
            case "h" -> n * 3_600_000L;
            case "m" -> n * 60_000L;
            case "s" -> n * 1_000L;
            case "ms" -> n;
            case "us", "ns" -> 0L;
            default -> throw new IllegalStateException("unidade de duração desconhecida: " + unit);
        };
    }

    private void operator() {
        char c = src.charAt(pos);
        switch (c) {
            case '(' -> single(TokenType.LPAREN);
            case ')' -> single(TokenType.RPAREN);
            case '{' -> single(TokenType.LBRACE);
            case '}' -> single(TokenType.RBRACE);
            case '[' -> single(TokenType.LBRACKET);
            case ']' -> single(TokenType.RBRACKET);
            case ';' -> single(TokenType.SEMI);
            case ',' -> single(TokenType.COMMA);
            case '@' -> singleOr(TokenType.MATCH, TokenType.AT, '@');
            case '.' -> dotDot();
            case ':' -> colons();
            case '=' -> singleOr(TokenType.EQ_EQ, TokenType.EQ, '=');
            case '!' -> singleOr(TokenType.NE, TokenType.BANG, '=');
            case '>' -> gt();
            case '<' -> lt();
            case '&' -> single(TokenType.AND_AND);
            case '|' -> single(TokenType.OR_OR);
            case '?' -> qmark();
            case '*' -> star();
            case '+' -> single(TokenType.PLUS);
            case '%' -> single(TokenType.PERCENT);
            default -> throw error("caractere inesperado '" + c + "'");
        }
    }

    private void singleOr(TokenType two, TokenType one, char second) {
        int start = pos++;
        if (pos < src.length() && src.charAt(pos) == second) {
            pos++;
            emit(two, src.substring(start, pos));
        } else {
            emit(one, src.substring(start, pos));
        }
    }

    private void dotDot() {
        int start = pos++;
        if (pos + 1 < src.length() && src.charAt(pos) == '.') {
            pos++;
            if (pos < src.length() && src.charAt(pos) == '=') {
                pos++;
                emit(TokenType.DOT_DOT_EQ, src.substring(start, pos));
            } else {
                emit(TokenType.DOT_DOT, src.substring(start, pos));
            }
        } else {
            emit(TokenType.DOT, src.substring(start, pos));
        }
    }

    private void colons() {
        int start = pos++;
        if (pos < src.length() && src.charAt(pos) == ':') {
            pos++;
            emit(TokenType.COLON_COLON, src.substring(start, pos));
        } else if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            emit(TokenType.ASSIGN, src.substring(start, pos));
        } else {
            emit(TokenType.COLON, src.substring(start, pos));
        }
    }

    private void gt() {
        int start = pos++;
        if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            emit(TokenType.GE, src.substring(start, pos));
        } else if (pos + 1 < src.length() && src.charAt(pos) == '.' && src.charAt(pos + 1) == '.') {
            pos += 2;
            if (pos < src.length() && src.charAt(pos) == '=') {
                pos++;
                emit(TokenType.GT_DOT_DOT_EQ, src.substring(start, pos));
            } else {
                emit(TokenType.GT_DOT_DOT, src.substring(start, pos));
            }
        } else {
            emit(TokenType.GT, src.substring(start, pos));
        }
    }

    private void lt() {
        int start = pos++;
        if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            emit(TokenType.LE, src.substring(start, pos));
        } else if (pos + 1 < src.length() && src.charAt(pos) == '-' && src.charAt(pos + 1) == '>') {
            pos += 2;
            emit(TokenType.B_ARROW, src.substring(start, pos));
        } else if (pos + 1 < src.length() && src.charAt(pos) == '-') {
            pos++;
            emit(TokenType.LARROW, src.substring(start, pos));
        } else {
            emit(TokenType.LT, src.substring(start, pos));
        }
    }

    private void qmark() {
        int start = pos++;
        if (pos < src.length() && src.charAt(pos) == '?') {
            pos++;
            emit(TokenType.QUESTION_QUESTION, src.substring(start, pos));
        } else if (pos < src.length() && src.charAt(pos) == ':') {
            pos++;
            emit(TokenType.QMARK_COLON, src.substring(start, pos));
        } else if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            emit(TokenType.QMARK_EQ, src.substring(start, pos));
        } else {
            emit(TokenType.QMARK, src.substring(start, pos));
        }
    }

    private void star() {
        int start = pos++;
        if (pos < src.length() && src.charAt(pos) == '*') {
            pos++;
            emit(TokenType.STAR_STAR, src.substring(start, pos));
        } else if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
            emit(TokenType.STAR_EQ, src.substring(start, pos));
        } else {
            emit(TokenType.STAR, src.substring(start, pos));
        }
    }

    private AxonError error(String msg) {
        return AxonError.parse("erro de lexado na posição " + pos + ": " + msg);
    }
}
