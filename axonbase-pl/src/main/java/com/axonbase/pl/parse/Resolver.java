package com.axonbase.pl.parse;

import com.axonbase.pl.PlSyntaxError;
import com.axonbase.pl.ast.FunctionCode;
import com.axonbase.pl.ast.Name;
import com.axonbase.pl.ast.Scope;
import java.util.ArrayList;
import java.util.List;

/** Resolve nomes: slots locais, celulas de closure, globais e embutidos. */
final class Resolver {
    private Resolver() {
    }

    /** Resolve todos os escopos e devolve os nomes globais na ordem dos slots. */
    static String[] resolve(Scope module) {
        List<Scope> all = new ArrayList<>();
        collect(module, all);
        for (Scope s : all) {
            for (Name r : s.refs) {
                classify(module, s, r);
            }
        }
        // slots do modulo
        List<String> g = new ArrayList<>(module.bound);
        for (int k = 0; k < g.size(); k++) {
            module.slots.put(g.get(k), k);
        }
        module.code.nslots = g.size();
        module.code.slotNames = g.toArray(new String[0]);
        for (Scope s : all) {
            if (!s.module) {
                assign(s);
            }
        }
        for (Scope s : all) {
            if (!s.module) {
                FunctionCode c = s.code;
                c.freeCapture = new int[s.freevars.size()];
                int k = 0;
                for (String fv : s.freevars) {
                    Scope p = s.parent;
                    Integer ps = p.module ? null : p.slots.get(fv);
                    if (ps == null) {
                        throw new PlSyntaxError(c.line, 1, "variavel livre '" + fv + "' sem ligacao em escopo externo", "");
                    }
                    c.freeCapture[k++] = ps;
                }
            }
        }
        for (Scope s : all) {
            for (Name r : s.refs) {
                finish(module, s, r);
            }
        }
        return g.toArray(new String[0]);
    }

    private static void collect(Scope s, List<Scope> out) {
        out.add(s);
        for (Scope c : new ArrayList<>(s.children)) {
            collect(c, out);
        }
    }

    private static boolean ownsLocal(Scope s, String n) {
        return s.bound.contains(n) && !s.globals.contains(n) && !s.nonlocals.contains(n);
    }

    private static void markFree(Scope from, String name, Scope owner) {
        for (Scope t = from; t != owner; t = t.parent) {
            t.freevars.add(name);
        }
        owner.cellvars.add(name);
    }

    private static void classify(Scope module, Scope s, Name r) {
        String id = r.id;
        if (s.module) {
            r.kind = module.bound.contains(id) ? Name.GLOBAL : Name.BUILTIN;
            return;
        }
        if (s.globals.contains(id)) {
            r.kind = Name.GLOBAL;
            return;
        }
        if (s.nonlocals.contains(id)) {
            Scope a = s.parent;
            while (a != null && !a.module) {
                if (ownsLocal(a, id)) {
                    markFree(s, id, a);
                    r.kind = Name.FREE;
                    return;
                }
                a = a.parent;
            }
            throw new PlSyntaxError(r.line, r.col, "nonlocal '" + id + "' sem ligacao em funcao externa", "");
        }
        if (s.bound.contains(id)) {
            r.kind = Name.LOCAL;
            return;
        }
        Scope a = s.parent;
        while (a != null && !a.module) {
            if (ownsLocal(a, id)) {
                markFree(s, id, a);
                r.kind = Name.FREE;
                return;
            }
            a = a.parent;
        }
        r.kind = module.bound.contains(id) ? Name.GLOBAL : Name.BUILTIN;
    }

    private static void assign(Scope s) {
        List<String> names = new ArrayList<>();
        for (String n : s.bound) {
            if (!s.globals.contains(n) && !s.nonlocals.contains(n)) {
                names.add(n);
            }
        }
        int nBound = names.size();
        for (String fv : s.freevars) {
            if (!names.contains(fv)) {
                names.add(fv);
            }
        }
        for (int k = 0; k < names.size(); k++) {
            s.slots.put(names.get(k), k);
        }
        FunctionCode c = s.code;
        c.nslots = names.size();
        c.slotNames = names.toArray(new String[0]);
        int nParams = c.nPos + c.kwonlyNames.length + (c.varargs != null ? 1 : 0) + (c.varkw != null ? 1 : 0);
        List<Integer> cp = new ArrayList<>();
        List<Integer> cl = new ArrayList<>();
        for (int k = 0; k < nBound; k++) {
            if (s.cellvars.contains(names.get(k))) {
                (k < nParams ? cp : cl).add(k);
            }
        }
        c.cellParams = cp.stream().mapToInt(Integer::intValue).toArray();
        c.cellLocals = cl.stream().mapToInt(Integer::intValue).toArray();
        int[] fs = new int[s.freevars.size()];
        int k = 0;
        for (String fv : s.freevars) {
            fs[k++] = s.slots.get(fv);
        }
        c.freeSlots = fs;
        for (int j = 0; j < c.nPos; j++) {
            c.paramIndex.put(c.posNames[j], s.slots.get(c.posNames[j]));
        }
        c.kwonlySlots = new int[c.kwonlyNames.length];
        for (int j = 0; j < c.kwonlyNames.length; j++) {
            c.kwonlySlots[j] = s.slots.get(c.kwonlyNames[j]);
            c.paramIndex.put(c.kwonlyNames[j], c.kwonlySlots[j]);
        }
        c.varargsSlot = c.varargs == null ? -1 : s.slots.get(c.varargs);
        c.varkwSlot = c.varkw == null ? -1 : s.slots.get(c.varkw);
    }

    private static void finish(Scope module, Scope s, Name r) {
        switch (r.kind) {
            case Name.LOCAL:
                r.slot = s.slots.get(r.id);
                if (s.cellvars.contains(r.id)) {
                    r.kind = Name.CELL;
                }
                break;
            case Name.FREE:
                r.slot = s.slots.get(r.id);
                r.kind = Name.CELL;
                break;
            case Name.GLOBAL:
                r.slot = module.slots.get(r.id);
                break;
            default:
                r.resolveBuiltin();
                break;
        }
    }
}
