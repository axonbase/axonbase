package com.axonbase.pl;

/**
 * Parametro de uma funcao, para a interface do usuario.
 *
 * @param nome nome do parametro
 * @param padrao texto do valor padrao, ou null
 * @param anotacao texto da anotacao de tipo, ou null
 * @param tipo categoria do parametro
 */
public record PlParamInfo(String nome, String padrao, String anotacao, Tipo tipo) {

    /** Categoria do parametro. */
    public enum Tipo { POSICIONAL, SOMENTE_NOMEADO, ARGS, KWARGS }
}
