package com.axonbase.pl;

/**
 * Quadro do traceback.
 *
 * @param funcao nome da funcao (ou modulo)
 * @param programa nome do programa
 * @param linha linha do comando em execucao
 * @param coluna coluna do comando em execucao
 */
public record PlTraceFrame(String funcao, String programa, int linha, int coluna) {
}
