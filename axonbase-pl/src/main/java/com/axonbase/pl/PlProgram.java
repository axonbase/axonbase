package com.axonbase.pl;

import com.axonbase.pl.ast.FunctionCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Programa AxonPL compilado. Imutavel e seguro entre threads. */
public final class PlProgram {
    private final String nome;
    private final String fonte;
    private final FunctionCode modulo;
    private final String[] globais;
    private final Map<String, Integer> indice = new HashMap<>();
    private final List<PlFunctionInfo> funcoes;
    private final List<PlConstantInfo> constantes;
    private final List<PlImportInfo> imports;
    private final boolean declaraGlobal;

    PlProgram(String nome, String fonte, FunctionCode modulo, String[] globais, List<PlFunctionInfo> funcoes,
              List<PlConstantInfo> constantes, List<PlImportInfo> imports, boolean declaraGlobal) {
        this.nome = nome;
        this.fonte = fonte;
        this.modulo = modulo;
        this.globais = globais;
        for (int i = 0; i < globais.length; i++) {
            indice.put(globais[i], i);
        }
        this.funcoes = List.copyOf(funcoes);
        this.constantes = List.copyOf(constantes);
        this.imports = List.copyOf(imports);
        this.declaraGlobal = declaraGlobal;
    }

    /** Nome do programa. */
    public String nome() {
        return nome;
    }

    /** Texto fonte (com quebras de linha normalizadas). */
    public String fonte() {
        return fonte;
    }

    /** Funcoes de nivel superior, na ordem de declaracao. */
    public List<PlFunctionInfo> functions() {
        return funcoes;
    }

    /** Atribuicoes de nivel superior (constantes publicas). */
    public List<PlConstantInfo> constants() {
        return constantes;
    }

    /** Imports do programa. */
    public List<PlImportInfo> imports() {
        return imports;
    }

    /** Verdadeiro se alguma funcao declara {@code global} (e portanto pode reatribuir variaveis do modulo). */
    public boolean declaraGlobal() {
        return declaraGlobal;
    }

    /** Uso interno: quantidade de variaveis globais. */
    public int numGlobais() {
        return globais.length;
    }

    /** Uso interno: nomes das variaveis globais. */
    public String[] nomesGlobais() {
        return globais;
    }

    /** Uso interno: indice de um nome global, ou -1. */
    public int indiceGlobal(String n) {
        Integer i = indice.get(n);
        return i == null ? -1 : i;
    }

    /** Uso interno: codigo do corpo do modulo. */
    public FunctionCode moduleCode() {
        return modulo;
    }
}
