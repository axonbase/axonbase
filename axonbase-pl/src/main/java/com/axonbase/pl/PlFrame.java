package com.axonbase.pl;

import com.axonbase.value.AxonValue;
import java.util.Map;

/** Quadro de execucao visto por depuradores. */
public interface PlFrame {

    /** Nome da funcao (ou nome do modulo para o corpo do programa). */
    String funcao();

    /** Nome do programa dono do codigo. */
    String programa();

    /** Linha do comando em execucao. */
    int linha();

    /** Variaveis locais (valores nao conversiveis aparecem como texto). */
    Map<String, AxonValue> locais();

    /** Quadro chamador, ou null no topo. */
    PlFrame chamador();

    /** Profundidade (0 no topo). */
    int profundidade();
}
