package com.axonbase.parser;

/**
 * Texto de corpo de funções, procedures e packages (AxonPL): normalização da indentação e escolha do delimitador
 * de dólar que reemite o corpo exatamente.
 */
public final class ProgramText {

    private ProgramText() {
    }

    /**
     * Remove as linhas em branco iniciais e finais e a indentação comum, para o corpo (instruções Python) poder ser
     * escrito com recuo dentro do {@code $$}. Quebras de linha viram {@code \n}.
     */
    public static String normalizeBody(String body) {
        if (body == null) {
            return "";
        }
        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int first = 0;
        int last = lines.length - 1;
        while (first <= last && lines[first].isBlank()) {
            first++;
        }
        while (last >= first && lines[last].isBlank()) {
            last--;
        }
        if (first > last) {
            return "";
        }
        // texto que começa na mesma linha do delimitador ($$ return x ...): o espaço inicial não é recuo do bloco
        boolean sameLine = first == 0 && !lines[0].isBlank();
        if (sameLine) {
            lines[0] = lines[0].stripLeading();
        }
        int common = Integer.MAX_VALUE;
        for (int i = sameLine ? first + 1 : first; i <= last; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            int n = 0;
            while (n < lines[i].length() && (lines[i].charAt(n) == ' ' || lines[i].charAt(n) == '\t')) {
                n++;
            }
            common = Math.min(common, n);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = first; i <= last; i++) {
            if (i > first) {
                sb.append('\n');
            }
            String line = lines[i];
            int cut = sameLine && i == first ? 0 : Math.min(common, line.length());
            sb.append(line.isBlank() ? "" : line.substring(cut).stripTrailing());
        }
        return sb.toString();
    }

    /** Escolhe um delimitador {@code $$} ou {@code $tag$} que não aparece no corpo. */
    public static String delimiterFor(String body) {
        if (!body.contains("$$") && !body.endsWith("$")) {
            return "$$";
        }
        String tag = "axon";
        int n = 0;
        while (body.contains("$" + tag + "$") || body.endsWith("$" + tag)) {
            tag = "axon" + (++n);
        }
        return "$" + tag + "$";
    }

    /** O corpo entre delimitadores, pronto para o Render. */
    public static String quote(String body) {
        String d = delimiterFor(body);
        return d + body + d;
    }
}
