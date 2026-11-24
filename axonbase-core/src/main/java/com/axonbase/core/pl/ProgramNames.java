package com.axonbase.core.pl;

import com.axonbase.core.engine.Functions;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Nomes válidos e nomes reservados (namespaces e funções embutidos) para funções, procedures e packages. */
final class ProgramNames {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESERVED = reserved();

    private ProgramNames() {
    }

    private static Set<String> reserved() {
        Set<String> r = new HashSet<>();
        for (String n : Functions.names()) {
            int c = n.indexOf("::");
            r.add(c < 0 ? n : n.substring(0, c));
            r.add(n);
        }
        // namespaces embutidos que ainda não têm entrada na lista, módulos do AxonPL e módulos nativos
        r.addAll(Set.of("fn", "db", "log", "json", "decimal", "math", "time", "string", "array", "vector", "geo",
            "search", "graph", "kv", "rand", "crypto", "encoding", "type", "record", "meta", "object", "count",
            "geometry", "value", "now"));
        return r;
    }

    static boolean validIdentifier(String name) {
        return name != null && IDENT.matcher(name).matches();
    }

    static boolean reservedName(String name) {
        return RESERVED.contains(name.toLowerCase(java.util.Locale.ROOT));
    }
}
