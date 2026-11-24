/**
 * AxonPL (Axon Procedural Language): subconjunto de Python 3 interpretado dentro do banco.
 *
 * <p>API publica: {@link com.axonbase.pl.PlCompiler} compila o texto em um
 * {@link com.axonbase.pl.PlProgram} imutavel; {@link com.axonbase.pl.PlRuntime} executa funcoes ou o corpo do
 * programa (devolvendo um {@link com.axonbase.pl.PlNamespace}); {@link com.axonbase.pl.PlHost} liga o programa
 * ao ambiente; {@link com.axonbase.pl.PlLimits} limita passos, tempo, profundidade e memoria;
 * {@link com.axonbase.pl.PlDebugHook} observa (e pode pausar) a execucao.
 *
 * <p>Divergencias intencionais em relacao ao CPython: inteiros de 64 bits (estouro vira OverflowError; use
 * decimal para numeros grandes), strings em UTF-16 (caracteres fora do plano basico contam como 2), chaves de
 * dict e elementos de set comparam por equals do Java (True e 1 sao chaves distintas, 1 e 1.0 tambem), keys,
 * values e items devolvem listas, sets iteram na ordem de insercao, geradores sao avaliados de forma ansiosa,
 * nao existe complex (base negativa com expoente fracionario lanca ValueError) e nao existem classes.
 *
 * <p>Fora da versao 1.0 (erro de sintaxe amigavel): class, yield, async/await, with, match, decoradores,
 * walrus, operador de matriz. Nao existem exec, eval, compile, open, getattr, setattr, globals, locals,
 * import dinamico, arquivos, rede ou acesso ao sistema.
 */
package com.axonbase.pl;
