package com.axonbase.pl.ast;

import com.axonbase.pl.rt.Builtins;
import com.axonbase.pl.rt.Cell;
import com.axonbase.pl.rt.Errors;
import com.axonbase.pl.rt.Frame;
import com.axonbase.pl.rt.HostModule;

/** Referencia a um nome; a resolucao (slot local, celula, global ou embutido) e feita no fim da compilacao. */
public final class Name extends Expr implements Target {
    public static final int LOCAL = 0;
    public static final int CELL = 1;
    public static final int GLOBAL = 2;
    public static final int BUILTIN = 3;
    public static final int FREE = 4;

    public final String id;
    public Scope scope;
    public int kind = -1;
    public int slot = -1;
    public Object builtinVal;

    public Name(String id, Scope scope) {
        this.id = id;
        this.scope = scope;
        scope.refs.add(this);
    }

    @Override
    public Object eval(Frame f) {
        Object v;
        switch (kind) {
            case LOCAL:
                v = f.slots[slot];
                if (v == null) {
                    throw unbound();
                }
                return v;
            case CELL:
                v = ((Cell) f.slots[slot]).v;
                if (v == null) {
                    throw unbound();
                }
                return v;
            case GLOBAL:
                v = f.globals[slot];
                if (v == null) {
                    throw Errors.name("o nome '" + id + "' nao esta definido");
                }
                return v;
            default:
                return builtin(f);
        }
    }

    private Object builtin(Frame f) {
        if (builtinVal != null) {
            return builtinVal;
        }
        if (f.ctx.host != null && f.ctx.host.modulosNativos().contains(id)) {
            return f.ctx.modules.computeIfAbsent("host:" + id, k -> new HostModule(id));
        }
        throw Errors.name("o nome '" + id + "' nao esta definido");
    }

    private RuntimeException unbound() {
        return Errors.make(com.axonbase.pl.rt.Types.UNBOUND,
                "a variavel local '" + id + "' foi usada antes de receber um valor");
    }

    @Override
    public void assign(Frame f, Object v) {
        switch (kind) {
            case LOCAL:
                f.slots[slot] = v;
                break;
            case CELL:
                ((Cell) f.slots[slot]).v = v;
                break;
            case GLOBAL:
                f.globals[slot] = v;
                break;
            default:
                throw Errors.name("nao e possivel atribuir ao nome embutido '" + id + "'");
        }
    }

    @Override
    public void delete(Frame f) {
        switch (kind) {
            case LOCAL:
                if (f.slots[slot] == null) {
                    throw unbound();
                }
                f.slots[slot] = null;
                break;
            case CELL:
                Cell c = (Cell) f.slots[slot];
                if (c.v == null) {
                    throw unbound();
                }
                c.v = null;
                break;
            case GLOBAL:
                if (f.globals[slot] == null) {
                    throw Errors.name("o nome '" + id + "' nao esta definido");
                }
                f.globals[slot] = null;
                break;
            default:
                throw Errors.name("o nome '" + id + "' nao esta definido");
        }
    }

    /** Remove silenciosamente (fim do bloco except). */
    public void unbind(Frame f) {
        switch (kind) {
            case LOCAL -> f.slots[slot] = null;
            case CELL -> ((Cell) f.slots[slot]).v = null;
            case GLOBAL -> f.globals[slot] = null;
            default -> { }
        }
    }

    /** Aplica a resolucao de embutidos. */
    public void resolveBuiltin() {
        builtinVal = Builtins.lookup(id);
    }
}
