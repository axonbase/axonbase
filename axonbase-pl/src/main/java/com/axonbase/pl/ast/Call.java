package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.PyCallable;
import com.axonbase.pl.rt.Types;
import java.util.Map;

/** Chamada de funcao f(args). */
public final class Call extends CallBase {
    private final Expr fn;

    public Call(Expr fn, Expr[] args, String[] kwNames, Expr[] kwVals) {
        super(args, kwNames, kwVals);
        this.fn = fn;
    }

    @Override
    public Object eval(Frame f) {
        Object fv = fn.eval(f);
        Object[] a = evalArgs(f);
        Map<String, Object> kw = evalKw(f);
        return invoke(f, fv, a, kw);
    }

    /** Chama qualquer valor chamavel. */
    public static Object invoke(Frame f, Object fv, Object[] a, Map<String, Object> kw) {
        if (fv instanceof PyCallable c) {
            return c.call(f, a, kw);
        }
        throw Errors.type("o objeto do tipo '" + Types.nomeDe(fv) + "' nao e chamavel");
    }
}
