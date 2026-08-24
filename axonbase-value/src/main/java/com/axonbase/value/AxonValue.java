package com.axonbase.value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Valor dinámico central de AxonBase.
 * <p>
 * Modela os tipos de dato da linguaxe AxonQL: {@code none}, {@code null},
 * {@code bool}, {@code number} (int/float/decimal), {@code string},
 * {@code duration}, {@code datetime}, {@code uuid}, {@code array},
 * {@code set}, {@code object}, {@code bytes}, {@code table},
 * {@code record} e {@code range}.
 * <p>
 * A implementación é unha clase selada que delega en {@link AxonType},
 * inmutable e cunha orde total definida.
 */
public final class AxonValue implements Comparable<AxonValue> {

    /**
     * Tipo do valor. Orde total por tipo: None &lt; Null &lt; Bool &lt; Number
     * &lt; String &lt; Duration &lt; Datetime &lt; Uuid &lt; Array &lt; Set
     * &lt; Object &lt; Bytes &lt; RecordId &lt; Table.
     */
    public enum AxonType {
        NONE, NULL, BOOL, NUMBER, STRING, DURATION, DATETIME, UUID,
        ARRAY, SET, OBJECT, BYTES, RECORD_ID, TABLE
    }

    private final AxonType type;
    private final Object value;

    private AxonValue(AxonType type, Object value) {
        this.type = type;
        this.value = value;
    }

    // ------------------------------------------------------------------
    // Tipos primitivos
    // ------------------------------------------------------------------

    public static AxonValue none() {
        return new AxonValue(AxonType.NONE, null);
    }

    public static AxonValue nul() {
        return new AxonValue(AxonType.NULL, null);
    }

    public static AxonValue bool(boolean b) {
        return new AxonValue(AxonType.BOOL, b);
    }

    public static AxonValue num(long n) {
        return new AxonValue(AxonType.NUMBER, n);
    }

    public static AxonValue num(double d) {
        return new AxonValue(AxonType.NUMBER, d);
    }

    public static AxonValue num(BigDecimal dec) {
        return new AxonValue(AxonType.NUMBER, dec);
    }

    public static AxonValue str(String s) {
        if (s == null) {
            throw new NullPointerException("string value cannot be null");
        }
        return new AxonValue(AxonType.STRING, s);
    }

    public static AxonValue uuid(UUID u) {
        if (u == null) {
            throw new NullPointerException("uuid value cannot be null");
        }
        return new AxonValue(AxonType.UUID, u);
    }

    public static AxonValue datetime(Instant t) {
        if (t == null) {
            throw new NullPointerException("datetime value cannot be null");
        }
        return new AxonValue(AxonType.DATETIME, t);
    }

    public static AxonValue duration(long millis) {
        return new AxonValue(AxonType.DURATION, new Duration(millis));
    }

    public static AxonValue array(List<AxonValue> arr) {
        return new AxonValue(AxonType.ARRAY, List.copyOf(arr));
    }

    public static AxonValue set(List<AxonValue> arr) {
        return new AxonValue(AxonType.SET, List.copyOf(arr));
    }

    public static AxonValue object(Map<String, AxonValue> obj) {
        return new AxonValue(AxonType.OBJECT, Collections.unmodifiableMap(new LinkedHashMap<>(obj)));
    }

    public static AxonValue bytes(byte[] data) {
        return new AxonValue(AxonType.BYTES, data.clone());
    }

    public static AxonValue table(String name) {
        return new AxonValue(AxonType.TABLE, name);
    }

    public static AxonValue record(String table, Object key) {
        return new AxonValue(AxonType.RECORD_ID, new RecordId(table, key));
    }

    // ------------------------------------------------------------------
    // Accesores
    // ------------------------------------------------------------------

    public AxonType type() {
        return type;
    }

    public Object raw() {
        return value;
    }

    /**
     * Codificación estable do key para claves de almacenamento. Diferencia
     * strings de números e evita a ambigüidade de representación con comillas.
     */
    public String keyString() {
        return switch (type) {
            case NUMBER -> "n" + asDecimal().stripTrailingZeros().toPlainString();
            case STRING -> "s" + asString();
            default -> "x" + toString();
        };
    }

    public UUID asUuidValue() {
        require(AxonType.UUID);
        return (UUID) value;
    }

    public Instant asInstant() {
        require(AxonType.DATETIME);
        return (Instant) value;
    }

    public Duration asDuration() {
        require(AxonType.DURATION);
        return (Duration) value;
    }

    public boolean isNone() {
        return type == AxonType.NONE;
    }

    public boolean isNull() {
        return type == AxonType.NULL;
    }

    public boolean isNumber() {
        return type == AxonType.NUMBER;
    }

    public boolean isInteger() {
        return type == AxonType.NUMBER && value instanceof Long;
    }

    public boolean isString() {
        return type == AxonType.STRING;
    }

    public boolean isArray() {
        return type == AxonType.ARRAY;
    }

    public boolean isObject() {
        return type == AxonType.OBJECT;
    }

    public boolean isRecordId() {
        return type == AxonType.RECORD_ID;
    }

    public boolean isBool() {
        return type == AxonType.BOOL;
    }

    public boolean isDatetime() {
        return type == AxonType.DATETIME;
    }

    // ------------------------------------------------------------------
    // Conversión a tipos concretos
    // ------------------------------------------------------------------

    public boolean asBool() {
        require(AxonType.BOOL);
        return (Boolean) value;
    }

    public String asString() {
        require(AxonType.STRING);
        return (String) value;
    }

    public long asLong() {
        require(AxonType.NUMBER);
        return ((Number) value).longValue();
    }

    public double asDouble() {
        require(AxonType.NUMBER);
        return ((Number) value).doubleValue();
    }

    public BigDecimal asDecimal() {
        require(AxonType.NUMBER);
        Number n = (Number) value;
        if (n instanceof BigDecimal dec) {
            return dec;
        }
        if (n instanceof Double d) {
            return BigDecimal.valueOf(d);
        }
        return BigDecimal.valueOf(n.longValue());
    }

    @SuppressWarnings("unchecked")
    public List<AxonValue> asArray() {
        require(AxonType.ARRAY);
        return (List<AxonValue>) value;
    }

    public boolean isSet() {
        return type == AxonType.SET;
    }

    @SuppressWarnings("unchecked")
    public List<AxonValue> asSet() {
        require(AxonType.SET);
        return (List<AxonValue>) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, AxonValue> asObject() {
        require(AxonType.OBJECT);
        return (Map<String, AxonValue>) value;
    }

    public RecordId asRecordId() {
        require(AxonType.RECORD_ID);
        return (RecordId) value;
    }

    public String asTable() {
        require(AxonType.TABLE);
        return (String) value;
    }

    private void require(AxonType t) {
        if (type != t) {
            throw new IllegalStateException("esperado " + t + " pero era " + type);
        }
    }

    // ------------------------------------------------------------------
    // Identidade do record
    // ------------------------------------------------------------------

    /** Identidade de record: unha táboa e unha clave. */
    public record RecordId(String table, Object key) {
        public RecordId {
            if (table == null || table.isBlank()) {
                throw new IllegalArgumentException("táboa do record id non pode estar en branco");
            }
            if (key == null) {
                throw new IllegalArgumentException("clave do record id non pode ser null");
            }
        }

        @Override
        public String toString() {
            return table + ":" + renderKey(key);
        }
    }

    /** Converte a clave do record a forma simples sen comillas. */
    private static String renderKey(Object key) {
        if (key instanceof AxonValue kv) {
            return switch (kv.type()) {
                case STRING -> kv.asString();
                case NUMBER -> kv.isInteger()
                    ? Long.toString(kv.asLong())
                    : Double.toString(kv.asDouble());
                case UUID -> kv.asUuidValue().toString();
                default -> kv.toString();
            };
        }
        return String.valueOf(key);
    }

    // ------------------------------------------------------------------
    // equals / hashCode / compareTo
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AxonValue other)) {
            return false;
        }
        return type == other.type && comparablePayload().equals(other.comparablePayloadTuple());
    }

    /** Igualdade de carga útil en forma comparable. */
    private Object comparablePayload() {
        switch (type) {
            case NUMBER -> {
                return normalizeNumber((Number) value);
            }
            case ARRAY, SET -> {
                return List.copyOf(((List<AxonValue>) value));
            }
            case OBJECT -> {
                return normalizeObject((Map<String, AxonValue>) value);
            }
            default -> {
                return value;
            }
        }
    }

    private Object comparablePayloadTuple() {
        if (type == AxonType.NUMBER) {
            return normalizeNumber((Number) value);
        }
        if (type == AxonType.ARRAY || type == AxonType.SET) {
            return List.copyOf((List<AxonValue>) value);
        }
        if (type == AxonType.OBJECT) {
            return normalizeObject((Map<String, AxonValue>) value);
        }
        return value;
    }

    private static Object normalizeNumber(Number n) {
        if (n instanceof BigDecimal dec) {
            return dec.stripTrailingZeros();
        }
        if (n instanceof Double d) {
            return BigDecimal.valueOf(d).stripTrailingZeros();
        }
        return BigDecimal.valueOf(n.longValue()).stripTrailingZeros();
    }

    private static Map<String, AxonValue> normalizeObject(Map<String, AxonValue> m) {
        return new LinkedHashMap<>(m);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + comparablePayload().hashCode();
    }

    @Override
    public int compareTo(AxonValue o) {
        if (type != o.type) {
            return type.ordinal() - o.type.ordinal();
        }
        return switch (type) {
            case NONE, NULL -> 0;
            case BOOL -> Boolean.compare(asBool(), o.asBool());
            case NUMBER -> asDecimal().compareTo(o.asDecimal());
            case STRING -> asString().compareTo(o.asString());
            case UUID -> asUuidValue().compareTo(o.asUuidValue());
            case DATETIME -> asInstant().compareTo(o.asInstant());
            case DURATION -> asDuration().compareTo(o.asDuration());
            case TABLE -> asTable().compareTo(o.asTable());
            case RECORD_ID -> asRecordId().toString().compareTo(o.asRecordId().toString());
            case ARRAY, SET -> compareLists(asListMe(), asList(o));
            case OBJECT -> compareObjects(asObject(), o.asObject());
            case BYTES -> java.util.Arrays.compare((byte[]) value, (byte[]) o.value);
            default -> 0;
        };
    }

    private List<AxonValue> asListMe() {
        return (List<AxonValue>) value;
    }

    private List<AxonValue> asList(AxonValue o) {
        return (List<AxonValue>) o.value;
    }

    private static int compareLists(List<AxonValue> a, List<AxonValue> b) {
        int len = Math.min(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareObjects(Map<String, AxonValue> a, Map<String, AxonValue> b) {
        if (a.size() != b.size()) {
            return Integer.compare(a.size(), b.size());
        }
        for (Map.Entry<String, AxonValue> e : a.entrySet()) {
            AxonValue bv = b.get(e.getKey());
            if (bv == null) {
                return 1;
            }
            int c = e.getValue().compareTo(bv);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Serialización string (estilo AxonQL)
    // ------------------------------------------------------------------

    @Override
    public String toString() {
        return switch (type) {
            case NONE -> "NONE";
            case NULL -> "null";
            case BOOL -> Boolean.toString((Boolean) value);
            case NUMBER -> ((Number) value).toString();
            case STRING -> quote((String) value);
            case UUID -> ((UUID) value).toString();
            case DATETIME -> "d'" + ((Instant) value).toString() + "'";
            case DURATION -> ((Duration) value).toString();
            case ARRAY, SET -> stringifySeq((List<AxonValue>) value);
            case OBJECT -> stringifyObject((Map<String, AxonValue>) value);
            case BYTES -> "b'" + java.util.HexFormat.of().formatHex((byte[]) value) + "'";
            case TABLE -> ((String) value);
            case RECORD_ID -> value.toString();
        };
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String stringifySeq(List<AxonValue> seq) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < seq.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(seq.get(i));
        }
        return sb.append(']').toString();
    }

    private static String stringifyObject(Map<String, AxonValue> obj) {
        StringBuilder sb = new StringBuilder("{");
        var it = obj.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            sb.append('"').append(e.getKey()).append("\": ").append(e.getValue());
            if (it.hasNext()) {
                sb.append(", ");
            }
        }
        return sb.append('}').toString();
    }

    /**
     * Value interno de duración: millisegundos e unidades. Simple para o MVP.
     */
    public record Duration(long millis) implements Comparable<Duration> {
        @Override
        public String toString() {
            return millis + "ms";
        }

        @Override
        public int compareTo(Duration o) {
            return Long.compare(millis, o.millis());
        }
    }
}