package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host de teste que simula o modulo db em memoria. Funcoes de db: query(tabela, filtro), one(tabela, filtro),
 * exec(tabela, objeto) e count(tabela). Tambem coleta a saida de print em {@link #logs()}.
 */
public final class PlHostMemoria implements PlHost {
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<AxonValue>> tabelas = new HashMap<>();
    private final Map<String, PlProgram> modulos = new HashMap<>();

    /** Linhas escritas por print. */
    public List<String> logs() {
        return logs;
    }

    /** Insere uma linha numa tabela. */
    public void inserir(String tabela, AxonValue objeto) {
        tabelas.computeIfAbsent(tabela, k -> new ArrayList<>()).add(objeto);
    }

    /** Registra um programa como modulo importavel. */
    public void registrarModulo(String nome, PlProgram p) {
        modulos.put(nome, p);
    }

    @Override
    public PlProgram resolverImport(String nome) {
        return modulos.get(nome);
    }

    @Override
    public Set<String> modulosNativos() {
        return Set.of("db");
    }

    @Override
    public void log(String nivel, String mensagem, List<AxonValue> args) {
        logs.add(mensagem);
    }

    private List<AxonValue> filtrar(List<AxonValue> args) {
        String t = args.get(0).asString();
        List<AxonValue> rows = tabelas.getOrDefault(t, List.of());
        if (args.size() < 2 || !args.get(1).isObject()) {
            return new ArrayList<>(rows);
        }
        Map<String, AxonValue> f = args.get(1).asObject();
        List<AxonValue> r = new ArrayList<>();
        for (AxonValue row : rows) {
            boolean ok = true;
            for (Map.Entry<String, AxonValue> e : f.entrySet()) {
                AxonValue v = row.asObject().get(e.getKey());
                if (v == null || !v.equals(e.getValue())) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                r.add(row);
            }
        }
        return r;
    }

    @Override
    public Object attr(String modulo, String nome) {
        if (!modulo.equals("db")) {
            return null;
        }
        switch (nome) {
            case "query":
                return (PlCallable) (args, kw) -> AxonValue.array(filtrar(args));
            case "one":
                return (PlCallable) (args, kw) -> {
                    List<AxonValue> r = filtrar(args);
                    return r.isEmpty() ? AxonValue.nul() : r.get(0);
                };
            case "exec":
                return (PlCallable) (args, kw) -> {
                    inserir(args.get(0).asString(), args.get(1));
                    return AxonValue.num(1L);
                };
            case "count":
                return (PlCallable) (args, kw) -> AxonValue.num((long) filtrar(args).size());
            case "nome":
                return AxonValue.str("memoria");
            default:
                return null;
        }
    }

    static Map<String, AxonValue> unused() {
        return new LinkedHashMap<>();
    }
}
