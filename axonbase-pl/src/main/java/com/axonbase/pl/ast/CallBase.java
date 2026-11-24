package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Ops;
import com.axonbase.pl.rt.PyDict;
import com.axonbase.pl.rt.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Argumentos de chamada compartilhados por Call e MethodCall. */
public abstract class CallBase extends Expr {
    protected final Expr[] args;
    protected final String[] kwNames;
    protected final Expr[] kwVals;
    private final boolean hasStar;

    protected CallBase(Expr[] args, String[] kwNames, Expr[] kwVals) {
        this.args = args;
        this.kwNames = kwNames;
        this.kwVals = kwVals;
        boolean s = false;
        for (Expr e : args) {
            s |= e instanceof Starred;
        }
        this.hasStar = s;
    }

    protected final Object[] evalArgs(Frame f) {
        int n = args.length;
        if (!hasStar) {
            if (n == 0) {
                return NOARGS;
            }
            Object[] a = new Object[n];
            for (int i = 0; i < n; i++) {
                a[i] = args[i].eval(f);
            }
            return a;
        }
        java.util.ArrayList<Object> acc = new java.util.ArrayList<>();
        for (Expr e : args) {
            if (e instanceof Starred s) {
                List<Object> l = Ops.toList(f, s.e.eval(f));
                acc.addAll(l);
            } else {
                acc.add(e.eval(f));
            }
        }
        return acc.toArray();
    }

    private static final Object[] NOARGS = new Object[0];

    protected final Map<String, Object> evalKw(Frame f) {
        if (kwNames.length == 0) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kwNames.length; i++) {
            if (kwNames[i] == null) {
                Object d = kwVals[i].eval(f);
                if (!(d instanceof PyDict pd)) {
                    throw Errors.type("o argumento de ** deve ser um mapeamento, nao '" + Types.nomeDe(d) + "'");
                }
                for (Map.Entry<Object, Object> e : pd.m.entrySet()) {
                    if (!(e.getKey() instanceof String k)) {
                        throw Errors.type("as chaves de ** devem ser strings");
                    }
                    if (m.containsKey(k)) {
                        throw Errors.type("argumento nomeado repetido: '" + k + "'");
                    }
                    m.put(k, e.getValue());
                }
            } else {
                if (m.containsKey(kwNames[i])) {
                    throw Errors.type("argumento nomeado repetido: '" + kwNames[i] + "'");
                }
                m.put(kwNames[i], kwVals[i].eval(f));
            }
        }
        return m;
    }
}
