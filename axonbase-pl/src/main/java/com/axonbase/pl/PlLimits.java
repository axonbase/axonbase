package com.axonbase.pl;

/**
 * Limites de execucao. O objeto e mutavel apenas pelo configurador e pelo cancelamento
 * (flag volatil), que pode ser acionado por outra thread.
 */
public final class PlLimits {
    private long maxPassos = 1_000_000;
    private long prazoAbsolutoNanos;
    private long prazoRelativoNanos;
    private int maxProfundidade = 64;
    private long maxElementos = 5_000_000;
    private long maxMemoria = 64L * 1024 * 1024;
    private long maxTamanhoString = 16_000_000;
    private volatile boolean cancelado;
    private volatile long passosExecutados;

    /** Limites padrao. */
    public static PlLimits padrao() {
        return new PlLimits();
    }

    public PlLimits maxPassos(long v) {
        this.maxPassos = v;
        return this;
    }

    /** Prazo absoluto no relogio de System.nanoTime (0 = sem prazo). */
    public PlLimits prazoAbsolutoNanos(long v) {
        this.prazoAbsolutoNanos = v;
        return this;
    }

    /** Prazo relativo ao inicio de cada execucao, em nanossegundos (0 = sem prazo). */
    public PlLimits prazoNanos(long v) {
        this.prazoRelativoNanos = v;
        return this;
    }

    public PlLimits maxProfundidade(int v) {
        this.maxProfundidade = v;
        return this;
    }

    /** Maximo de elementos de uma colecao. */
    public PlLimits maxElementos(long v) {
        this.maxElementos = v;
        return this;
    }

    /** Estimativa de memoria logica em bytes para alocacoes em bloco. */
    public PlLimits maxMemoria(long v) {
        this.maxMemoria = v;
        return this;
    }

    public PlLimits maxTamanhoString(long v) {
        this.maxTamanhoString = v;
        return this;
    }

    /** Aciona o cancelamento (seguro entre threads). */
    public void cancelar() {
        this.cancelado = true;
    }

    /** Limpa o cancelamento. */
    public void limparCancelamento() {
        this.cancelado = false;
    }

    /** Passos executados pela última execução que usou estes limites (para métricas). */
    public long passosExecutados() {
        return passosExecutados;
    }

    void passosExecutados(long v) {
        this.passosExecutados = v;
    }

    public boolean cancelado() {
        return cancelado;
    }

    public long maxPassos() {
        return maxPassos;
    }

    public long prazoAbsolutoNanos() {
        return prazoAbsolutoNanos;
    }

    public long prazoNanos() {
        return prazoRelativoNanos;
    }

    public int maxProfundidade() {
        return maxProfundidade;
    }

    public long maxElementos() {
        return maxElementos;
    }

    public long maxMemoria() {
        return maxMemoria;
    }

    public long maxTamanhoString() {
        return maxTamanhoString;
    }
}
