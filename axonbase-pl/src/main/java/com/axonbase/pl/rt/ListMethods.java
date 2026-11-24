package com.axonbase.pl.rt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Metodos de list e tuple. */
public final class ListMethods {
    private ListMethods() {
    }

    public static final Map<String, Method> L = new HashMap<>();
    public static final Map<String, Method> TUP = new HashMap<>();

    private static void def(String n, int min, int max, Method m) {
        L.put(n, (f, self, a, kw) -> {
            Args.noKw(n, kw);
            Args.n("list." + n, a, min, max);
            return m.call(f, self, a, kw);
        });
    }

    private static ArrayList<Object> l(Object o) {
        return ((PyList) o).a;
    }

    static {
        def("append", 1, 1, (f, x, a, k) -> {
            ArrayList<Object> v = l(x);
            f.ctx.checkElems(v.size() + 1L);
            v.add(a[0]);
            return NoneType.NONE;
        });
        def("extend", 1, 1, (f, x, a, k) -> {
            List<Object> items = Ops.toList(f, a[0]);
            f.ctx.checkElems((long) l(x).size() + items.size());
            l(x).addAll(items);
            return NoneType.NONE;
        });
        def("insert", 2, 2, (f, x, a, k) -> {
            ArrayList<Object> v = l(x);
            f.ctx.checkElems(v.size() + 1L);
            long i = Ops.index(a[0]);
            if (i < 0) {
                i = Math.max(0, i + v.size());
            }
            v.add((int) Math.min(i, v.size()), a[1]);
            return NoneType.NONE;
        });
        def("pop", 0, 1, (f, x, a, k) -> {
            ArrayList<Object> v = l(x);
            if (v.isEmpty()) {
                throw Errors.index("pop de lista vazia");
            }
            long i = a.length > 0 ? Ops.index(a[0]) : -1;
            if (i < 0) {
                i += v.size();
            }
            if (i < 0 || i >= v.size()) {
                throw Errors.index("indice do pop fora do intervalo");
            }
            return v.remove((int) i);
        });
        def("remove", 1, 1, (f, x, a, k) -> {
            ArrayList<Object> v = l(x);
            for (int i = 0; i < v.size(); i++) {
                if (Ops.eq(v.get(i), a[0])) {
                    v.remove(i);
                    return NoneType.NONE;
                }
            }
            throw Errors.value("list.remove(x): x nao esta na lista");
        });
        def("index", 1, 3, (f, x, a, k) -> {
            ArrayList<Object> v = l(x);
            int st = a.length > 1 ? (int) Math.max(0, Ops.index(a[1])) : 0;
            int en = a.length > 2 ? (int) Math.min(v.size(), Ops.index(a[2])) : v.size();
            for (int i = st; i < en; i++) {
                if (Ops.eq(v.get(i), a[0])) {
                    return (long) i;
                }
            }
            throw Errors.value("o valor nao esta na lista");
        });
        def("count", 1, 1, (f, x, a, k) -> {
            long c = 0;
            for (Object o : l(x)) {
                if (Ops.eq(o, a[0])) {
                    c++;
                }
            }
            return c;
        });
        L.put("sort", (f, x, a, kw) -> {
            if (a.length > 0) {
                throw Errors.type("list.sort() nao aceita argumentos posicionais");
            }
            Args.onlyKw("sort", kw, "key", "reverse");
            Object key = Args.kw(kw, "key");
            Object rev = Args.kw(kw, "reverse");
            Sorting.sort(f, l(x), key, rev != null && Ops.truthy(rev));
            return NoneType.NONE;
        });
        def("reverse", 0, 0, (f, x, a, k) -> {
            java.util.Collections.reverse(l(x));
            return NoneType.NONE;
        });
        def("copy", 0, 0, (f, x, a, k) -> new PyList(l(x)));
        def("clear", 0, 0, (f, x, a, k) -> {
            l(x).clear();
            return NoneType.NONE;
        });

        TUP.put("index", (f, x, a, kw) -> {
            Args.n("tuple.index", a, 1, 1);
            Object[] t = ((PyTuple) x).a;
            for (int i = 0; i < t.length; i++) {
                if (Ops.eq(t[i], a[0])) {
                    return (long) i;
                }
            }
            throw Errors.value("o valor nao esta na tupla");
        });
        TUP.put("count", (f, x, a, kw) -> {
            Args.n("tuple.count", a, 1, 1);
            long c = 0;
            for (Object o : ((PyTuple) x).a) {
                if (Ops.eq(o, a[0])) {
                    c++;
                }
            }
            return c;
        });
    }
}
