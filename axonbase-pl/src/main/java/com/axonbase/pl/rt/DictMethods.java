package com.axonbase.pl.rt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Metodos de dict e set. */
public final class DictMethods {
    private DictMethods() {
    }

    public static final Map<String, Method> D = new HashMap<>();
    public static final Map<String, Method> S = new HashMap<>();

    private static void def(Map<String, Method> t, String pre, String n, int min, int max, Method m) {
        t.put(n, (f, self, a, kw) -> {
            Args.noKw(n, kw);
            Args.n(pre + "." + n, a, min, max);
            return m.call(f, self, a, kw);
        });
    }

    private static Map<Object, Object> d(Object o) {
        return ((PyDict) o).m;
    }

    private static java.util.LinkedHashSet<Object> s(Object o) {
        return ((PySet) o).s;
    }

    private static void updateFrom(Frame f, PyDict dst, Object src) {
        if (src instanceof PyDict sd) {
            dst.m.putAll(sd.m);
            return;
        }
        for (Object item : Ops.toList(f, src)) {
            List<Object> pair = Ops.toList(f, item);
            if (pair.size() != 2) {
                throw Errors.value("cada item de update deve ser um par chave/valor");
            }
            dst.put(pair.get(0), pair.get(1));
        }
    }

    private static PySet others(Frame f, PySet self, Object[] a) {
        PySet r = new PySet(self.frozen);
        r.s.addAll(self.s);
        return r;
    }

    private static java.util.Collection<Object> coll(Frame f, Object o) {
        if (o instanceof PySet ps) {
            return ps.s;
        }
        return Ops.toList(f, o);
    }

    static {
        D.put("get", (f, x, a, kw) -> {
            Args.noKw("get", kw);
            Args.n("dict.get", a, 1, 2);
            Ops.checkHashable(a[0]);
            Object v = d(x).get(a[0]);
            return v != null ? v : a.length > 1 ? a[1] : NoneType.NONE;
        });
        def(D, "dict", "keys", 0, 0, (f, x, a, k) -> new PyList(d(x).keySet()));
        def(D, "dict", "values", 0, 0, (f, x, a, k) -> new PyList(d(x).values()));
        def(D, "dict", "items", 0, 0, (f, x, a, k) -> {
            PyList r = new PyList(d(x).size());
            for (Map.Entry<Object, Object> e : d(x).entrySet()) {
                r.a.add(new PyTuple(new Object[] {e.getKey(), e.getValue()}));
            }
            return r;
        });
        D.put("update", (f, x, a, kw) -> {
            Args.n("dict.update", a, 0, 1);
            PyDict dd = (PyDict) x;
            if (a.length == 1) {
                updateFrom(f, dd, a[0]);
            }
            if (kw != null) {
                dd.m.putAll(kw);
            }
            f.ctx.checkElems(dd.m.size());
            return NoneType.NONE;
        });
        def(D, "dict", "pop", 1, 2, (f, x, a, k) -> {
            Ops.checkHashable(a[0]);
            Object v = d(x).remove(a[0]);
            if (v != null) {
                return v;
            }
            if (a.length > 1) {
                return a[1];
            }
            throw Errors.key(a[0]);
        });
        def(D, "dict", "popitem", 0, 0, (f, x, a, k) -> {
            if (d(x).isEmpty()) {
                throw Errors.key("popitem(): dicionario vazio");
            }
            Object last = null;
            for (Object kk : d(x).keySet()) {
                last = kk;
            }
            Object v = d(x).remove(last);
            return new PyTuple(new Object[] {last, v});
        });
        def(D, "dict", "setdefault", 1, 2, (f, x, a, k) -> {
            Ops.checkHashable(a[0]);
            Object v = d(x).get(a[0]);
            if (v == null) {
                v = a.length > 1 ? a[1] : NoneType.NONE;
                d(x).put(a[0], v);
            }
            return v;
        });
        def(D, "dict", "copy", 0, 0, (f, x, a, k) -> {
            PyDict r = new PyDict();
            r.m.putAll(d(x));
            return r;
        });
        def(D, "dict", "clear", 0, 0, (f, x, a, k) -> {
            d(x).clear();
            return NoneType.NONE;
        });

        def(S, "set", "add", 1, 1, (f, x, a, k) -> {
            frozenCheck(x);
            f.ctx.checkElems(s(x).size() + 1L);
            ((PySet) x).add(a[0]);
            return NoneType.NONE;
        });
        def(S, "set", "remove", 1, 1, (f, x, a, k) -> {
            frozenCheck(x);
            Ops.checkHashable(a[0]);
            if (!s(x).remove(a[0])) {
                throw Errors.key(a[0]);
            }
            return NoneType.NONE;
        });
        def(S, "set", "discard", 1, 1, (f, x, a, k) -> {
            frozenCheck(x);
            Ops.checkHashable(a[0]);
            s(x).remove(a[0]);
            return NoneType.NONE;
        });
        def(S, "set", "pop", 0, 0, (f, x, a, k) -> {
            frozenCheck(x);
            if (s(x).isEmpty()) {
                throw Errors.key("pop de conjunto vazio");
            }
            Object first = s(x).iterator().next();
            s(x).remove(first);
            return first;
        });
        S.put("union", (f, x, a, kw) -> {
            PySet r = others(f, (PySet) x, a);
            for (Object o : a) {
                for (Object e : coll(f, o)) {
                    r.add(e);
                }
            }
            return r;
        });
        S.put("intersection", (f, x, a, kw) -> {
            PySet r = others(f, (PySet) x, a);
            for (Object o : a) {
                java.util.Collection<Object> c = coll(f, o);
                r.s.removeIf(e -> !containsEq(c, e));
            }
            return r;
        });
        S.put("difference", (f, x, a, kw) -> {
            PySet r = others(f, (PySet) x, a);
            for (Object o : a) {
                java.util.Collection<Object> c = coll(f, o);
                r.s.removeIf(e -> containsEq(c, e));
            }
            return r;
        });
        def(S, "set", "symmetric_difference", 1, 1, (f, x, a, k) -> {
            PySet other = new PySet(false);
            for (Object e : coll(f, a[0])) {
                other.add(e);
            }
            return Ops.binary(f, Ops.BITXOR, x, other);
        });
        def(S, "set", "issubset", 1, 1, (f, x, a, k) -> {
            java.util.Collection<Object> c = coll(f, a[0]);
            return s(x).stream().allMatch(e -> containsEq(c, e));
        });
        def(S, "set", "issuperset", 1, 1, (f, x, a, k) -> {
            java.util.Collection<Object> c = coll(f, a[0]);
            return c.stream().allMatch(e -> s(x).contains(e));
        });
        def(S, "set", "isdisjoint", 1, 1, (f, x, a, k) -> {
            java.util.Collection<Object> c = coll(f, a[0]);
            return s(x).stream().noneMatch(e -> containsEq(c, e));
        });
        S.put("update", (f, x, a, kw) -> {
            frozenCheck(x);
            for (Object o : a) {
                for (Object e : coll(f, o)) {
                    ((PySet) x).add(e);
                }
            }
            f.ctx.checkElems(s(x).size());
            return NoneType.NONE;
        });
        def(S, "set", "intersection_update", 1, 1, (f, x, a, k) -> {
            frozenCheck(x);
            java.util.Collection<Object> c = coll(f, a[0]);
            s(x).removeIf(e -> !containsEq(c, e));
            return NoneType.NONE;
        });
        def(S, "set", "difference_update", 1, 1, (f, x, a, k) -> {
            frozenCheck(x);
            java.util.Collection<Object> c = coll(f, a[0]);
            s(x).removeIf(e -> containsEq(c, e));
            return NoneType.NONE;
        });
        def(S, "set", "clear", 0, 0, (f, x, a, k) -> {
            frozenCheck(x);
            s(x).clear();
            return NoneType.NONE;
        });
        def(S, "set", "copy", 0, 0, (f, x, a, k) -> others(f, (PySet) x, a));
    }

    private static boolean containsEq(java.util.Collection<Object> c, Object e) {
        if (c instanceof java.util.Set) {
            return c.contains(e);
        }
        for (Object o : c) {
            if (Ops.eq(o, e)) {
                return true;
            }
        }
        return false;
    }

    private static void frozenCheck(Object x) {
        if (((PySet) x).frozen) {
            throw Errors.attr("o objeto 'frozenset' nao pode ser modificado");
        }
    }

    static ArrayList<Object> unused() {
        return null;
    }
}
