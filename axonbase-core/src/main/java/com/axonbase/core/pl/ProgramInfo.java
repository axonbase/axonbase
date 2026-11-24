package com.axonbase.core.pl;

import com.axonbase.pl.PlConstantInfo;
import com.axonbase.pl.PlFunctionInfo;
import com.axonbase.pl.PlImportInfo;
import com.axonbase.value.AxonValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descrição de funções, procedures e packages (INFO FOR ..., INFO FOR DATABASE e a rota admin). */
public final class ProgramInfo {

    private static final Pattern LEADING_DOC = Pattern.compile("\\A\\s*(\"\"\"|''')(.*?)\\1", Pattern.DOTALL);

    private ProgramInfo() {
    }

    /** Descrição completa de um objeto. */
    public static AxonValue full(ProgramDef d) {
        Map<String, AxonValue> m = new LinkedHashMap<>();
        m.put("name", AxonValue.str(d.name()));
        m.put("kind", AxonValue.str(d.kind().name()));
        m.put("params", params(d));
        m.put("returns", d.returns() == null ? AxonValue.nul() : AxonValue.str(d.returns()));
        m.put("doc", doc(d));
        m.put("source", AxonValue.str(d.body()));
        m.put("lines", AxonValue.num((long) (d.body().isEmpty() ? 0 : d.body().split("\n", -1).length)));
        m.put("version", AxonValue.num(d.version()));
        m.put("created", AxonValue.datetime(Instant.ofEpochMilli(d.created())));
        m.put("updated", AxonValue.datetime(Instant.ofEpochMilli(d.updated())));
        m.put("security", AxonValue.str(d.definer() ? "DEFINER" : "INVOKER"));
        m.put("broken", AxonValue.bool(d.broken()));
        if (d.broken()) {
            m.put("error", AxonValue.str(d.error()));
        }
        if (d.kind() == ProgramDef.Kind.PACKAGE) {
            m.put("functions", packageFunctions(d));
            m.put("constants", names(constants(d)));
            m.put("imports", imports(d));
        }
        return AxonValue.object(m);
    }

    /** Descrição curta para o INFO FOR DATABASE e a rota admin. */
    public static AxonValue brief(ProgramDef d) {
        Map<String, AxonValue> m = new LinkedHashMap<>();
        if (d.kind() == ProgramDef.Kind.PACKAGE) {
            m.put("functions", names(publicFunctions(d).stream().map(PlFunctionInfo::nome).toList()));
            m.put("constants", names(constants(d)));
        } else {
            m.put("params", params(d));
            m.put("returns", d.returns() == null ? AxonValue.nul() : AxonValue.str(d.returns()));
        }
        m.put("doc", doc(d));
        m.put("version", AxonValue.num(d.version()));
        if (d.broken()) {
            m.put("error", AxonValue.str(d.error()));
        }
        return AxonValue.object(m);
    }

    /** Linha da rota admin: tipo, parâmetros, doc e versão. */
    public static AxonValue listing(ProgramDef d) {
        Map<String, AxonValue> m = new LinkedHashMap<>(brief(d).asObject());
        m.put("name", AxonValue.str(d.name()));
        m.put("kind", AxonValue.str(d.kind().name()));
        m.put("security", AxonValue.str(d.definer() ? "DEFINER" : "INVOKER"));
        m.put("updated", AxonValue.datetime(Instant.ofEpochMilli(d.updated())));
        return AxonValue.object(m);
    }

    private static AxonValue params(ProgramDef d) {
        List<AxonValue> out = new ArrayList<>();
        for (ProgramDef.Param p : d.params()) {
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("name", AxonValue.str(p.name()));
            m.put("type", p.type() == null ? AxonValue.nul() : AxonValue.str(p.type()));
            m.put("default", p.hasDefault() ? p.defaultValue() : AxonValue.nul());
            out.add(AxonValue.object(m));
        }
        return AxonValue.array(out);
    }

    private static AxonValue doc(ProgramDef d) {
        if (d.program() == null) {
            return AxonValue.nul();
        }
        if (d.kind() != ProgramDef.Kind.PACKAGE) {
            for (PlFunctionInfo f : d.program().functions()) {
                if (f.nome().equals(d.name())) {
                    return f.doc() == null ? AxonValue.nul() : AxonValue.str(f.doc().strip());
                }
            }
            return AxonValue.nul();
        }
        Matcher m = LEADING_DOC.matcher(d.body());
        return m.find() ? AxonValue.str(m.group(2).strip()) : AxonValue.nul();
    }

    static List<PlFunctionInfo> publicFunctions(ProgramDef d) {
        return d.program() == null ? List.of()
            : d.program().functions().stream().filter(f -> !f.nome().startsWith("_")).toList();
    }

    private static List<String> constants(ProgramDef d) {
        return d.program() == null ? List.of()
            : d.program().constants().stream().map(PlConstantInfo::nome).filter(n -> !n.startsWith("_")).toList();
    }

    private static AxonValue packageFunctions(ProgramDef d) {
        List<AxonValue> out = new ArrayList<>();
        for (PlFunctionInfo f : publicFunctions(d)) {
            Map<String, AxonValue> m = new LinkedHashMap<>();
            m.put("name", AxonValue.str(f.nome()));
            List<AxonValue> ps = new ArrayList<>();
            f.parametros().forEach(p -> {
                Map<String, AxonValue> pm = new LinkedHashMap<>();
                pm.put("name", AxonValue.str(p.nome()));
                pm.put("type", p.anotacao() == null ? AxonValue.nul() : AxonValue.str(p.anotacao()));
                pm.put("default", p.padrao() == null ? AxonValue.nul() : AxonValue.str(p.padrao()));
                ps.add(AxonValue.object(pm));
            });
            m.put("params", AxonValue.array(ps));
            m.put("returns", f.retorno() == null ? AxonValue.nul() : AxonValue.str(f.retorno()));
            m.put("doc", f.doc() == null ? AxonValue.nul() : AxonValue.str(f.doc().strip()));
            out.add(AxonValue.object(m));
        }
        return AxonValue.array(out);
    }

    private static AxonValue imports(ProgramDef d) {
        List<String> mods = d.program() == null ? List.of()
            : d.program().imports().stream().map(PlImportInfo::modulo).distinct().toList();
        return names(mods);
    }

    private static AxonValue names(List<String> list) {
        return AxonValue.array(list.stream().map(AxonValue::str).toList());
    }
}
