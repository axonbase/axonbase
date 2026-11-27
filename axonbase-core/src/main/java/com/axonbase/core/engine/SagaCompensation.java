package com.axonbase.core.engine;

import com.axonbase.parser.ast.Statement;

/**
 * Marca da sessão que desfaz um passo de SAGA: nela só roda a sentença indicada, montada pelo motor. Só o motor
 * constrói a marca, então nenhum pedido de cliente a liga.
 */
public final class SagaCompensation {

    private final Statement statement;

    SagaCompensation(Statement statement) {
        this.statement = statement;
    }

    boolean permits(Statement candidate) {
        return candidate == statement;
    }
}
