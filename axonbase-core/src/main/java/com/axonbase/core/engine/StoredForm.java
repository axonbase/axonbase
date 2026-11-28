package com.axonbase.core.engine;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * As formas de um mesmo valor gravado. Alguns tipos mudam na ida e volta pelo disco: um record link é um id na
 * memória e volta como texto, por exemplo. Chaves derivadas do valor precisam ser comparadas na forma relida, e as
 * entradas que versões anteriores derivaram do valor em memória continuam valendo.
 */
final class StoredForm {

    /** Texto com cara de record link, {@code tabela:chave}. Aspas ficam de fora: {@code d'...'} é uma data. */
    private static final Pattern RECORD_LINK = Pattern.compile("[\\p{L}\\p{N}_\\-]+:.+", Pattern.DOTALL);

    private StoredForm() {
    }

    /** O valor como volta do disco. */
    static AxonValue of(AxonValue value) {
        // duas voltas: um objeto {"$record": ...} vira link na primeira e texto na segunda
        return roundTrip(roundTrip(value));
    }

    /** O valor relido com os textos de record link de volta a links, como eram na memória de quem os gravou. */
    static AxonValue withLinks(AxonValue stored) {
        if (stored.isString()) {
            String text = stored.asString();
            if (!RECORD_LINK.matcher(text).matches()) {
                return stored;
            }
            int colon = text.indexOf(':');
            return AxonValue.record(text.substring(0, colon), text.substring(colon + 1));
        }
        if (stored.isArray()) {
            return AxonValue.array(stored.asArray().stream().map(StoredForm::withLinks).toList());
        }
        if (stored.isObject()) {
            Map<String, AxonValue> out = new LinkedHashMap<>();
            stored.asObject().forEach((key, value) -> out.put(key, withLinks(value)));
            return AxonValue.object(out);
        }
        return stored;
    }

    /** O registro com a conversão aplicada só às colunas dadas; o que não é objeto volta como veio. */
    static AxonValue columns(AxonValue record, List<String> columns, UnaryOperator<AxonValue> conversion) {
        if (record == null || !record.isObject()) {
            return record;
        }
        Map<String, AxonValue> out = new LinkedHashMap<>(record.asObject());
        for (String column : columns) {
            out.computeIfPresent(column, (key, value) -> conversion.apply(value));
        }
        return AxonValue.object(out);
    }

    private static AxonValue roundTrip(AxonValue value) {
        return AxonJson.decode(AxonJson.encode(value));
    }
}
