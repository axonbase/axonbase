package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;
import com.axonbase.pl.rt.PyList;
import java.util.List;

/** Alvo de desempacotamento: a, b = ...; a, *resto = ... (aninhavel). */
public final class TupleTarget implements Target {
    private final Target[] items;
    private final int star;

    public TupleTarget(Target[] items, int star) {
        this.items = items;
        this.star = star;
    }

    @Override
    public void assign(Frame f, Object v) {
        List<Object> vals = Ops.toList(f, v);
        int n = items.length;
        if (star < 0) {
            if (vals.size() > n) {
                throw Errors.value("valores demais para desempacotar (esperado " + n + ")");
            }
            if (vals.size() < n) {
                throw Errors.value("nao ha valores suficientes para desempacotar (esperado " + n + ", recebeu "
                        + vals.size() + ")");
            }
            for (int i = 0; i < n; i++) {
                items[i].assign(f, vals.get(i));
            }
            return;
        }
        int before = star;
        int after = n - star - 1;
        if (vals.size() < before + after) {
            throw Errors.value("nao ha valores suficientes para desempacotar (esperado pelo menos " + (before + after)
                    + ", recebeu " + vals.size() + ")");
        }
        for (int i = 0; i < before; i++) {
            items[i].assign(f, vals.get(i));
        }
        PyList mid = new PyList(vals.subList(before, vals.size() - after));
        items[star].assign(f, mid);
        for (int i = 0; i < after; i++) {
            items[star + 1 + i].assign(f, vals.get(vals.size() - after + i));
        }
    }

    @Override
    public void delete(Frame f) {
        for (Target t : items) {
            t.delete(f);
        }
    }
}
