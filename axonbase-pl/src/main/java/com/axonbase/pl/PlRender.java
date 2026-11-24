package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.List;
import java.util.Map;

/** Renderizacao curta de valores para depuradores, com limite de tamanho, profundidade e itens. */
public final class PlRender {
    /** Limite padrao de caracteres por valor. */
    public static final int MAX_CHARS = 200;
    /** Limite padrao de aninhamento. */
    public static final int MAX_PROFUNDIDADE = 3;
    private static final int MAX_ITENS = 20;

    private PlRender() {
    }

    /** Renderiza com os limites padrao. */
    public static String valor(AxonValue v) {
        return valor(v, MAX_CHARS, MAX_PROFUNDIDADE);
    }

    /** Renderiza o valor em no maximo {@code maxChars} caracteres (mais as reticencias). */
    public static String valor(AxonValue v, int maxChars, int maxProf) {
        StringBuilder sb = new StringBuilder();
        escreve(sb, v, maxChars, maxProf);
        if (sb.length() > maxChars) {
            sb.setLength(maxChars);
            sb.append("...");
        }
        return sb.toString();
    }

    /** Nome curto do tipo do valor. */
    public static String tipo(AxonValue v) {
        if (v == null || v.isNull() || v.isNone()) {
            return "None";
        }
        if (v.isBool()) {
            return "bool";
        }
        if (v.isNumber()) {
            return v.isInteger() ? "int" : "float";
        }
        if (v.isString()) {
            return "str";
        }
        if (v.isArray()) {
            return "list";
        }
        if (v.isObject()) {
            return "dict";
        }
        return v.type().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void escreve(StringBuilder sb, AxonValue v, int maxChars, int prof) {
        if (sb.length() > maxChars) {
            return;
        }
        if (v == null || v.isNull() || v.isNone()) {
            sb.append("None");
        } else if (v.isBool()) {
            sb.append(v.asBool() ? "True" : "False");
        } else if (v.isString()) {
            sb.append('\'').append(v.asString().replace("\n", "\\n")).append('\'');
        } else if (v.isArray() || v.isSet()) {
            List<AxonValue> l = v.isArray() ? v.asArray() : v.asSet();
            if (prof <= 0) {
                sb.append("[... ").append(l.size()).append(" itens]");
                return;
            }
            sb.append('[');
            int i = 0;
            for (AxonValue e : l) {
                if (i > 0) {
                    sb.append(", ");
                }
                if (i++ >= MAX_ITENS || sb.length() > maxChars) {
                    sb.append("... ").append(l.size()).append(" itens");
                    break;
                }
                escreve(sb, e, maxChars, prof - 1);
            }
            sb.append(']');
        } else if (v.isObject()) {
            Map<String, AxonValue> m = v.asObject();
            if (prof <= 0) {
                sb.append("{... ").append(m.size()).append(" chaves}");
                return;
            }
            sb.append('{');
            int i = 0;
            for (Map.Entry<String, AxonValue> e : m.entrySet()) {
                if (i > 0) {
                    sb.append(", ");
                }
                if (i++ >= MAX_ITENS || sb.length() > maxChars) {
                    sb.append("... ").append(m.size()).append(" chaves");
                    break;
                }
                sb.append('\'').append(e.getKey()).append("': ");
                escreve(sb, e.getValue(), maxChars, prof - 1);
            }
            sb.append('}');
        } else {
            sb.append(v);
        }
    }
}
