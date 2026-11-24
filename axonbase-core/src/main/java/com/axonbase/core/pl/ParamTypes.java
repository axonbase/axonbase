package com.axonbase.core.pl;

import com.axonbase.common.AxonError;
import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tipos de parâmetro de função (documentais, mas validados e convertidos na chamada). */
final class ParamTypes {

    private static final Set<String> KNOWN = Set.of("int", "integer", "float", "number", "decimal", "string", "str",
        "bool", "boolean", "datetime", "array", "list", "object", "dict", "record", "any", "uuid", "duration",
        "bytes", "geometry", "vector", "set");

    private ParamTypes() {
    }

    /** Nome base do tipo, sem {@code <...>}, em minúsculas. */
    static String base(String type) {
        int lt = type.indexOf('<');
        return (lt < 0 ? type : type.substring(0, lt)).toLowerCase(Locale.ROOT);
    }

    static boolean known(String type) {
        return KNOWN.contains(base(type));
    }

    /** Valida e converte o argumento. Nulo e none passam sem checagem. */
    static AxonValue coerce(String function, String param, String type, AxonValue v) {
        if (type == null || v == null || v.isNull() || v.isNone()) {
            return v;
        }
        try {
            AxonValue r = convert(base(type), v);
            if (r != null) {
                return r;
            }
        } catch (RuntimeException invalid) {
            // cai no erro padrão abaixo
        }
        throw new AxonError(-32040, "argumento '" + param + "' da funcao '" + function + "' deve ser " + type
            + ", mas recebeu " + describe(v));
    }

    private static AxonValue convert(String type, AxonValue v) {
        switch (type) {
            case "int", "integer" -> {
                if (v.isInteger()) {
                    return v;
                }
                if (v.isNumber()) {
                    BigDecimal d = v.asDecimal();
                    return d.stripTrailingZeros().scale() <= 0 ? AxonValue.num(d.longValueExact()) : null;
                }
                if (v.isString()) {
                    return AxonValue.num(Long.parseLong(v.asString().trim()));
                }
                return null;
            }
            case "float" -> {
                if (v.isNumber()) {
                    return v.raw() instanceof Double ? v : AxonValue.num(v.asDouble());
                }
                return v.isString() ? AxonValue.num(Double.parseDouble(v.asString().trim())) : null;
            }
            case "number" -> {
                if (v.isNumber()) {
                    return v;
                }
                return v.isString() ? AxonValue.num(new BigDecimal(v.asString().trim())) : null;
            }
            case "decimal" -> {
                if (v.isNumber()) {
                    return v.raw() instanceof BigDecimal ? v : AxonValue.num(v.asDecimal());
                }
                return v.isString() ? AxonValue.num(new BigDecimal(v.asString().trim())) : null;
            }
            case "string", "str" -> {
                if (v.isString()) {
                    return v;
                }
                if (v.isNumber() || v.isBool() || v.isUuid() || v.isDatetime() || v.isRecordId()) {
                    return AxonValue.str(v.isNumber() && v.raw() instanceof BigDecimal d ? d.toPlainString()
                        : String.valueOf(v.raw() instanceof AxonValue.RecordId r ? r.toString() : v.raw()));
                }
                return null;
            }
            case "bool", "boolean" -> {
                if (v.isBool()) {
                    return v;
                }
                if (v.isString()) {
                    String s = v.asString().trim().toLowerCase(Locale.ROOT);
                    return s.equals("true") ? AxonValue.bool(true) : s.equals("false") ? AxonValue.bool(false) : null;
                }
                if (v.isInteger() && (v.asLong() == 0 || v.asLong() == 1)) {
                    return AxonValue.bool(v.asLong() == 1);
                }
                return null;
            }
            case "datetime" -> {
                if (v.isDatetime()) {
                    return v;
                }
                return v.isString() ? AxonValue.datetime(Instant.parse(v.asString().trim())) : null;
            }
            case "array", "list" -> {
                if (v.isArray()) {
                    return v;
                }
                return v.isSet() ? AxonValue.array(List.copyOf(v.asSet())) : null;
            }
            case "set" -> {
                return v.isSet() ? v : v.isArray() ? AxonValue.set(v.asArray()) : null;
            }
            case "object", "dict" -> {
                return v.isObject() ? v : null;
            }
            case "record" -> {
                if (v.isRecordId()) {
                    return v;
                }
                if (v.isString() && v.asString().indexOf(':') > 0) {
                    String s = v.asString();
                    int c = s.indexOf(':');
                    return AxonValue.record(s.substring(0, c), AxonValue.str(s.substring(c + 1)));
                }
                return null;
            }
            case "uuid" -> {
                return v.isUuid() ? v : v.isString() ? AxonValue.uuid(java.util.UUID.fromString(v.asString())) : null;
            }
            case "duration" -> {
                return v.isDuration() ? v : null;
            }
            case "bytes" -> {
                return v.isBytes() ? v : null;
            }
            default -> {
                return v; // any, geometry, vector
            }
        }
    }

    /** Descrição curta do valor recebido, para a mensagem de erro. */
    static String describe(AxonValue v) {
        String s = v.toString();
        if (s.length() > 40) {
            s = s.substring(0, 37) + "...";
        }
        return v.type().name().toLowerCase(Locale.ROOT) + " " + s;
    }
}
