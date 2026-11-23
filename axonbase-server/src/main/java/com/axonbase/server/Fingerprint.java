package com.axonbase.server;

import java.util.regex.Pattern;

/**
 * Forma normalizada de uma instrução, sem os valores: literais de texto e números viram {@code ?} e listas repetidas
 * colapsam. Duas consultas que só diferem nos valores têm o mesmo fingerprint, e ele não carrega dados pessoais.
 */
public final class Fingerprint {

    static final int MAX_CHARS = 300;
    private static final Pattern STRINGS = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern NUMBERS = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern LISTS = Pattern.compile("\\?(?:\\s*,\\s*\\?)+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Fingerprint() {}

    public static String of(String sql) {
        if (sql == null) {
            return "";
        }
        String s = STRINGS.matcher(sql).replaceAll("?");
        s = NUMBERS.matcher(s).replaceAll("?");
        s = LISTS.matcher(s).replaceAll("?");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) + "..." : s;
    }
}
