package com.axonbase.pl;

import java.util.List;
import java.util.Set;
import com.axonbase.value.AxonValue;

/**
 * Ponte entre o programa e o ambiente. Nunca expoe classes Java ou reflexao:
 * so PlCallable e AxonValue cruzam a fronteira.
 */
public interface PlHost {

    /** Resolve um import que nao e embutido. Devolve o programa do modulo ou null se nao existir. */
    default PlProgram resolverImport(String nome) {
        return null;
    }

    /** Nomes de modulos nativos do host, visiveis sem import (por exemplo db). */
    default Set<String> modulosNativos() {
        return Set.of();
    }

    /**
     * Atributo de um modulo nativo: um PlCallable, um AxonValue, ou null se nao existir.
     */
    default Object attr(String modulo, String nome) {
        return null;
    }

    /** Recebe a saida de print. Nivel e INFO para print. */
    default void log(String nivel, String mensagem, List<AxonValue> args) {
    }
}
