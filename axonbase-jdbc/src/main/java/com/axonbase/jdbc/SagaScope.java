package com.axonbase.jdbc;

import java.sql.SQLException;

/**
 * Escopo de transação SAGA em uma conexão JDBC.
 * <p>
 * {@code joinSaga} vincula a sessão a uma saga ativa usando {@code JOIN SAGA}
 * no servidor. O escopo é desvinculado com {@code leaveSaga} ou ao fechar
 * o recurso retornado.
 */
public interface SagaScope {

    /**
     * Vincula esta conexão a uma saga ativa.
     *
     * @param sagaName      nome do recurso de saga definido no servidor
     * @param correlationId identificador de correlação único da transação
     * @return um escopo que desvincula no fechamento
     * @throws SQLException se a saga não estiver ativa ou a conexão estiver fechada
     */
    SagaBinding joinSaga(String sagaName, String correlationId) throws SQLException;

    /**
     * Desvincula esta conexão da saga ativa.
     * @throws SQLException se não houver saga ativa
     */
    void leaveSaga() throws SQLException;
}