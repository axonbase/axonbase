package com.axonbase.value;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;

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

    /**
     * Formato compacto de duración no wire: {@code 1h}, {@code 30m}, {@code 500ms},
     * sempre redondável a uma unidade para que o parse seja estável.
     */
    static String durationToString(long millis) {
        if (millis == 0) {
            return "0ms";
        }
        if (millis % 3_600_000L == 0) {
            return (millis / 3_600_000L) + "h";
        }
        if (millis % 60_000L == 0) {
            return (millis / 60_000L) + "m";
        }
        if (millis % 1_000L == 0) {
            return (millis / 1_000L) + "s";
        }
        return millis + "ms";
    }

    /** Converte texto de duração ({@code 1h30m}, {@code 500ms}, {@code 2d}) em milisegundos. */
    static long durationFromString(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("value_duration_empty"));
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(\\d+(?:\\.\\d+)?)(ms|w|d|h|m|s)")
            .matcher(s);
        long total = 0;
        int last = 0;
        while (m.find()) {
            if (m.start() != last) {
                throw new IllegalArgumentException(Messages.get("value_duration_unexpected_syntax", s));
            }
            long amount = java.lang.Long.parseLong(m.group(1));
            long unit = switch (m.group(2)) {
                case "ms" -> 1L;
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> 0L;
            };
            total += amount * unit;
            last = m.end();
        }
        if (last != s.length()) {
            throw new IllegalArgumentException(Messages.get("value_duration_invalid", s));
        }
        return total;
    }

    private static void writeTo(StringBuilder sb, AxonValue v) {
        switch (v.type()) {
            case NONE, NULL -> sb.append("null");
            case BOOL -> sb.append(v.asBool() ? "true" : "false");
            case NUMBER -> writeNumber(sb, v);
            case STRING -> writeString(sb, v.asString());
            case DATETIME -> writeTaggedString(sb, "$datetime", v.asInstant().toString());
            case UUID -> writeTaggedString(sb, "$uuid", v.asUuidValue().toString());
            case DURATION -> writeTaggedString(sb, "$duration", durationToString(v.asDuration().millis()));
            case ARRAY, SET -> writeArray(sb, v);
            case OBJECT -> writeObject(sb, v);
            case BYTES -> writeBytes(sb, (byte[]) v.raw());
            case TABLE -> writeTaggedString(sb, "$table", v.asTable());
            case RECORD_ID -> writeString(sb, v.asRecordId().toString());
        }
    }

    private static void writeNumber(StringBuilder sb, AxonValue v) {
        Object n = v.raw();
        if (n instanceof BigDecimal dec) {
            writeTaggedString(sb, "$decimal", dec.toPlainString());
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
        writeTaggedString(sb, "$bytes", Base64.getEncoder().encodeToString(data));
    }

    private static void writeTaggedString(StringBuilder sb, String tag, String value) {
        sb.append('{');
        writeString(sb, tag);
        sb.append(':');
        writeString(sb, value);
        sb.append('}');
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
            throw decodeError(Messages.get("json_extra_content"));
        }
        return v;
    }

    private static AxonError decodeError(String msg) {
        return AxonError.parse(Messages.get("json_parse_error", msg));
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
                throw decodeError(Messages.get("json_unexpected_end"));
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
                    throw decodeError(Messages.get("json_expected_object_key"));
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
                throw decodeError(Messages.get("json_expected_object_separator"));
            }
            if (map.size() == 1) {
                AxonValue tagged = parseTagged(map);
                if (tagged != null) {
                    return tagged;
                }
            }
            return AxonValue.object(map);
        }

        /**
         * Reconhece os envelopes marcados de valor tipado: {@code $decimal},
         * {@code $bytes}, {@code $datetime}, {@code $duration}, {@code $uuid},
         * {@code $table} e {@code $record}. É a ponte entre JSON e os tipos
         * AxonQL que o wire protocol não representa como literal JSON.
         */
        private AxonValue parseTagged(Map<String, AxonValue> map) {
            if (map.get("$decimal") != null && map.get("$decimal").isString()) {
                try {
                    return AxonValue.num(new BigDecimal(map.get("$decimal").asString()));
                } catch (NumberFormatException e) {
                    throw decodeError(Messages.get("json_invalid_decimal"));
                }
            }
            if (map.get("$bytes") != null && map.get("$bytes").isString()) {
                try {
                    return AxonValue.bytes(Base64.getDecoder().decode(map.get("$bytes").asString()));
                } catch (IllegalArgumentException e) {
                    throw decodeError(Messages.get("json_invalid_base64"));
                }
            }
            if (map.get("$datetime") != null && map.get("$datetime").isString()) {
                try {
                    return AxonValue.datetime(Instant.parse(map.get("$datetime").asString()));
                } catch (java.time.format.DateTimeParseException e) {
                    throw decodeError(Messages.get("json_invalid_datetime"));
                }
            }
            if (map.get("$duration") != null && map.get("$duration").isString()) {
                try {
                    return AxonValue.duration(durationFromString(map.get("$duration").asString()));
                } catch (IllegalArgumentException e) {
                    throw decodeError(Messages.get("value_duration_invalid", e.getMessage()));
                }
            }
            if (map.get("$uuid") != null && map.get("$uuid").isString()) {
                try {
                    return AxonValue.uuid(UUID.fromString(map.get("$uuid").asString()));
                } catch (IllegalArgumentException e) {
                    throw decodeError(Messages.get("json_invalid_uuid"));
                }
            }
            if (map.get("$table") != null && map.get("$table").isString()) {
                return AxonValue.table(map.get("$table").asString());
            }
            if (map.get("$record") != null && map.get("$record").isString()) {
                String raw = map.get("$record").asString();
                int i = raw.indexOf(':');
                if (i <= 0 || i == raw.length() - 1) {
                    throw decodeError(Messages.get("json_invalid_record", raw));
                }
                return AxonValue.record(raw.substring(0, i), raw.substring(i + 1));
            }
            return null;
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
                throw decodeError(Messages.get("json_expected_array_separator"));
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
                throw decodeError(Messages.get("json_invalid_number_empty"));
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
                throw decodeError(Messages.get("json_invalid_number", token));
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
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                            throw decodeError(Messages.get("json_truncated_unicode_escape"));
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                pos += 4;
                            } catch (NumberFormatException e) {
                                throw decodeError(Messages.get("json_invalid_unicode_escape"));
                            }
                        }
                        default -> throw decodeError(Messages.get("json_invalid_escape", esc));
                    }
                    continue;
                }
                sb.append(c);
            }
            throw decodeError(Messages.get("json_unterminated_string"));
        }

        AxonValue parseKeyword(String kw, AxonValue v) {
            if (s.startsWith(kw, pos)) {
                pos += kw.length();
                return v;
            }
            throw decodeError(Messages.get("json_invalid_literal"));
        }

        char peek() {
            if (eof()) {
                return '\0';
            }
            return s.charAt(pos);
        }

        char next() {
            if (eof()) {
                throw decodeError(Messages.get("json_unexpected_end"));
            }
            return s.charAt(pos++);
        }

        void expect(char c) {
            char got = next();
            if (got != c) {
                throw decodeError(Messages.get("json_expected_character", c, got));
            }
        }
    }
}
