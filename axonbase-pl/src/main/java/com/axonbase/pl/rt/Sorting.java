package com.axonbase.pl.rt;

import com.axonbase.pl.ast.Call;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Ordenacao estavel com key e reverse, fiel ao Python. */
public final class Sorting {
    private Sorting() {
    }

    private static final class KV {
        final Object k;
        final Object v;

        KV(Object k, Object v) {
            this.k = k;
            this.v = v;
        }
    }

    static int cmp(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (Ops.compare(Ops.LT, a, b)) {
            return -1;
        }
        return Ops.compare(Ops.LT, b, a) ? 1 : 0;
    }

    /** Ordena a lista no lugar. */
    public static void sort(Frame f, List<Object> items, Object key, boolean reverse) {
        boolean hasKey = key != null && !(key instanceof NoneType);
        if (reverse) {
            Collections.reverse(items);
        }
        try {
            if (hasKey) {
                ArrayList<KV> kvs = new ArrayList<>(items.size());
                int n = 0;
                for (Object o : items) {
                    kvs.add(new KV(Call.invoke(f, key, new Object[] {o}, null), o));
                    if ((++n & 255) == 0) {
                        f.ctx.step();
                    }
                }
                kvs.sort(Comparator.comparing((KV kv) -> kv.k, Sorting::cmp));
                for (int i = 0; i < kvs.size(); i++) {
                    items.set(i, kvs.get(i).v);
                }
            } else {
                items.sort(Sorting::cmp);
            }
        } catch (IllegalArgumentException e) {
            throw Errors.value("comparacao inconsistente durante a ordenacao");
        }
        if (reverse) {
            Collections.reverse(items);
        }
    }
}
