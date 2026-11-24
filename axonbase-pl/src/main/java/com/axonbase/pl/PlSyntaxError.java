package com.axonbase.pl;

/** Erro de sintaxe do AxonPL, com posicao e trecho da linha. */
public final class PlSyntaxError extends PlError {

    private final int linha;
    private final int coluna;
    private final String trecho;
    private final String mensagemSimples;

    /**
     * Cria o erro.
     *
     * @param linha linha (a partir de 1)
     * @param coluna coluna (a partir de 1)
     * @param mensagem mensagem em portugues
     * @param trecho texto da linha com o problema
     */
    public PlSyntaxError(int linha, int coluna, String mensagem, String trecho) {
        super("SyntaxError", "Erro de sintaxe na linha " + linha + ", coluna " + coluna + ": " + mensagem);
        this.linha = linha;
        this.coluna = coluna;
        this.trecho = trecho == null ? "" : trecho;
        this.mensagemSimples = mensagem;
    }

    /** Linha do erro (a partir de 1). */
    public int linha() {
        return linha;
    }

    /** Coluna do erro (a partir de 1). */
    public int coluna() {
        return coluna;
    }

    /** Trecho da linha onde ocorreu o erro. */
    public String trecho() {
        return trecho;
    }

    /** Mensagem sem o prefixo de posicao. */
    public String mensagemSimples() {
        return mensagemSimples;
    }
}
