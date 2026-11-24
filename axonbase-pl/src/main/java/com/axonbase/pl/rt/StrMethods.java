package com.axonbase.pl.rt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Metodos de str. */
public final class StrMethods {
    private StrMethods() {
    }

    public static final Map<String, Method> T = new HashMap<>();

    private static String s(Object o) {
        return (String) o;
    }

    private static void def(String n, int min, int max, Method m) {
        T.put(n, (f, self, a, kw) -> {
            Args.noKw(n, kw);
            Args.n("str." + n, a, min, max);
            return m.call(f, self, a, kw);
        });
    }

    private static String stripWith(String x, Object chars, boolean left, boolean right) {
        int b = 0;
        int e = x.length();
        String cs = chars == null || chars instanceof NoneType ? null : Args.str(chars, "strip");
        if (left) {
            while (b < e && (cs == null ? Args.isPyWs(x.charAt(b)) : cs.indexOf(x.charAt(b)) >= 0)) {
                b++;
            }
        }
        if (right) {
            while (e > b && (cs == null ? Args.isPyWs(x.charAt(e - 1)) : cs.indexOf(x.charAt(e - 1)) >= 0)) {
                e--;
            }
        }
        return x.substring(b, e);
    }

    private static int[] range(Object[] a, int from, int len) {
        long st = 0;
        long en = len;
        if (a.length > from && !(a[from] instanceof NoneType)) {
            st = Ops.index(a[from]);
        }
        if (a.length > from + 1 && !(a[from + 1] instanceof NoneType)) {
            en = Ops.index(a[from + 1]);
        }
        if (st < 0) {
            st = Math.max(0, st + len);
        }
        if (en < 0) {
            en = Math.max(0, en + len);
        }
        en = Math.min(en, len);
        return new int[] {(int) Math.min(st, len + 1L), (int) en};
    }

    private static List<Object> split(String x, Object sepArg, long maxsplit, boolean right) {
        List<Object> out = new ArrayList<>();
        if (sepArg == null || sepArg instanceof NoneType) {
            int n = x.length();
            if (!right) {
                int i = 0;
                long cnt = 0;
                while (true) {
                    while (i < n && Args.isPyWs(x.charAt(i))) {
                        i++;
                    }
                    if (i >= n) {
                        break;
                    }
                    if (maxsplit >= 0 && cnt >= maxsplit) {
                        out.add(x.substring(i));
                        break;
                    }
                    int j = i;
                    while (j < n && !Args.isPyWs(x.charAt(j))) {
                        j++;
                    }
                    out.add(x.substring(i, j));
                    cnt++;
                    i = j;
                }
            } else {
                int i = n;
                long cnt = 0;
                List<Object> rev = new ArrayList<>();
                while (true) {
                    while (i > 0 && Args.isPyWs(x.charAt(i - 1))) {
                        i--;
                    }
                    if (i <= 0) {
                        break;
                    }
                    if (maxsplit >= 0 && cnt >= maxsplit) {
                        rev.add(x.substring(0, i));
                        break;
                    }
                    int j = i;
                    while (j > 0 && !Args.isPyWs(x.charAt(j - 1))) {
                        j--;
                    }
                    rev.add(x.substring(j, i));
                    cnt++;
                    i = j;
                }
                for (int k = rev.size() - 1; k >= 0; k--) {
                    out.add(rev.get(k));
                }
            }
            return out;
        }
        String sep = Args.str(sepArg, "split");
        if (sep.isEmpty()) {
            throw Errors.value("separador vazio");
        }
        if (!right) {
            int i = 0;
            long cnt = 0;
            while (true) {
                int j = (maxsplit >= 0 && cnt >= maxsplit) ? -1 : x.indexOf(sep, i);
                if (j < 0) {
                    out.add(x.substring(i));
                    break;
                }
                out.add(x.substring(i, j));
                i = j + sep.length();
                cnt++;
            }
        } else {
            int end = x.length();
            long cnt = 0;
            List<Object> rev = new ArrayList<>();
            while (true) {
                int j = (maxsplit >= 0 && cnt >= maxsplit) ? -1 : x.lastIndexOf(sep, end - sep.length());
                if (j < 0 || end - sep.length() < 0) {
                    rev.add(x.substring(0, end));
                    break;
                }
                rev.add(x.substring(j + sep.length(), end));
                end = j;
                cnt++;
            }
            for (int k = rev.size() - 1; k >= 0; k--) {
                out.add(rev.get(k));
            }
        }
        return out;
    }

    private static Method splitter(boolean right) {
        return (f, self, a, kw) -> {
            Args.onlyKw("split", kw, "sep", "maxsplit");
            Object sep = a.length > 0 ? a[0] : Args.kw(kw, "sep");
            Object ms = a.length > 1 ? a[1] : Args.kw(kw, "maxsplit");
            long m = ms == null ? -1 : Ops.index(ms);
            return new PyList(split(s(self), sep, m, right));
        };
    }

    private static String center(String x, long w, String fill, int mode) {
        if (fill.length() != 1) {
            throw Errors.type("o caractere de preenchimento deve ter exatamente um caractere");
        }
        long pad = w - x.length();
        if (pad <= 0) {
            return x;
        }
        if (w > 100_000_000) {
            throw Errors.limite(com.axonbase.pl.PlLimitError.Tipo.MEMORIA, "largura grande demais");
        }
        long left;
        if (mode == 0) {
            left = 0;
        } else if (mode == 1) {
            left = pad;
        } else {
            left = pad / 2 + (pad & w & 1);
        }
        return fill.repeat((int) left) + x + fill.repeat((int) (pad - left));
    }

    private static String title(String x) {
        StringBuilder sb = new StringBuilder(x.length());
        boolean prev = false;
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            boolean letter = Character.isLetter(c);
            sb.append(letter ? (prev ? Character.toLowerCase(c) : Character.toUpperCase(c)) : c);
            prev = letter;
        }
        return sb.toString();
    }

    private static boolean tests(String x, java.util.function.IntPredicate p, boolean emptyOk) {
        if (x.isEmpty()) {
            return emptyOk;
        }
        for (int i = 0; i < x.length(); i++) {
            if (!p.test(x.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Object startEnd(Object self, Object[] a, boolean start) {
        String x = s(self);
        int[] r = range(a, 1, x.length());
        String sub = x.substring(Math.min(r[0], x.length()), Math.max(Math.min(r[0], x.length()), r[1]));
        Object p = a[0];
        if (p instanceof PyTuple t) {
            for (Object o : t.a) {
                if (start ? sub.startsWith(Args.str(o, "startswith")) : sub.endsWith(Args.str(o, "endswith"))) {
                    return true;
                }
            }
            return false;
        }
        String ps = Args.str(p, start ? "startswith" : "endswith");
        return start ? sub.startsWith(ps) : sub.endsWith(ps);
    }

    static {
        def("upper", 0, 0, (f, x, a, k) -> s(x).toUpperCase());
        def("lower", 0, 0, (f, x, a, k) -> s(x).toLowerCase());
        def("casefold", 0, 0, (f, x, a, k) -> s(x).toLowerCase());
        def("swapcase", 0, 0, (f, x, a, k) -> {
            StringBuilder sb = new StringBuilder();
            for (char c : s(x).toCharArray()) {
                sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
            }
            return sb.toString();
        });
        def("strip", 0, 1, (f, x, a, k) -> stripWith(s(x), a.length > 0 ? a[0] : null, true, true));
        def("lstrip", 0, 1, (f, x, a, k) -> stripWith(s(x), a.length > 0 ? a[0] : null, true, false));
        def("rstrip", 0, 1, (f, x, a, k) -> stripWith(s(x), a.length > 0 ? a[0] : null, false, true));
        T.put("split", splitter(false));
        T.put("rsplit", splitter(true));
        def("join", 1, 1, (f, x, a, k) -> {
            List<Object> items = Ops.toList(f, a[0]);
            StringBuilder sb = new StringBuilder();
            String sep = s(x);
            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof String it)) {
                    throw Errors.type("item " + i + " da sequencia: era esperado str, mas recebeu '"
                            + Types.nomeDe(items.get(i)) + "'");
                }
                if (i > 0) {
                    sb.append(sep);
                }
                sb.append(it);
                if (sb.length() > f.ctx.maxStr) {
                    f.ctx.checkStr(sb.length());
                }
            }
            return sb.toString();
        });
        def("replace", 2, 3, (f, x, a, k) -> {
            String old = Args.str(a[0], "replace");
            String nw = Args.str(a[1], "replace");
            long count = a.length > 2 ? Ops.index(a[2]) : -1;
            String src = s(x);
            if (count < 0) {
                if (old.isEmpty()) {
                    StringBuilder sb = new StringBuilder(nw);
                    for (int i = 0; i < src.length(); i++) {
                        sb.append(src.charAt(i)).append(nw);
                    }
                    f.ctx.checkStr(sb.length());
                    return sb.toString();
                }
                f.ctx.checkStr((long) src.length() + (long) (nw.length() - old.length()) * Math.max(0, src.length() / old.length()));
                return src.replace(old, nw);
            }
            StringBuilder sb = new StringBuilder();
            int i = 0;
            long done = 0;
            if (old.isEmpty()) {
                while (done < count && i <= src.length()) {
                    sb.append(nw);
                    if (i < src.length()) {
                        sb.append(src.charAt(i));
                    }
                    i++;
                    done++;
                }
                if (i <= src.length()) {
                    sb.append(src.substring(Math.min(i, src.length())));
                }
                return sb.toString();
            }
            while (done < count) {
                int j = src.indexOf(old, i);
                if (j < 0) {
                    break;
                }
                sb.append(src, i, j).append(nw);
                i = j + old.length();
                done++;
            }
            return sb.append(src.substring(i)).toString();
        });
        def("startswith", 1, 3, (f, x, a, k) -> startEnd(x, a, true));
        def("endswith", 1, 3, (f, x, a, k) -> startEnd(x, a, false));
        def("find", 1, 3, (f, x, a, k) -> {
            String src = s(x);
            int[] r = range(a, 1, src.length());
            if (r[0] > src.length()) {
                return -1L;
            }
            String sub = Args.str(a[0], "find");
            int i = src.indexOf(sub, r[0]);
            return i >= 0 && i + sub.length() <= r[1] ? (long) i : -1L;
        });
        def("rfind", 1, 3, (f, x, a, k) -> {
            String src = s(x);
            int[] r = range(a, 1, src.length());
            String sub = Args.str(a[0], "rfind");
            int i = src.lastIndexOf(sub, r[1] - sub.length());
            return i >= r[0] && i + sub.length() <= r[1] ? (long) i : -1L;
        });
        def("index", 1, 3, (f, x, a, k) -> {
            Object r = T.get("find").call(f, x, a, null);
            if ((Long) r < 0) {
                throw Errors.value("substring nao encontrada");
            }
            return r;
        });
        def("rindex", 1, 3, (f, x, a, k) -> {
            Object r = T.get("rfind").call(f, x, a, null);
            if ((Long) r < 0) {
                throw Errors.value("substring nao encontrada");
            }
            return r;
        });
        def("count", 1, 3, (f, x, a, k) -> {
            String src = s(x);
            int[] r = range(a, 1, src.length());
            String sub = Args.str(a[0], "count");
            if (r[0] > src.length() || r[1] < r[0]) {
                return 0L;
            }
            String w = src.substring(r[0], r[1]);
            if (sub.isEmpty()) {
                return (long) w.length() + 1;
            }
            long c = 0;
            int i = 0;
            while ((i = w.indexOf(sub, i)) >= 0) {
                c++;
                i += sub.length();
            }
            return c;
        });
        def("title", 0, 0, (f, x, a, k) -> title(s(x)));
        def("capitalize", 0, 0, (f, x, a, k) -> {
            String v = s(x);
            return v.isEmpty() ? v : Character.toUpperCase(v.charAt(0)) + v.substring(1).toLowerCase();
        });
        def("isdigit", 0, 0, (f, x, a, k) -> tests(s(x), c -> Character.isDigit(c), false));
        def("isdecimal", 0, 0, (f, x, a, k) -> tests(s(x), c -> Character.isDigit(c), false));
        def("isnumeric", 0, 0, (f, x, a, k) -> tests(s(x), c -> Character.isDigit(c), false));
        def("isalpha", 0, 0, (f, x, a, k) -> tests(s(x), c -> Character.isLetter(c), false));
        def("isalnum", 0, 0, (f, x, a, k) -> tests(s(x), c -> Character.isLetterOrDigit(c), false));
        def("isspace", 0, 0, (f, x, a, k) -> tests(s(x), c -> Args.isPyWs((char) c), false));
        def("isupper", 0, 0, (f, x, a, k) -> {
            String v = s(x);
            return v.chars().anyMatch(Character::isLetter) && v.equals(v.toUpperCase());
        });
        def("islower", 0, 0, (f, x, a, k) -> {
            String v = s(x);
            return v.chars().anyMatch(Character::isLetter) && v.equals(v.toLowerCase());
        });
        def("zfill", 1, 1, (f, x, a, k) -> {
            String v = s(x);
            long w = Ops.index(a[0]);
            f.ctx.checkStr(w);
            if (v.length() >= w) {
                return v;
            }
            boolean sign = !v.isEmpty() && (v.charAt(0) == '+' || v.charAt(0) == '-');
            String body = sign ? v.substring(1) : v;
            return (sign ? v.substring(0, 1) : "") + "0".repeat((int) (w - v.length())) + body;
        });
        def("center", 1, 2, (f, x, a, k) -> {
            f.ctx.checkStr(Ops.index(a[0]));
            return center(s(x), Ops.index(a[0]), a.length > 1 ? Args.str(a[1], "center") : " ", 2);
        });
        def("ljust", 1, 2, (f, x, a, k) -> {
            f.ctx.checkStr(Ops.index(a[0]));
            return center(s(x), Ops.index(a[0]), a.length > 1 ? Args.str(a[1], "ljust") : " ", 0);
        });
        def("rjust", 1, 2, (f, x, a, k) -> {
            f.ctx.checkStr(Ops.index(a[0]));
            return center(s(x), Ops.index(a[0]), a.length > 1 ? Args.str(a[1], "rjust") : " ", 1);
        });
        T.put("format", (f, x, a, kw) -> Fmt.strFormat(f, s(x), a, kw));
        def("partition", 1, 1, (f, x, a, k) -> {
            String v = s(x);
            String sep = Args.str(a[0], "partition");
            if (sep.isEmpty()) {
                throw Errors.value("separador vazio");
            }
            int i = v.indexOf(sep);
            return i < 0 ? new PyTuple(new Object[] {v, "", ""})
                    : new PyTuple(new Object[] {v.substring(0, i), sep, v.substring(i + sep.length())});
        });
        def("rpartition", 1, 1, (f, x, a, k) -> {
            String v = s(x);
            String sep = Args.str(a[0], "rpartition");
            if (sep.isEmpty()) {
                throw Errors.value("separador vazio");
            }
            int i = v.lastIndexOf(sep);
            return i < 0 ? new PyTuple(new Object[] {"", "", v})
                    : new PyTuple(new Object[] {v.substring(0, i), sep, v.substring(i + sep.length())});
        });
        T.put("splitlines", (f, x, a, kw) -> {
            boolean keep = a.length > 0 ? Ops.truthy(a[0]) : kw != null && Ops.truthy(kw.getOrDefault("keepends", false));
            String v = s(x);
            PyList out = new PyList();
            int i = 0;
            int n = v.length();
            int start = 0;
            while (i < n) {
                char c = v.charAt(i);
                boolean brk = c == '\n' || c == '\r' || c == '\u000b' || c == '\f' || c == '\u001c' || c == '\u001d'
                        || c == '\u001e' || c == '\u0085' || c == ' ' || c == ' ';
                if (brk) {
                    int end = i;
                    if (c == '\r' && i + 1 < n && v.charAt(i + 1) == '\n') {
                        i++;
                    }
                    i++;
                    out.a.add(keep ? v.substring(start, i) : v.substring(start, end));
                    start = i;
                } else {
                    i++;
                }
            }
            if (start < n) {
                out.a.add(v.substring(start));
            }
            return out;
        });
        def("removeprefix", 1, 1, (f, x, a, k) -> {
            String v = s(x);
            String p = Args.str(a[0], "removeprefix");
            return v.startsWith(p) ? v.substring(p.length()) : v;
        });
        def("removesuffix", 1, 1, (f, x, a, k) -> {
            String v = s(x);
            String p = Args.str(a[0], "removesuffix");
            return !p.isEmpty() && v.endsWith(p) ? v.substring(0, v.length() - p.length()) : v;
        });
    }
}
