package com.axonbase.pl;

import java.util.List;

/**
 * Import do programa.
 *
 * @param modulo nome do modulo
 * @param nomes nomes importados com from (vazio para import simples)
 * @param alias apelido do import simples, ou null
 * @param linha linha do comando
 */
public record PlImportInfo(String modulo, List<String> nomes, String alias, int linha) {
}
