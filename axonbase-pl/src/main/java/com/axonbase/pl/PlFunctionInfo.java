package com.axonbase.pl;

import java.util.List;

/**
 * Funcao de nivel superior declarada no programa.
 *
 * @param nome nome da funcao
 * @param parametros parametros na ordem de declaracao
 * @param retorno texto da anotacao de retorno, ou null
 * @param doc docstring, ou null
 * @param linha linha da declaracao
 */
public record PlFunctionInfo(String nome, List<PlParamInfo> parametros, String retorno, String doc, int linha) {
}
