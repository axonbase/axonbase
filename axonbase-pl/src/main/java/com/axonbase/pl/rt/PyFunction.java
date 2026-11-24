package com.axonbase.pl.rt;

import com.axonbase.pl.PlError;
import com.axonbase.pl.PlLimitError;
import com.axonbase.pl.PlTraceFrame;
import com.axonbase.pl.ast.FunctionCode;
import com.axonbase.pl.ast.Stmt;
import java.util.ArrayList;
import java.util.Map;

/** Funcao definida pelo programa (def ou lambda). */
public final class PyFunction extends PyCallable {
    public final FunctionCode code;
    private final Object[] defaults;
    private final Object[] kwDefaults;
    private final Cell[] closure;
    public final ModuleInst mod;

    public PyFunction(FunctionCode code, Object[] defaults, Object[] kwDefaults, Cell[] closure, ModuleInst mod) {
        this.code = code;
        this.defaults = defaults;
        this.kwDefaults = kwDefaults;
        this.closure = closure;
        this.mod = mod;
    }

    @Override
    public String nome() {
        return code.name;
    }

    private RuntimeException missing(Object[] slots) {
        ArrayList<String> miss = new ArrayList<>();
        int firstDef = code.nPos - defaults.length;
        for (int i = 0; i < code.nPos; i++) {
            if (slots[i] == null && i < firstDef) {
                miss.add("'" + code.posNames[i] + "'");
            }
        }
        for (int j = 0; j < code.kwonlyNames.length; j++) {
            if (slots[code.kwonlySlots[j]] == null && kwDefaults[j] == null) {
                miss.add("'" + code.kwonlyNames[j] + "'");
            }
        }
        return Errors.type(code.name + "() faltam argumentos obrigatorios: " + String.join(", ", miss));
    }

    private void bind(Object[] slots, Object[] args, Map<String, Object> kw) {
        int np = code.nPos;
        int n = args.length;
        if (kw == null && code.simple) {
            int firstDef = np - defaults.length;
            if (n == np) {
                System.arraycopy(args, 0, slots, 0, n);
                return;
            }
            if (n < np && n >= firstDef) {
                System.arraycopy(args, 0, slots, 0, n);
                for (int i = n; i < np; i++) {
                    slots[i] = defaults[i - firstDef];
                }
                return;
            }
            if (n > np) {
                throw Errors.type(code.name + "() recebeu " + n + " argumentos posicionais, mas aceita " + np);
            }
            throw Errors.type(code.name + "() faltam argumentos obrigatorios (recebeu " + n + ", precisa de pelo menos "
                    + firstDef + ")");
        }
        System.arraycopy(args, 0, slots, 0, Math.min(n, np));
        if (n > np) {
            if (code.varargsSlot < 0) {
                throw Errors.type(code.name + "() recebeu " + n + " argumentos posicionais, mas aceita " + np);
            }
            Object[] rest = new Object[n - np];
            System.arraycopy(args, np, rest, 0, rest.length);
            slots[code.varargsSlot] = new PyTuple(rest);
        } else if (code.varargsSlot >= 0) {
            slots[code.varargsSlot] = PyTuple.VAZIA;
        }
        PyDict extra = code.varkwSlot >= 0 ? new PyDict() : null;
        if (kw != null) {
            for (Map.Entry<String, Object> e : kw.entrySet()) {
                Integer idx = code.paramIndex.get(e.getKey());
                if (idx != null) {
                    if (slots[idx] != null) {
                        throw Errors.type(code.name + "() recebeu varios valores para o argumento '" + e.getKey() + "'");
                    }
                    slots[idx] = e.getValue();
                } else if (extra != null) {
                    extra.m.put(e.getKey(), e.getValue());
                } else {
                    throw Errors.type(code.name + "() recebeu um argumento nomeado inesperado '" + e.getKey() + "'");
                }
            }
        }
        if (extra != null) {
            slots[code.varkwSlot] = extra;
        }
        int firstDef = np - defaults.length;
        for (int i = 0; i < np; i++) {
            if (slots[i] == null) {
                if (i >= firstDef) {
                    slots[i] = defaults[i - firstDef];
                } else {
                    throw missing(slots);
                }
            }
        }
        for (int j = 0; j < code.kwonlyNames.length; j++) {
            int s = code.kwonlySlots[j];
            if (slots[s] == null) {
                if (kwDefaults[j] == null) {
                    throw missing(slots);
                }
                slots[s] = kwDefaults[j];
            }
        }
    }

    @Override
    public Object call(Frame caller, Object[] args, Map<String, Object> kw) {
        Ctx ctx = caller.ctx;
        if (ctx.depth >= ctx.maxDepth) {
            throw Errors.limite(PlLimitError.Tipo.PROFUNDIDADE,
                    "profundidade maxima de chamadas excedida (" + ctx.maxDepth + ")");
        }
        Object[] slots = new Object[code.nslots];
        bind(slots, args, kw);
        for (int s : code.cellParams) {
            Cell c = new Cell();
            c.v = slots[s];
            slots[s] = c;
        }
        for (int s : code.cellLocals) {
            slots[s] = new Cell();
        }
        for (int i = 0; i < code.freeSlots.length; i++) {
            slots[code.freeSlots[i]] = closure[i];
        }
        ctx.depth++;
        Frame fr = new Frame(ctx, slots, mod, code, caller);
        try {
            if (ctx.hook != null) {
                ctx.hook.onCall(fr);
            }
            Object rv;
            if (code.lambdaBody != null) {
                ctx.step();
                rv = code.lambdaBody.eval(fr);
            } else {
                int r = Stmt.runBlock(code.body, fr);
                rv = r == Stmt.RETURN ? fr.ret : NoneType.NONE;
            }
            if (ctx.hook != null) {
                ctx.hook.onReturn(fr, Convert.paraAxonSolto(rv));
            }
            return rv;
        } catch (PlError e) {
            if (!e.marcarNotificado() && ctx.hook != null) {
                ctx.hook.onException(fr, e);
            }
            e.registrarQuadro(new PlTraceFrame(code.name, mod.programa, fr.linha(), fr.coluna()));
            throw e;
        } catch (StackOverflowError so) {
            PlLimitError e = Errors.limite(PlLimitError.Tipo.PROFUNDIDADE,
                    "pilha de execucao esgotada (recursao funda demais)");
            e.registrarQuadro(new PlTraceFrame(code.name, mod.programa, fr.linha(), fr.coluna()));
            throw e;
        } finally {
            ctx.depth--;
        }
    }
}
