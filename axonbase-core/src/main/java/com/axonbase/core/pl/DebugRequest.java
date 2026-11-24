package com.axonbase.core.pl;

import com.axonbase.value.AxonValue;
import java.util.List;
import java.util.Set;

/**
 * Pedido de uma sessão de depuração.
 *
 * @param ns namespace do banco
 * @param db banco
 * @param auth claims de quem pediu (a execução usa a mesma identidade); nulo no modo sem autenticação
 * @param program nome do programa: {@code nome} ou {@code pacote::funcao}
 * @param args argumentos posicionais
 * @param breakpoints linhas do corpo do usuário (a primeira linha do corpo é a 1)
 * @param stopOnEntry pausa no primeiro comando do corpo
 * @param commit confirma a transação ao final (só admin); por padrão tudo é revertido
 * @param profile devolve contagem e tempo por linha e as consultas disparadas
 */
public record DebugRequest(String ns, String db, AxonValue auth, String program, List<AxonValue> args,
                           Set<Integer> breakpoints, boolean stopOnEntry, boolean commit, boolean profile) {
}
