package com.axonbase.core.engine;

import com.axonbase.parser.ast.Statement;

/**
 * Marca da sessão que desfaz um passo de SAGA: nela só roda a sentença indicada, montada pelo motor, e a escrita na
 * tabela do passo repõe o estado anterior sem passar por PERMISSIONS, Data Rules nem pelas regras de valor novo das
 * colunas. Leituras continuam restritas, e eventos e consultas ao vivo disparados pela escrita não herdam a marca. Só
 * o motor a constrói, então nenhum pedido de cliente a liga.
 */
public final class SagaCompensation {

    private final Statement statement;
    private final String table;

    SagaCompensation(Statement statement, String table) {
        this.statement = statement;
        this.table = table;
    }

    boolean permits(Statement candidate) {
        return candidate == statement;
    }

    boolean restores(String written) {
        return table.equals(written);
    }
}
