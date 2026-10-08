package com.axonbase.jdbc;

import java.sql.SQLException;

/**
 * Escopo de saga ativo que desvincula a conexão no fechamento.
 */
public interface SagaBinding extends AutoCloseable {
    @Override
    void close() throws SQLException;
}