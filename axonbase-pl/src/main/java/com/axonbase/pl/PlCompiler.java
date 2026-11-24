package com.axonbase.pl;

import com.axonbase.pl.lex.Lexer;
import com.axonbase.pl.parse.Parser;
import java.util.concurrent.ConcurrentHashMap;

/** Compilador do AxonPL: texto para PlProgram, com cache de AST. */
public final class PlCompiler {
    private PlCompiler() {
    }

    private static final int CACHE_MAX = 512;
    private static final ConcurrentHashMap<String, PlProgram> CACHE = new ConcurrentHashMap<>();

    /**
     * Compila o texto.
     *
     * @param source codigo AxonPL (subconjunto de Python 3)
     * @param programName nome usado nos tracebacks
     * @return programa imutavel
     * @throws PlSyntaxError se houver erro de sintaxe ou uso de recurso fora da versao 1.0
     */
    public static PlProgram compile(String source, String programName) {
        String key = programName + "\u0000" + source;
        PlProgram cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String src = Lexer.normalizar(source);
        Parser.Resultado r = Parser.analisar(src);
        PlProgram p = new PlProgram(programName, src, r.modulo(), r.globais(), r.funcoes(), r.constantes(), r.imports());
        if (CACHE.size() >= CACHE_MAX) {
            CACHE.clear();
        }
        CACHE.put(key, p);
        return p;
    }
}
