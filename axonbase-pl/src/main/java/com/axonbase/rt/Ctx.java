package com.axonbase.pl.rt;

import com.axonbase.pl.PlDebugHook;
import com.axonbase.pl.PlHost;
import com.axonbase.pl.PlLimitError;
import com.axonbase.pl.PlLimits;
import java.util.HashMap;
import java.util.Map;

/** Contexto de uma execucao: limites, host, hook e contadores. Uma instancia por thread/execucao. */
public final class Ctx {
    public final PlHost host;
    public final PlDebugHook hook;
    public final PlLimits lim;
    public final long maxSteps;
    public final int maxDepth;
    public final long maxElems;
    public final long maxMem;
    public final long maxStr;
    private final long deadline;
    public long steps;
    private long nextCheck;
    public int depth;
    private long mem;
    public PyException handled;
    public final Map<String, Object> modules = new HashMap<>();

    public Ctx(PlHost host, PlLimits lim, PlDebugHook hook) {
        this.host = host;
        this.lim = lim;
        this.hook = hook;
        this.maxSteps = lim.maxPassos();
        this.maxDepth = lim.maxProfundidade();
        this.maxElems = lim.maxElementos();
        this.maxMem = lim.maxMemoria();
        this.maxStr = lim.maxTamanhoString();
        long d = lim.prazoAbsolutoNanos();
        if (lim.prazoNanos() > 0) {
            long rel = System.nanoTime() + lim.prazoNanos();
            d = d == 0 ? rel : Math.min(d, rel);
        }
        this.deadline = d;
        this.nextCheck = Math.min(256, maxSteps + 1);
    }

    /** Conta um passo. Barato: so uma comparacao no caminho comum. */
    public void step() {
        if (++steps >= nextCheck) {
            slow();
        }
    }

    private void slow() {
        if (steps > maxSteps) {
            throw Errors.limite(PlLimitError.Tipo.PASSOS,
                    "limite de passos excedido (" + maxSteps + " passos de execucao)");
        }
        checkTempo();
        nextCheck = Math.min(steps + 256, maxSteps + 1);
    }

    /** Verifica cancelamento e prazo. */
    public void checkTempo() {
        if (lim.cancelado()) {
            throw Errors.limite(PlLimitError.Tipo.CANCELADO, "execucao cancelada");
        }
        if (deadline != 0 && System.nanoTime() - deadline - lim.creditoNanos() > 0) {
            throw Errors.limite(PlLimitError.Tipo.TEMPO, "prazo de execucao excedido");
        }
    }

    /** Valida o tamanho de uma colecao. */
    public void checkElems(long n) {
        if (n > maxElems) {
            throw Errors.limite(PlLimitError.Tipo.MEMORIA,
                    "colecao com " + n + " elementos excede o limite de " + maxElems);
        }
    }

    /** Valida o tamanho de uma string. */
    public void checkStr(long n) {
        if (n > maxStr) {
            throw Errors.limite(PlLimitError.Tipo.MEMORIA,
                    "string com " + n + " caracteres excede o limite de " + maxStr);
        }
    }

    /** Registra uma alocacao em bloco (estimativa logica em bytes). */
    public void alloc(long bytes) {
        mem += bytes;
        if (mem > maxMem) {
            throw Errors.limite(PlLimitError.Tipo.MEMORIA,
                    "estimativa de memoria excede " + maxMem + " bytes");
        }
    }
}
