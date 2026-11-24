package com.axonbase.pl;

/**
 * Constante (atribuicao de nivel superior) do programa.
 *
 * @param nome nome da variavel
 * @param linha linha da atribuicao
 * @param valor texto fonte da expressao atribuida
 */
public record PlConstantInfo(String nome, int linha, String valor) {
}
