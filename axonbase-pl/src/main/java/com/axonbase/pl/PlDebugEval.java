package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Avalia uma expressao no escopo de um quadro pausado, em modo somente leitura: as variaveis do quadro entram como
 * copias, entao nada do programa depurado muda. O acesso ao banco depende do {@link PlHost} informado.
 */
public final class PlDebugEval {
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESERVADAS = Set.of("False", "None", "True", "and", "as", "assert", "async",
        "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
        "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
        "while", "with", "yield");
    private static final PlRuntime RT = new PlRuntime();

    private PlDebugEval() {
    }

    /**
     * Avalia a expressao.
     *
     * @param frame quadro pausado (locais e globais viram variaveis da expressao)
     * @param expressao uma unica expressao, sem quebra de linha
     * @param host ponte somente leitura com o banco (pode ser null)
     * @param limits limites da avaliacao (null usa passos e prazo reduzidos)
     * @return valor da expressao
     * @throws PlError em erro de sintaxe ou de execucao da expressao
     */
    public static AxonValue avaliar(PlFrame frame, String expressao, PlHost host, PlLimits limits) {
        if (expressao == null || expressao.isBlank()) {
            throw new PlDebugError("expressao vazia");
        }
        if (expressao.contains("\n") || expressao.contains("\r")) {
            throw new PlDebugError("a expressao deve ter uma unica linha");
        }
        Map<String, AxonValue> escopo = new LinkedHashMap<>(frame.globais());
        escopo.putAll(frame.locais());
        List<String> nomes = new ArrayList<>();
        List<AxonValue> valores = new ArrayList<>();
        for (Map.Entry<String, AxonValue> e : escopo.entrySet()) {
            if (IDENT.matcher(e.getKey()).matches() && !RESERVADAS.contains(e.getKey())) {
                nomes.add(e.getKey());
                valores.add(e.getValue());
            }
        }
        String fonte = "def __avaliar__(" + String.join(", ", nomes) + "):\n    return (" + expressao + "\n    )\n";
        PlProgram p;
        try {
            p = PlCompiler.compile(fonte, "expressao");
        } catch (PlSyntaxError e) {
            throw new PlDebugError("expressao invalida: " + e.mensagemSimples());
        }
        PlLimits lim = limits != null ? limits : PlLimits.padrao().maxPassos(100_000).prazoNanos(2_000_000_000L);
        return RT.call(p, "__avaliar__", valores, null, host, lim, null);
    }
}
