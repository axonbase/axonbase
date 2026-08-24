package com.axonbase.value;

import com.axonbase.common.AxonError;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Codec JSON (JavaScript Object Notation) manual para {@link AxonValue}, sen
 * dependencias externas. É o formato de wire principal no MVP.
 * <p>
 * Regras de serialización:
 * <ul>
 *   <li>{@code none}/{@code null} -&gt; {@code null}</li>
 *   <li>{@code bool} -&gt; {@code true}/{@code false}</li>
 *   <li>{@code number} -&gt; número (decimal como string)</li>
 *   <li>{@code string} -&gt; string</li>
 *   <li>{@code datetime} -&gt; RFC3339 string</li>
 *   <li>{@code uuid} -&gt; string con hífen</li>
 *   <li>{@code array}/{@code set} -&gt; array</li>
 *   <li>{@code object} -&gt; object</li>
 *   <li>{@code record} -&gt; string {@code name:key}</li>
 *   <li>{@code table} -&gt; string</li>
 *   <li>{@code duration} -&gt; string estilo AxonQL</li>
 *   <li>{@code bytes} -&gt; array de números</li>
 * </ul>
 */
public final class AxonJson {

    private AxonJson() {
    }

    // ------------------------------------------------------------------
    // Serialización a string JSON
    // ------------------------------------------------------------------

    public static String write(AxonValue value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value);
        return sb.toString();
    }

    /** Serializa un {@link AxonValue} a bytes UTF-8. */
    public static byte[] encode(AxonValue value) {
        return write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Deserializa bytes UTF-8 a {@link AxonValue}. */
    public static AxonValue decode(byte[] data) {
        return parseDocument(new String(data, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void writeTo(StringBuilder sb, AxonValue v) {
        switch (v.type()) {
            case NONE, NULL -> sb.append("null");
            case BOOL -> sb.append(v.asBool() ? "true" : "false");
            case NUMBER -> writeNumber(sb, v);
            case STRING -> writeString(sb, v.asString());
            case DATETIME -> writeString(sb, v.asInstant().toString());
            case UUID -> writeString(sb, v.asUuidValue().toString());
            case DURATION -> writeString(sb, v.asDuration().toString());
            case ARRAY, SET -> writeArray(sb, v);
            case OBJECT -> writeObject(sb, v);
            case BYTES -> writeBytes(sb, (byte[]) v.raw());
            case TABLE -> writeString(sb, v.asTable());
            case RECORD_ID -> writeString(sb, v.asRecordId().toString());
        }
    }

    private static void writeNumber(StringBuilder sb, AxonValue v) {
        Object n = v.raw();
        if (n instanceof BigDecimal dec) {
            sb.append(dec.toPlainString());
        } else {
            sb.append(n.toString());
        }
    }

    private static void writeArray(StringBuilder sb, AxonValue v) {
        sb.append('[');
        List<AxonValue> arr = v.asArray();
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeTo(sb, arr.get(i));
        }
        sb.append(']');
    }

    private static void writeObject(StringBuilder sb, AxonValue v) {
        sb.append('{');
        Map<String, AxonValue> obj = v.asObject();
        boolean first = true;
        for (Map.Entry<String, AxonValue> e : obj.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeTo(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeBytes(StringBuilder sb, byte[] data) {
        sb.append('[');
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(data[i] & 0xFF);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (s.codePointAt(i)) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Parse de string JSON a AxonValue
    // ------------------------------------------------------------------

    public static AxonValue parseDocument(String json) {
        Parser p = new Parser(json);
        AxonValue v = p.parseValue();
        p.skipWs();
        if (!p.eof()) {
            throw decodeError("conteúdo extra despois do valor JSON");
        }
        return v;
    }

    private static AxonError decodeError(String msg) {
        return AxonError.parse("erro JSON: " + msg);
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        void skipWs() {
            while (!eof()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        AxonValue parseValue() {
            skipWs();
            if (eof()) {
                throw decodeError("final de entrada inesperado");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> AxonValue.str(parseString());
                case 't' -> parseKeyword("true", AxonValue.bool(true));
                case 'f' -> parseKeyword("false", AxonValue.bool(false));
                case 'n' -> parseKeyword("null", AxonValue.nul());
                default -> parseNumber();
            };
        }

        AxonValue parseObject() {
            expect('{');
            Map<String, AxonValue> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return AxonValue.object(map);
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw decodeError("esperado string como chave de objeto");
                }
                String key = parseString();
                skipWs();
                expect(':');
                AxonValue v = parseValue();
                map.put(key, v);
                skipWs();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw decodeError("esperado ',' ou '}' en objeto");
            }
            return AxonValue.object(map);
        }

        AxonValue parseArray() {
            expect('[');
            List<AxonValue> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return AxonValue.array(list);
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw decodeError("esperado ',' ou ']' en array");
            }
            return AxonValue.array(list);
        }

        AxonValue parseNumber() {
            int start = pos;
            while (!eof()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) {
                throw decodeError("número JSON inválido");
            }
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    double d = Double.parseDouble(token);
                    if (Double.isFinite(d)) {
                        return AxonValue.num(d);
                    }
                }
                return AxonValue.num(Long.parseLong(token));
            } catch (NumberFormatException e) {
                throw decodeError("número JSON inválido: " + token);
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        default -> throw decodeError("escape JSON inválido: \\" + esc);
                    }
                    continue;
                }
                sb.append(c);
            }
            throw decodeError("string JSON non terminada");
        }

        AxonValue parseKeyword(String kw, AxonValue v) {
            if (s.startsWith(kw, pos)) {
                pos += kw.length();
                return v;
            }
            throw decodeError("literal inválido");
        }

        char peek() {
            if (eof()) {
                return '\0';
            }
            return s.charAt(pos);
        }

        char next() {
            if (eof()) {
                throw decodeError("final de entrada inesperado");
            }
            return s.charAt(pos++);
        }

        void expect(char c) {
            char got = next();
            if (got != c) {
                throw decodeError("esperado '" + c + "' pero era '" + got + "'");
            }
        }
    }
}