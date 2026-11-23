package com.axonbase.server;

import com.axonbase.value.AxonValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Montagem de respostas JSON da API de gestão a partir de tipos Java comuns. */
final class AdminJson {

    private AdminJson() {
    }

    /** Converte String, números, booleanos, null, listas, mapas e AxonValue em AxonValue. */
    static AxonValue of(Object o) {
        if (o == null) {
            return AxonValue.nul();
        }
        if (o instanceof AxonValue v) {
            return v;
        }
        if (o instanceof String s) {
            return AxonValue.str(s);
        }
        if (o instanceof Boolean b) {
            return AxonValue.bool(b);
        }
        if (o instanceof Double || o instanceof Float) {
            return AxonValue.num(((Number) o).doubleValue());
        }
        if (o instanceof Number n) {
            return AxonValue.num(n.longValue());
        }
        if (o instanceof List<?> list) {
            List<AxonValue> out = new ArrayList<>();
            list.forEach(e -> out.add(of(e)));
            return AxonValue.array(out);
        }
        if (o instanceof Map<?, ?> map) {
            Map<String, AxonValue> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), of(v)));
            return AxonValue.object(out);
        }
        return AxonValue.str(String.valueOf(o));
    }

    /** Objeto a partir de pares chave, valor. */
    static AxonValue obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return of(m);
    }

    /** Instante RFC 3339 em UTC, ou null quando o valor é zero. */
    static String iso(long epochMillis) {
        return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis).toString();
    }
}
