package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.List;
import java.util.Map;

/**
 * Ganchos de depuracao. As chamadas podem bloquear a thread (um depurador pausa a execucao).
 * Todos os metodos tem implementacao vazia. Com hook igual a null o custo e uma comparacao.
 */
public interface PlDebugHook {

    /** Antes de executar cada comando, com a linha dele. */
    default void onLine(PlFrame frame, int line) {
    }

    /** Ao entrar numa funcao. */
    default void onCall(PlFrame frame) {
    }

    /** Ao sair de uma funcao com valor de retorno. */
    default void onReturn(PlFrame frame, AxonValue valor) {
    }

    /** Quando uma excecao e observada pela primeira vez num quadro. */
    default void onException(PlFrame frame, PlError erro) {
    }

    /** Antes de chamar uma funcao do host (por exemplo db.query). */
    default void onHostCall(PlFrame frame, String modulo, String nome, List<AxonValue> args,
                            Map<String, AxonValue> kwargs) {
    }
}
