package com.axonbase.jdbc;

import com.axonbase.common.Messages;
import com.axonbase.sdk.Axon;
import com.axonbase.value.AxonValue;

import java.sql.*;
import java.util.*;

/**
 * Statement JDBC do AxonBase. Traduz SQL para AxonQL e executa via SDK.
 */
public class AxonStatement implements Statement {

    protected final Axon axon;
    protected final String ns;
    protected final String db;
    protected final boolean translate;
    protected final AxonConnection connection;
    protected AxonValue currentResult;
    protected boolean closed;
    protected boolean hasResultSet;
    protected int maxRows;
    protected int queryTimeout;
    private final List<String> batch = new ArrayList<>();
    private SqlRecordIds.Shape shape;

    public AxonStatement(Axon axon, String ns, String db, AxonConnection connection) {
        this(axon, ns, db, connection, false);
    }

    public AxonStatement(Axon axon, String ns, String db, AxonConnection connection, boolean translate) {
        this.axon = axon;
        this.ns = ns;
        this.db = db;
        this.connection = connection;
        this.translate = translate;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        execute(sql);
        return getResultSet();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        run(sql, true);
        return currentResult != null && currentResult.isArray() ? currentResult.asArray().size() : 0;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        run(sql, false);
        return hasResultSet;
    }

    private void run(String sql, boolean countRows) throws SQLException {
        ensureOpen();
        currentResult = null;
        hasResultSet = false;
        if (sql == null) {
            throw new SQLException(Messages.get("jdbc_execute_failed", "null"));
        }
        try {
            String command = translate ? SqlTranslator.translateStatement(sql) : sql;
            shape = translate ? SqlRecordIds.shape(sql) : null;
            if (countRows) {
                command = returningDeletedRows(command);
            }
            if (axon == null || !axon.isConnected()) {
                throw new SQLException(Messages.get("jdbc_connection_closed"));
            }
            if (connection != null) {
                connection.beforeStatement(command);
            }
            currentResult = axon.query(command);
            if (connection != null) {
                connection.afterStatement(command);
            }
            hasResultSet = currentResult != null && currentResult.isArray();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(Messages.get("jdbc_execute_failed", sql), e);
        }
    }

    /** Um DELETE só devolve os registros com RETURN BEFORE, e é deles que sai a contagem do JDBC. */
    private static String returningDeletedRows(String command) {
        String body = command.strip();
        while (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).strip();
        }
        if (!AxonConnection.word(body, 0).equals("DELETE")) {
            return command;
        }
        String unquoted = body.replaceAll("'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"", "''");
        if (unquoted.contains(";") || unquoted.toUpperCase(Locale.ROOT).matches("(?s).*\\bRETURN\\b.*")) {
            return command;
        }
        // a quebra de linha encerra um eventual comentário no fim do comando
        return body + "\nRETURN BEFORE";
    }

    private void ensureOpen() throws SQLException {
        if (closed) throw new SQLException(Messages.get("jdbc_statement_closed"));
    }

    protected static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException(Messages.get("jdbc_feature_unsupported", feature));
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        if (currentResult == null) return null;
        AxonResultSetBackend backend = new AxonResultSetBackend(currentResult, shape);
        backend.limit(maxRows);
        return JdbcProxy.create(ResultSet.class, backend);
    }

    @Override
    public int getUpdateCount() throws SQLException {
        if (hasResultSet) return -1;
        return currentResult != null && currentResult.isArray() ? currentResult.asArray().size() : -1;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        hasResultSet = false;
        currentResult = null;
        return false;
    }

    @Override
    public int getMaxRows() throws SQLException { return maxRows; }
    @Override
    public void setMaxRows(int max) throws SQLException { this.maxRows = max; }

    @Override
    public int getQueryTimeout() throws SQLException { return queryTimeout; }
    @Override
    public void setQueryTimeout(int seconds) throws SQLException { this.queryTimeout = seconds; }

    @Override
    public void close() throws SQLException { closed = true; currentResult = null; }
    @Override
    public boolean isClosed() throws SQLException { return closed; }

    @Override
    public Connection getConnection() throws SQLException { return connection; }

    @Override public void setFetchDirection(int direction) throws SQLException {}
    @Override public int getFetchDirection() throws SQLException { return ResultSet.FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) throws SQLException {}
    @Override public int getFetchSize() throws SQLException { return 0; }
    @Override public int getResultSetConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }
    @Override public int getResultSetType() throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public int getResultSetHoldability() throws SQLException { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }

    @Override public void setCursorName(String name) throws SQLException {}
    @Override public boolean getMoreResults(int current) throws SQLException { return false; }
    @Override public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        refuseGeneratedKeys(autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS);
        return executeUpdate(sql);
    }
    @Override public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        refuseGeneratedKeys(columnIndexes != null && columnIndexes.length > 0);
        return executeUpdate(sql);
    }
    @Override public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        refuseGeneratedKeys(columnNames != null && columnNames.length > 0);
        return executeUpdate(sql);
    }
    @Override public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        refuseGeneratedKeys(autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS);
        return execute(sql);
    }
    @Override public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        refuseGeneratedKeys(columnIndexes != null && columnIndexes.length > 0);
        return execute(sql);
    }
    @Override public boolean execute(String sql, String[] columnNames) throws SQLException {
        refuseGeneratedKeys(columnNames != null && columnNames.length > 0);
        return execute(sql);
    }
    @Override public ResultSet getGeneratedKeys() throws SQLException { throw unsupported("generated keys"); }

    static void refuseGeneratedKeys(boolean requested) throws SQLException {
        if (requested) throw unsupported("generated keys");
    }
    @Override public void setPoolable(boolean poolable) throws SQLException {}
    @Override public boolean isPoolable() throws SQLException { return false; }
    @Override public void closeOnCompletion() throws SQLException {}
    @Override public boolean isCloseOnCompletion() throws SQLException { return false; }
    @Override public void cancel() throws SQLException { throw unsupported("Statement.cancel"); }
    @Override public void setEscapeProcessing(boolean enable) throws SQLException {}
    @Override public void setMaxFieldSize(int max) throws SQLException {}
    @Override public int getMaxFieldSize() throws SQLException { return 0; }
    @Override public void addBatch(String sql) throws SQLException { queue(sql); }

    protected void queue(String sql) throws SQLException {
        ensureOpen();
        if (sql == null || sql.isBlank()) throw new SQLException(Messages.get("jdbc_execute_failed", sql));
        batch.add(sql);
    }

    @Override public void clearBatch() throws SQLException {
        ensureOpen();
        batch.clear();
    }

    /** Executa na ordem e para na primeira falha; a exceção leva as contagens do que já foi aplicado. */
    @Override public int[] executeBatch() throws SQLException {
        ensureOpen();
        List<String> commands = List.copyOf(batch);
        batch.clear();
        int[] counts = new int[commands.size()];
        for (int i = 0; i < commands.size(); i++) {
            try {
                if (AxonConnection.word(commands.get(i), 0).equals("SELECT")) {
                    throw new SQLException(Messages.get("jdbc_batch_query_refused"));
                }
                run(commands.get(i), true);
                counts[i] = currentResult != null && currentResult.isArray()
                    ? currentResult.asArray().size() : Statement.SUCCESS_NO_INFO;
            } catch (SQLException e) {
                throw new BatchUpdateException(Messages.get("jdbc_batch_failed", i + 1, e.getMessage()),
                    e.getSQLState(), e.getErrorCode(), Arrays.copyOf(counts, i), e);
            }
        }
        currentResult = null;
        hasResultSet = false;
        return counts;
    }
    @Override public SQLWarning getWarnings() throws SQLException { return null; }
    @Override public void clearWarnings() throws SQLException {}
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return iface.cast(this); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this); }
}
