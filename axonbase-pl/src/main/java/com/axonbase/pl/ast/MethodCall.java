package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Attrs;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Method;
import java.util.Map;

/** Chamada de metodo obj.nome(args). */
public final class MethodCall extends CallBase {
    private final Expr obj;
    private final String name;

    public MethodCall(Expr obj, String name, Expr[] args, String[] kwNames, Expr[] kwVals) {
        super(args, kwNames, kwVals);
        this.obj = obj;
        this.name = name;
    }

    @Override
    public Object eval(Frame f) {
        Object o = obj.eval(f);
        Method m = Attrs.method(o, name);
        if (m != null) {
            Object[] a = evalArgs(f);
            Map<String, Object> kw = evalKw(f);
            return m.call(f, o, a, kw);
        }
        Object fv = Attrs.getattr(f, o, name);
        Object[] a = evalArgs(f);
        Map<String, Object> kw = evalKw(f);
        return Call.invoke(f, fv, a, kw);
    }
}
