package com.axonbase.core.control;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec JSON determinístico dos comandos de controle.
 *
 * <p>O snapshot inteiro cabe em uma chave do KV, o que é o que permite replicá-lo
 * dentro do mesmo batch atômico das escritas de dados: o seguidor recebe DDL e
 * registros na mesma entrada de log.</p>
 */
public final class CatalogCodec {

    private CatalogCodec() {
    }

    public static String encode(ControlSnapshot snapshot) {
        List<AxonValue> out = new ArrayList<>();
        for (ControlCommand command : snapshot.commands()) {
            Map<String, AxonValue> fields = new LinkedHashMap<>();
            fields.put("kind", AxonValue.str(command.kind().name()));
            fields.put("ns", AxonValue.str(command.namespace()));
            fields.put("db", AxonValue.str(command.database()));
            fields.put("name", AxonValue.str(command.name()));
            fields.put("definition", AxonValue.str(command.definition()));
            out.add(AxonValue.object(fields));
        }
        return AxonJson.write(AxonValue.array(out));
    }

    public static ControlSnapshot decode(String json) {
        List<ControlCommand> out = new ArrayList<>();
        AxonValue parsed = AxonJson.parseDocument(json);
        if (!parsed.isArray()) {
            return ControlSnapshot.empty();
        }
        for (AxonValue entry : parsed.asArray()) {
            Map<String, AxonValue> fields = entry.asObject();
            ControlCommand.Kind kind = kindOf(fields.get("kind"));
            if (kind == null) {
                // Um nó mais novo pode ter escrito um tipo que este ainda não conhece;
                // ignorar é melhor que abortar o arranque com o catálogo pela metade.
                continue;
            }
            out.add(new ControlCommand(kind, text(fields.get("ns")), text(fields.get("db")),
                text(fields.get("name")), text(fields.get("definition"))));
        }
        return new ControlSnapshot(out);
    }

    private static ControlCommand.Kind kindOf(AxonValue value) {
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return ControlCommand.Kind.valueOf(value.asString());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String text(AxonValue value) {
        return value == null || !value.isString() ? "" : value.asString();
    }
}
