package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rastro de execucao: contagem de execucoes e tempo exclusivo por linha. O tempo de uma linha que chama outra
 * funcao nao inclui o tempo da funcao chamada (ele vai para as linhas dela). Usado por uma unica thread de
 * execucao; a leitura final e sincronizada.
 */
public final class PlProfiler implements PlDebugHook {

    /** Estatistica de uma linha. */
    public record Linha(String programa, int linha, long execucoes, long nanos) {
    }

    private final Map<String, long[]> dados = new LinkedHashMap<>();
    private final Map<String, Object[]> chaves = new LinkedHashMap<>();
    private String ultima;
    private long ultimoTs = System.nanoTime();

    private static String chave(String programa, int linha) {
        return programa + "\u0000" + linha;
    }

    private synchronized void cobrar() {
        long agora = System.nanoTime();
        if (ultima != null) {
            dados.get(ultima)[1] += agora - ultimoTs;
        }
        ultimoTs = agora;
    }

    /** Desconta um intervalo (por exemplo o tempo parado num breakpoint) do tempo da linha atual. */
    public synchronized void descontar(long nanos) {
        ultimoTs += nanos;
    }

    @Override
    public synchronized void onLine(PlFrame frame, int line) {
        cobrar();
        String k = chave(frame.programa(), line);
        dados.computeIfAbsent(k, x -> new long[2])[0]++;
        chaves.putIfAbsent(k, new Object[] {frame.programa(), line});
        ultima = k;
    }

    @Override
    public synchronized void onCall(PlFrame frame) {
        cobrar();
        ultima = null;
    }

    @Override
    public synchronized void onReturn(PlFrame frame, AxonValue valor) {
        cobrar();
        PlFrame c = frame.chamador();
        String k = c == null ? null : chave(c.programa(), c.linha());
        ultima = k != null && dados.containsKey(k) ? k : null;
    }

    /** Estatisticas por linha, na ordem em que as linhas foram vistas pela primeira vez. */
    public synchronized List<Linha> linhas() {
        cobrar();
        List<Linha> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : dados.entrySet()) {
            Object[] id = chaves.get(e.getKey());
            out.add(new Linha((String) id[0], (Integer) id[1], e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }
}
