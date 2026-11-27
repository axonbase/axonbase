package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Como {@code SET} num caminho, {@code MERGE} e {@code PATCH} mudam um documento. Nada é alterado no lugar: cada
 * mudança devolve um valor novo, então uma operação recusada não deixa o registro pela metade.
 */
final class RecordChanges {

    private RecordChanges() {
    }

    /**
     * Grava {@code value} no caminho. Cada passo é um nome de campo ({@code String}) ou uma posição de lista
     * ({@code Integer}). Objetos e listas que faltam são criados; a posição logo depois da última acrescenta.
     */
    static AxonValue set(AxonValue current, List<Object> path, AxonValue value) {
        return set(current, path, 0, value);
    }

    private static AxonValue set(AxonValue current, List<Object> path, int at, AxonValue value) {
        if (at == path.size()) {
            return value;
        }
        boolean absent = current == null || current.isNull() || current.isNone();
        if (path.get(at) instanceof String name) {
            if (!absent && !current.isObject()) {
                throw refused("stmt_path_not_object", describe(path, at));
            }
            Map<String, AxonValue> copy = new LinkedHashMap<>(absent ? Map.of() : current.asObject());
            copy.put(name, set(copy.get(name), path, at + 1, value));
            return AxonValue.object(copy);
        }
        int index = (Integer) path.get(at);
        if (!absent && !current.isArray()) {
            throw refused("stmt_path_not_array", describe(path, at));
        }
        List<AxonValue> copy = new ArrayList<>(absent ? List.of() : current.asArray());
        if (index < 0 || index > copy.size()) {
            throw refused("stmt_path_index", describe(path, at + 1));
        }
        AxonValue next = set(index < copy.size() ? copy.get(index) : null, path, at + 1, value);
        if (index == copy.size()) {
            copy.add(next);
        } else {
            copy.set(index, next);
        }
        return AxonValue.array(copy);
    }

    private static String describe(List<Object> path, int length) {
        StringBuilder text = new StringBuilder();
        for (Object step : path.subList(0, length)) {
            if (step instanceof Integer index) {
                text.append('[').append(index).append(']');
            } else {
                text.append(text.length() == 0 ? "" : ".").append(step);
            }
        }
        return text.toString();
    }

    /** Junta {@code changes} em {@code base}: objetos se juntam em qualquer profundidade, o resto é substituído. */
    static Map<String, AxonValue> merge(Map<String, AxonValue> base, Map<String, AxonValue> changes) {
        Map<String, AxonValue> out = new LinkedHashMap<>(base);
        changes.forEach((name, value) -> {
            AxonValue existing = out.get(name);
            out.put(name, existing != null && existing.isObject() && value.isObject()
                ? AxonValue.object(merge(existing.asObject(), value.asObject()))
                : value);
        });
        return out;
    }

    /**
     * JSON Patch (RFC 6902): {@code add}, {@code remove}, {@code replace}, {@code move}, {@code copy} e {@code test},
     * na ordem da lista, com caminhos JSON Pointer. A primeira operação que não puder ser feita recusa todas.
     */
    static AxonValue patch(AxonValue document, List<AxonValue> operations) {
        AxonValue result = document;
        for (AxonValue operation : operations) {
            result = apply(result, operation);
        }
        if (!result.isObject()) {
            throw refused("stmt_patch_invalid", result);
        }
        return result;
    }

    private static AxonValue apply(AxonValue document, AxonValue operation) {
        if (!operation.isObject()) {
            throw refused("stmt_patch_invalid", operation);
        }
        Map<String, AxonValue> fields = operation.asObject();
        String op = text(fields, "op", operation);
        List<String> path = pointer(text(fields, "path", operation), operation);
        return switch (op) {
            case "add" -> add(document, path, required(fields, "value", operation), operation);
            case "remove" -> remove(document, path, operation);
            case "replace" -> {
                AxonValue value = required(fields, "value", operation);
                yield path.isEmpty() ? value
                    : add(remove(document, path, operation), path, value, operation);
            }
            case "move" -> {
                List<String> from = pointer(text(fields, "from", operation), operation);
                if (path.size() > from.size() && path.subList(0, from.size()).equals(from)) {
                    throw refused("stmt_patch_invalid", operation);
                }
                AxonValue value = get(document, from, operation);
                yield from.equals(path) ? document
                    : add(remove(document, from, operation), path, value, operation);
            }
            case "copy" -> add(document, path,
                get(document, pointer(text(fields, "from", operation), operation), operation), operation);
            case "test" -> {
                if (!get(document, path, operation).equals(required(fields, "value", operation))) {
                    throw refused("stmt_patch_test_failed", operation);
                }
                yield document;
            }
            default -> throw refused("stmt_patch_invalid", operation);
        };
    }

    private static AxonValue required(Map<String, AxonValue> fields, String name, AxonValue operation) {
        AxonValue value = fields.get(name);
        if (value == null) {
            throw refused("stmt_patch_invalid", operation);
        }
        return value;
    }

    private static String text(Map<String, AxonValue> fields, String name, AxonValue operation) {
        AxonValue value = required(fields, name, operation);
        if (!value.isString()) {
            throw refused("stmt_patch_invalid", operation);
        }
        return value.asString();
    }

    private static List<String> pointer(String text, AxonValue operation) {
        if (text.isEmpty()) {
            return List.of();
        }
        if (!text.startsWith("/")) {
            throw refused("stmt_patch_invalid", operation);
        }
        List<String> steps = new ArrayList<>();
        for (String step : text.substring(1).split("/", -1)) {
            steps.add(step.replace("~1", "/").replace("~0", "~"));
        }
        return steps;
    }

    private static AxonValue get(AxonValue document, List<String> path, AxonValue operation) {
        AxonValue current = document;
        for (String step : path) {
            current = child(current, step, operation);
        }
        return current;
    }

    /** O filho que existe; um caminho que não leva a nada recusa a operação. */
    private static AxonValue child(AxonValue parent, String step, AxonValue operation) {
        AxonValue found = null;
        if (parent.isObject()) {
            found = parent.asObject().get(step);
        } else if (parent.isArray()) {
            int index = index(step, parent.asArray().size() - 1, operation);
            found = parent.asArray().get(index);
        }
        if (found == null) {
            throw refused("stmt_patch_path_missing", operation);
        }
        return found;
    }

    private static int index(String step, int highest, AxonValue operation) {
        if (!step.matches("0|[1-9][0-9]{0,8}") || Integer.parseInt(step) > highest) {
            throw refused("stmt_patch_path_missing", operation);
        }
        return Integer.parseInt(step);
    }

    private static AxonValue add(AxonValue document, List<String> path, AxonValue value, AxonValue operation) {
        if (path.isEmpty()) {
            return value;
        }
        String step = path.get(0);
        boolean last = path.size() == 1;
        List<String> rest = path.subList(1, path.size());
        if (document.isObject()) {
            Map<String, AxonValue> copy = new LinkedHashMap<>(document.asObject());
            copy.put(step, last ? value : add(child(document, step, operation), rest, value, operation));
            return AxonValue.object(copy);
        }
        if (document.isArray()) {
            List<AxonValue> copy = new ArrayList<>(document.asArray());
            if (last) {
                copy.add("-".equals(step) ? copy.size() : index(step, copy.size(), operation), value);
            } else {
                int at = index(step, copy.size() - 1, operation);
                copy.set(at, add(copy.get(at), rest, value, operation));
            }
            return AxonValue.array(copy);
        }
        throw refused("stmt_patch_path_missing", operation);
    }

    private static AxonValue remove(AxonValue document, List<String> path, AxonValue operation) {
        if (path.isEmpty()) {
            throw refused("stmt_patch_invalid", operation);
        }
        String step = path.get(0);
        AxonValue existing = child(document, step, operation);
        List<String> rest = path.subList(1, path.size());
        if (document.isObject()) {
            Map<String, AxonValue> copy = new LinkedHashMap<>(document.asObject());
            if (rest.isEmpty()) {
                copy.remove(step);
            } else {
                copy.put(step, remove(existing, rest, operation));
            }
            return AxonValue.object(copy);
        }
        List<AxonValue> copy = new ArrayList<>(document.asArray());
        int at = Integer.parseInt(step);
        if (rest.isEmpty()) {
            copy.remove(at);
        } else {
            copy.set(at, remove(existing, rest, operation));
        }
        return AxonValue.array(copy);
    }

    private static AxonError refused(String message, Object detail) {
        return AxonError.internal(Messages.get(message, detail));
    }
}
