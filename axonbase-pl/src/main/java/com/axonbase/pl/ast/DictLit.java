package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.PyDict;
import com.axonbase.pl.rt.Types;
import java.util.Map;

/** Literal de dict. Chave nula indica {**mapa}. */
public final class DictLit extends Expr {
    private final Expr[] keys;
    private final Expr[] vals;

    public DictLit(Expr[] keys, Expr[] vals) {
        this.keys = keys;
        this.vals = vals;
    }

    @Override
    public Object eval(Frame f) {
        PyDict d = new PyDict();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null) {
                Object m = vals[i].eval(f);
                if (!(m instanceof PyDict md)) {
                    throw Errors.type("o objeto do tipo '" + Types.nomeDe(m) + "' nao e um mapeamento para **");
                }
                for (Map.Entry<Object, Object> e : md.m.entrySet()) {
                    d.m.put(e.getKey(), e.getValue());
                }
            } else {
                Object k = keys[i].eval(f);
                d.put(k, vals[i].eval(f));
            }
        }
        f.ctx.checkElems(d.m.size());
        return d;
    }
}
