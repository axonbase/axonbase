package com.axonbase.server;

/**
 * Forma normalizada de uma instrução, sem os valores: literais de texto e números viram {@code ?} e listas repetidas
 * colapsam. Duas consultas que só diferem nos valores têm o mesmo fingerprint, e ele não carrega dados pessoais.
 *
 * <p>Roda em toda requisição, então é um percurso único sobre o texto (as quatro expressões regulares de antes eram
 * o maior custo de CPU do caminho de uma leitura por id).</p>
 */
public final class Fingerprint {

    static final int MAX_CHARS = 300;

    private Fingerprint() {}

    public static String of(String sql) {
        if (sql == null) {
            return "";
        }
        int n = sql.length();
        // passo 1: literais de texto e números viram '?'
        StringBuilder tmp = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = endOfString(sql, i, c);
                if (end > 0) {
                    tmp.append('?');
                    i = end;
                    continue;
                }
            } else if (c >= '0' && c <= '9' && (i == 0 || !isWord(sql.charAt(i - 1)))) {
                int end = endOfNumber(sql, i);
                if (end > 0) {
                    tmp.append('?');
                    i = end;
                    continue;
                }
            }
            tmp.append(c);
            i++;
        }
        // passo 2: listas de '?' colapsam e os espaços viram um só
        int m = tmp.length();
        StringBuilder out = new StringBuilder(m);
        boolean pendingSpace = false;
        i = 0;
        while (i < m && out.length() <= MAX_CHARS) {
            char c = tmp.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                i++;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
            i++;
            if (c == '?') {
                // ? , ? , ?  -> ?
                int j = i;
                while (true) {
                    int k = skipSpaces(tmp, j);
                    if (k < m && tmp.charAt(k) == ',') {
                        int q = skipSpaces(tmp, k + 1);
                        if (q < m && tmp.charAt(q) == '?') {
                            j = q + 1;
                            continue;
                        }
                    }
                    break;
                }
                i = j;
            }
        }
        // o que sobrou depois do limite não importa, mas o corte precisa casar com o texto completo
        String s = out.toString();
        boolean longer = out.length() > MAX_CHARS || i < m;
        if (longer) {
            return s.substring(0, Math.min(s.length(), MAX_CHARS)) + "...";
        }
        return s;
    }

    private static int skipSpaces(CharSequence s, int from) {
        int k = from;
        while (k < s.length() && Character.isWhitespace(s.charAt(k))) {
            k++;
        }
        return k;
    }

    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    private static boolean isWord(char c) {
        return c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Fim (exclusivo) do texto entre aspas que começa em {@code start}, ou -1 se não fecha. */
    private static int endOfString(String s, int start, char quote) {
        int n = s.length();
        int i = start + 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                // a barra invertida leva o caractere seguinte, que não pode ser quebra de linha (como o "." do regex antigo)
                if (i + 1 >= n || isLineTerminator(s.charAt(i + 1))) {
                    return -1;
                }
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    /** Fim (exclusivo) do número {@code \d+(\.\d+)?} cercado por limites de palavra, ou -1. */
    private static int endOfNumber(String s, int start) {
        int n = s.length();
        int i = start;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        int intEnd = i;
        if (i + 1 < n && s.charAt(i) == '.' && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '9') {
            int j = i + 1;
            while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                j++;
            }
            if (j >= n || !isWord(s.charAt(j))) {
                return j;
            }
        }
        return intEnd >= n || !isWord(s.charAt(intEnd)) ? intEnd : -1;
    }
}
