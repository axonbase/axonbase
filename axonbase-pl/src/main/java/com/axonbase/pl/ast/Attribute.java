package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Attrs;
import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.Types;

/** Acesso a atributo obj.nome. */
public final class Attribute extends Expr implements Target {
    public final Expr obj;
    public final String name;

    public Attribute(Expr obj, String name) {
        this.obj = obj;
        this.name = name;
    }

    @Override
    public Object eval(Frame f) {
        return Attrs.getattr(f, obj.eval(f), name);
    }

    @Override
    public void assign(Frame f, Object v) {
        Object o = obj.eval(f);
        throw Errors.attr("o objeto do tipo '" + Types.nomeDe(o) + "' nao permite atribuir o atributo '" + name + "'");
    }

    @Override
    public void delete(Frame f) {
        throw Errors.attr("nao e possivel remover o atributo '" + name + "'");
    }
}
