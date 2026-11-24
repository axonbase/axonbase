package com.axonbase.pl.rt;

import com.axonbase.pl.PlCallable;
import com.axonbase.pl.PlError;
import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Funcao do host exposta ao programa (por exemplo db.query). */
public final class HostFn extends PyCallable {
    private final PlCallable fn;
    private final String modulo;
    private final String nome;

    public HostFn(PlCallable fn, String modulo, String nome) {
        this.fn = fn;
        this.modulo = modulo;
        this.nome = nome;
    }

    @Override
    public String nome() {
        return modulo + "." + nome;
    }

    @Override
    public Object call(Frame caller, Object[] args, Map<String, Object> kw) {
        List<AxonValue> a = new ArrayList<>(args.length);
        for (Object o : args) {
            a.add(Convert.paraAxon(o));
        }
        Map<String, AxonValue> k = new LinkedHashMap<>();
        if (kw != null) {
            for (Map.Entry<String, Object> e : kw.entrySet()) {
                k.put(e.getKey(), Convert.paraAxon(e.getValue()));
            }
        }
        Ctx ctx = caller.ctx;
        if (ctx.hook != null) {
            ctx.hook.onHostCall(caller, modulo, nome, a, k);
        }
        AxonValue r;
        try {
            r = fn.call(a, k);
        } catch (PlError e) {
            throw e;
        } catch (RuntimeException e) {
            throw Errors.runtime("falha na chamada ao host " + modulo + "." + nome + ": " + e.getMessage());
        }
        return r == null ? NoneType.NONE : Convert.deAxon(r);
    }
}
