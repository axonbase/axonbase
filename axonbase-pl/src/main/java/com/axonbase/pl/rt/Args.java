package com.axonbase.pl.rt;

import java.util.Map;

/** Utilitarios de validacao de argumentos das funcoes embutidas. */
public final class Args {
    private Args() {
    }

    public static void n(String fn, Object[] a, int min, int max) {
        if (a.length < min || a.length > max) {
            String esp = min == max ? String.valueOf(min) : min + " a " + max;
            throw Errors.type(fn + "() espera " + esp + " argumento(s), mas recebeu " + a.length);
        }
    }

    public static String str(Object o, String fn) {
        if (o instanceof String s) {
            return s;
        }
        throw Errors.type(fn + "(): era esperado str, mas recebeu '" + Types.nomeDe(o) + "'");
    }

    public static void noKw(String fn, Map<String, Object> kw) {
        if (kw != null && !kw.isEmpty()) {
            throw Errors.type(fn + "() nao aceita argumentos nomeados: " + kw.keySet());
        }
    }

    /** Valor de argumento nomeado (ou null). Remove o nome do mapa de controle. */
    public static Object kw(Map<String, Object> kw, String name) {
        return kw == null ? null : kw.get(name);
    }

    /** Verifica que so ha argumentos nomeados conhecidos. */
    public static void onlyKw(String fn, Map<String, Object> kw, String... allowed) {
        if (kw == null) {
            return;
        }
        outer:
        for (String k : kw.keySet()) {
            for (String a : allowed) {
                if (a.equals(k)) {
                    continue outer;
                }
            }
            throw Errors.type(fn + "() recebeu um argumento nomeado inesperado '" + k + "'");
        }
    }

    public static boolean isPyWs(char c) {
        return Character.isWhitespace(c) || c == 0xA0 || c == 0x85 || Character.isSpaceChar(c);
    }
}
