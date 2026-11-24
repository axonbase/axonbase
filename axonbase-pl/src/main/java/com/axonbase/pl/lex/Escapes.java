package com.axonbase.pl.lex;

import com.axonbase.pl.PlSyntaxError;
import java.util.function.Function;

/** Processamento de sequencias de escape de strings. */
public final class Escapes {

    private Escapes() {
    }

    /** Aplica os escapes ao corpo cru de uma string. */
    public static String cook(String body, boolean bytes, Function<String, PlSyntaxError> err) {
        if (body.indexOf('\\') < 0) {
            return body;
        }
        StringBuilder sb = new StringBuilder(body.length());
        int n = body.length();
        for (int i = 0; i < n; i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= n) {
                sb.append(c);
                continue;
            }
            char e = body.charAt(++i);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '\\' -> sb.append('\\');
                case '\'' -> sb.append('\'');
                case '"' -> sb.append('"');
                case 'a' -> sb.append('\u0007');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'v' -> sb.append('\u000b');
                case '\n' -> { }
                case 'x' -> {
                    sb.append((char) hex(body, i + 1, 2, err));
                    i += 2;
                }
                case 'u' -> {
                    if (bytes) {
                        sb.append("\\u");
                    } else {
                        sb.append((char) hex(body, i + 1, 4, err));
                        i += 4;
                    }
                }
                case 'U' -> {
                    if (bytes) {
                        sb.append("\\U");
                    } else {
                        sb.appendCodePoint(hex(body, i + 1, 8, err));
                        i += 8;
                    }
                }
                default -> {
                    if (e >= '0' && e <= '7') {
                        int v = e - '0';
                        int k = 0;
                        while (k < 2 && i + 1 < n && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7') {
                            v = v * 8 + (body.charAt(++i) - '0');
                            k++;
                        }
                        sb.append((char) v);
                    } else {
                        sb.append('\\').append(e);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static int hex(String b, int from, int len, Function<String, PlSyntaxError> err) {
        if (from + len > b.length()) {
            throw err.apply("escape incompleto na string (esperados " + len + " digitos hexadecimais)");
        }
        try {
            return Integer.parseInt(b.substring(from, from + len), 16);
        } catch (NumberFormatException ex) {
            throw err.apply("escape invalido na string: digitos hexadecimais esperados");
        }
    }
}
