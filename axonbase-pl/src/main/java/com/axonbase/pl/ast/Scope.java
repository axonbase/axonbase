package com.axonbase.pl.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Escopo lexico usado pela resolucao de nomes em tempo de compilacao. */
public final class Scope {
    public Scope parent;
    public final boolean module;
    public final FunctionCode code;
    public final LinkedHashSet<String> bound = new LinkedHashSet<>();
    public final Set<String> globals = new HashSet<>();
    public final Set<String> nonlocals = new HashSet<>();
    public final List<Name> refs = new ArrayList<>();
    public final List<Scope> children = new ArrayList<>();
    public final LinkedHashSet<String> cellvars = new LinkedHashSet<>();
    public final LinkedHashSet<String> freevars = new LinkedHashSet<>();
    public final Map<String, Integer> slots = new HashMap<>();

    public Scope(Scope parent, boolean module, FunctionCode code) {
        this.parent = parent;
        this.module = module;
        this.code = code;
        if (parent != null) {
            parent.children.add(this);
        }
    }

    public Scope moduleScope() {
        Scope s = this;
        while (!s.module) {
            s = s.parent;
        }
        return s;
    }
}
