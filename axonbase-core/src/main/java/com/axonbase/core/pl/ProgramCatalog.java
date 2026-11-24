package com.axonbase.core.pl;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Funções, procedures e packages de um banco. Os nomes são únicos entre os três tipos. */
public final class ProgramCatalog {

    private final ConcurrentMap<String, ProgramDef> defs = new ConcurrentHashMap<>();

    public ProgramDef get(String name) {
        return defs.get(name);
    }

    void put(ProgramDef def) {
        defs.put(def.name(), def);
    }

    boolean remove(String name) {
        return defs.remove(name) != null;
    }

    /** Todos os objetos, ordenados por nome. */
    public List<ProgramDef> all() {
        return defs.values().stream().sorted(Comparator.comparing(ProgramDef::name)).toList();
    }

    public List<ProgramDef> of(ProgramDef.Kind kind) {
        return all().stream().filter(d -> d.kind() == kind).toList();
    }
}
