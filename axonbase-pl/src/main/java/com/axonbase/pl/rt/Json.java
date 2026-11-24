package com.axonbase.pl.rt;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Modulo json: dumps e loads. */
final class Json {
    private Json() {
    }

    static PyModule module() {
        PyModule m = new PyModule("json");
        m.def("dumps", new Builtin("json.dumps", (f, a, kw) -> {
            Args.n("json.dumps", a, 1, 1);
            Args.onlyKw("json.dumps", kw, "indent", "sort_keys", "ensure_ascii", "separators");
            Object ind = Args.kw(kw, "indent");
            int indent = ind == null || ind instanceof NoneType ? -1 : (int) Ops.index(ind);
            boolean sort = kw != null && Ops.truthy(kw.getOrDefault("sort_keys", false));
            boolean ascii = kw == null || Ops.truthy(kw.getOrDefault("ensure_ascii", true));
            String itemSep = indent >= 0 ? "," : ", ";
            String keySep = ": ";
            Object seps = Args.kw(kw, "separators");
            if (seps instanceof PyTuple t && t.a.length == 2) {
                itemSep = Repr.str(t.a[0]);
                keySep = Repr.str(t.a[1]);
            }
            StringBuilder sb = new StringBuilder();
            write(f, sb, a[0], indent, 0, sort, ascii, itemSep, keySep);
            f.ctx.checkStr(sb.length());
            return sb.toString();
        }));
        m.def("loads", new Builtin("json.loads", (f, a, kw) -> {
            Args.n("json.loads", a, 1, 1);
            Args.noKw("json.loads", kw);
            return new Parser(Args.str(a[0], "json.loads")).parseAll();
        }));
        return m;
    }

    private static void quote(StringBuilder sb, String s, boolean ascii) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || (ascii && c > 126)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void nl(StringBuilder sb, int indent, int level) {
        if (indent >= 0) {
            sb.append('\n').append(" ".repeat(indent * level));
        }
    }

    private static void write(Frame f, StringBuilder sb, Object o, int indent, int level, boolean sort, boolean ascii,
                              String itemSep, String keySep) {
        if (level > 100) {
            throw Errors.value("estrutura profunda demais para json.dumps");
        }
        if (o instanceof NoneType) {
            sb.append("null");
        } else if (o instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (o instanceof Long l) {
            sb.append(l);
        } else if (o instanceof Double d) {
            sb.append(Double.isNaN(d) ? "NaN" : Double.isInfinite(d) ? (d > 0 ? "Infinity" : "-Infinity")
                    : Repr.floatRepr(d));
        } else if (o instanceof String s) {
            quote(sb, s, ascii);
        } else if (o instanceof PyList || o instanceof PyTuple) {
            List<Object> items = o instanceof PyList l ? l.a : java.util.Arrays.asList(((PyTuple) o).a);
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(itemSep);
                }
                nl(sb, indent, level + 1);
                write(f, sb, items.get(i), indent, level + 1, sort, ascii, itemSep, keySep);
            }
            nl(sb, indent, level);
            sb.append(']');
        } else if (o instanceof PyDict d) {
            if (d.m.isEmpty()) {
                sb.append("{}");
                return;
            }
            List<Map.Entry<Object, Object>> es = new ArrayList<>(d.m.entrySet());
            List<String> keys = new ArrayList<>();
            for (Map.Entry<Object, Object> e : es) {
                Object k = e.getKey();
                if (k instanceof String s) {
                    keys.add(s);
                } else if (k instanceof Long || k instanceof Boolean || k instanceof NoneType || k instanceof Double) {
                    keys.add(k instanceof Boolean b ? (b ? "true" : "false") : k instanceof NoneType ? "null" : Repr.str(k));
                } else {
                    throw Errors.type("as chaves de json devem ser texto, numero, bool ou None");
                }
            }
            Integer[] order = new Integer[keys.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            if (sort) {
                java.util.Arrays.sort(order, (x, y) -> keys.get(x).compareTo(keys.get(y)));
            }
            sb.append('{');
            boolean first = true;
            for (int idx : order) {
                if (!first) {
                    sb.append(itemSep);
                }
                first = false;
                nl(sb, indent, level + 1);
                quote(sb, keys.get(idx), ascii);
                sb.append(keySep);
                write(f, sb, es.get(idx).getValue(), indent, level + 1, sort, ascii, itemSep, keySep);
            }
            nl(sb, indent, level);
            sb.append('}');
        } else {
            throw Errors.type("o objeto do tipo '" + Types.nomeDe(o) + "' nao e serializavel em JSON");
        }
    }

    private static final class Parser {
        private final String s;
        private int p;

        Parser(String s) {
            this.s = s;
        }

        RuntimeException err(String m) {
            return Errors.value("JSON invalido: " + m + " (posicao " + p + ")");
        }

        void ws() {
            while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\n' || s.charAt(p) == '\t' || s.charAt(p) == '\r')) {
                p++;
            }
        }

        Object parseAll() {
            ws();
            Object v = value(0);
            ws();
            if (p < s.length()) {
                throw err("texto sobrando depois do valor");
            }
            return v;
        }

        Object value(int depth) {
            if (depth > 200) {
                throw err("aninhamento profundo demais");
            }
            ws();
            if (p >= s.length()) {
                throw err("fim inesperado");
            }
            char c = s.charAt(p);
            if (c == '{') {
                p++;
                PyDict d = new PyDict();
                ws();
                if (p < s.length() && s.charAt(p) == '}') {
                    p++;
                    return d;
                }
                while (true) {
                    ws();
                    if (p >= s.length() || s.charAt(p) != '"') {
                        throw err("chave de objeto esperada");
                    }
                    String k = str();
                    ws();
                    if (p >= s.length() || s.charAt(p) != ':') {
                        throw err("':' esperado");
                    }
                    p++;
                    d.m.put(k, value(depth + 1));
                    ws();
                    if (p < s.length() && s.charAt(p) == ',') {
                        p++;
                        continue;
                    }
                    if (p < s.length() && s.charAt(p) == '}') {
                        p++;
                        return d;
                    }
                    throw err("',' ou '}' esperado");
                }
            }
            if (c == '[') {
                p++;
                PyList l = new PyList();
                ws();
                if (p < s.length() && s.charAt(p) == ']') {
                    p++;
                    return l;
                }
                while (true) {
                    l.a.add(value(depth + 1));
                    ws();
                    if (p < s.length() && s.charAt(p) == ',') {
                        p++;
                        continue;
                    }
                    if (p < s.length() && s.charAt(p) == ']') {
                        p++;
                        return l;
                    }
                    throw err("',' ou ']' esperado");
                }
            }
            if (c == '"') {
                return str();
            }
            if (s.startsWith("true", p)) {
                p += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", p)) {
                p += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", p)) {
                p += 4;
                return NoneType.NONE;
            }
            if (s.startsWith("NaN", p)) {
                p += 3;
                return Double.NaN;
            }
            if (s.startsWith("Infinity", p)) {
                p += 8;
                return Double.POSITIVE_INFINITY;
            }
            if (s.startsWith("-Infinity", p)) {
                p += 9;
                return Double.NEGATIVE_INFINITY;
            }
            int st = p;
            if (c == '-') {
                p++;
            }
            while (p < s.length() && Character.isDigit(s.charAt(p))) {
                p++;
            }
            boolean fl = false;
            if (p < s.length() && s.charAt(p) == '.') {
                fl = true;
                p++;
                while (p < s.length() && Character.isDigit(s.charAt(p))) {
                    p++;
                }
            }
            if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
                fl = true;
                p++;
                if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
                    p++;
                }
                while (p < s.length() && Character.isDigit(s.charAt(p))) {
                    p++;
                }
            }
            String num = s.substring(st, p);
            if (num.isEmpty() || num.equals("-")) {
                throw err("valor inesperado");
            }
            if (fl) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw Errors.overflow64();
            }
        }

        String str() {
            p++;
            StringBuilder sb = new StringBuilder();
            while (p < s.length()) {
                char c = s.charAt(p++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (p >= s.length()) {
                        break;
                    }
                    char e = s.charAt(p++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case '/' -> sb.append('/');
                        case '\\' -> sb.append('\\');
                        case '"' -> sb.append('"');
                        case 'u' -> {
                            if (p + 4 > s.length()) {
                                throw err("escape unicode incompleto");
                            }
                            sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                            p += 4;
                        }
                        default -> throw err("escape invalido");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("string nao terminada");
        }
    }

    static BigDecimal unused() {
        return null;
    }
}
