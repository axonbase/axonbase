package com.axonbase.jdbc;

import com.axonbase.common.Messages;
import com.axonbase.sdk.Axon;
import com.axonbase.sdk.AxonQlText;
import com.axonbase.sdk.AxonSdkException;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Conexão JDBC do AxonBase. Mantém uma conexão WebSocket para o servidor.
 */
public class AxonConnection implements Connection, SagaScope {

    private final Axon axon;
    private final String ns;
    private final String db;
    private final boolean translate;
    private boolean closed;
    private boolean autoCommit = true;
    private boolean inTransaction;

    public AxonConnection(String url, Properties info) throws SQLException {
        String rest = url.substring("jdbc:axonbase:".length());
        String wsUrl;
        boolean translate = false;
        int q = rest.indexOf('?');
        if (q >= 0) {
            wsUrl = rest.substring(0, q);
            String query = rest.substring(q + 1);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    switch (kv[0]) {
                        case "ns" -> info.setProperty("ns", kv[1]);
                        case "db" -> info.setProperty("db", kv[1]);
                        case "user" -> info.setProperty("user", kv[1]);
                        case "password" -> info.setProperty("password", kv[1]);
                        case "keystore" -> info.setProperty("keystore", kv[1]);
                        case "keystorePassword" -> info.setProperty("keystorePassword", kv[1]);
                        case "truststore" -> info.setProperty("truststore", kv[1]);
                        case "truststorePassword" -> info.setProperty("truststorePassword", kv[1]);
                        case "sql.translate" -> translate = "true".equalsIgnoreCase(kv[1]);
                    }
                }
            }
        } else {
            wsUrl = rest;
        }

        this.translate = translate || translate(info);
        this.ns = info.getProperty("ns", "axonbase");
        this.db = info.getProperty("db", "main");

        Axon axon = null;
        try {
            String ks = info.getProperty("keystore");
            String ksp = info.getProperty("keystorePassword");
            String ts = info.getProperty("truststore");
            String tsp = info.getProperty("truststorePassword");
            axon = Axon.connect(wsUrl, ks, ksp, ts, tsp);
            axon.use(this.ns, this.db);
            String user = info.getProperty("user");
            String pass = info.getProperty("password");
            if (user != null && !user.isEmpty() && pass != null && !pass.isEmpty()) {
                String token = axon.signin(user, pass);
                if (token != null) axon.authenticate(token);
            }
            this.axon = axon;
        } catch (AxonSdkException e) {
            if (axon != null) axon.close();
            throw new SQLException(Messages.get("jdbc_connection_failed", e.getMessage()), e);
        } catch (Exception e) {
            if (axon != null) axon.close();
            throw new SQLException(Messages.get("jdbc_connection_failed", e.getMessage()), e);
        }
    }

    @Override
    public SagaBinding joinSaga(String sagaName, String correlationId) throws SQLException {
        if (closed) throw new SQLException(Messages.get("jdbc_connection_closed"));
        try {
            axon.query("JOIN SAGA " + AxonQlText.sagaName(sagaName)
                + " WITH CORRELATION " + AxonQlText.literal(correlationId));
            return new SagaBinding() {
                @Override
                public void close() throws SQLException {
                    if (!closed) leaveSaga();
                }
            };
        } catch (Exception e) {
            throw new SQLException(Messages.get("jdbc_join_saga_failed", sagaName, correlationId), e);
        }
    }

    @Override
    public void leaveSaga() throws SQLException {
        if (closed) throw new SQLException(Messages.get("jdbc_connection_closed"));
        try {
            axon.query("LEAVE SAGA");
        } catch (Exception e) {
            throw new SQLException(Messages.get("jdbc_leave_saga_failed"), e);
        }
    }

    public boolean isTranslateEnabled() { return translate; }

    @Override
    public Statement createStatement() throws SQLException {
        return new AxonStatement(axon, ns, db, this, translate);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return new AxonPreparedStatement(axon, ns, db, this, sql, translate);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException(Messages.get("jdbc_prepare_call_unsupported"));
    }

    @Override
    public String nativeSQL(String sql) throws SQLException { return sql; }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (autoCommit) {
            commit();
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() throws SQLException { return autoCommit; }

    @Override
    public void commit() throws SQLException {
        if (!inTransaction) return;
        try { axon.query("COMMIT"); inTransaction = false; } catch (Exception e) { throw new SQLException(e); }
    }

    @Override
    public void rollback() throws SQLException {
        if (!inTransaction) return;
        try { axon.query("CANCEL"); inTransaction = false; } catch (Exception e) { throw new SQLException(e); }
    }

    /**
     * Com o auto-commit desligado, todo comando roda dentro de uma transação: a seguinte a um commit ou rollback
     * começa no primeiro comando.
     */
    void beforeStatement(String command) throws SQLException {
        if (autoCommit || inTransaction) return;
        switch (word(command, 0)) {
            case "BEGIN", "COMMIT", "CANCEL", "ROLLBACK", "JOIN", "LEAVE", "USE" -> { }
            default -> {
                try { axon.query("BEGIN"); inTransaction = true; } catch (Exception e) { throw new SQLException(e); }
            }
        }
    }

    /** Acompanha a transação aberta ou encerrada pelo próprio texto do comando. */
    void afterStatement(String command) {
        String first = word(command, 0);
        String second = word(command, 1);
        if (second.equals("SAGA") || second.equals("TO")) return;
        if (first.equals("BEGIN")) inTransaction = true;
        if (first.equals("COMMIT") || first.equals("CANCEL") || first.equals("ROLLBACK")) inTransaction = false;
    }

    /** A palavra de posição {@code index} do comando, em maiúsculas, ou vazio. */
    static String word(String command, int index) {
        String[] words = command.strip().split("[\\s;]+", index + 2);
        return words.length > index ? words[index].toUpperCase(Locale.ROOT) : "";
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            if (inTransaction) {
                try { axon.query("CANCEL"); } catch (Exception ignored) {}
            }
            axon.close();
            closed = true;
        }
    }

    @Override
    public boolean isClosed() throws SQLException { return closed; }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return new AxonDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {}

    @Override
    public boolean isReadOnly() throws SQLException { return false; }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        // ignorar a troca faria os comandos seguintes gravarem no banco anterior
        if (catalog != null && !catalog.equals(db)) {
            throw AxonStatement.unsupported("setCatalog " + catalog);
        }
    }
    @Override
    public String getCatalog() throws SQLException { return db; }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {}
    @Override
    public int getTransactionIsolation() throws SQLException { return Connection.TRANSACTION_READ_COMMITTED; }

    @Override
    public SQLWarning getWarnings() throws SQLException { return null; }
    @Override
    public void clearWarnings() throws SQLException {}

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement();
    }
    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql);
    }
    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareCall(sql);
    }
    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException { return Map.of(); }
    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {}

    @Override
    public void setHoldability(int holdability) throws SQLException {}
    @Override
    public int getHoldability() throws SQLException { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }

    @Override
    public Savepoint setSavepoint() throws SQLException { return setSavepoint(null); }
    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        String sp = name != null ? sanitize(name) : "sp";
        beforeStatement("SAVEPOINT");
        try { axon.query("SAVEPOINT " + sp); } catch (Exception e) { throw new SQLException(e); }
        return new AxonSavepoint(sp);
    }
    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        String sp = savepoint != null ? sanitize(savepoint.getSavepointName()) : "sp";
        try { axon.query("ROLLBACK TO " + sp); } catch (Exception e) { throw new SQLException(e); }
    }
    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        String sp = savepoint != null ? sanitize(savepoint.getSavepointName()) : "sp";
        try { axon.query("RELEASE " + sp); } catch (Exception e) { throw new SQLException(e); }
    }

    private static String sanitize(String name) throws SQLException {
        if (name == null || name.isBlank()) return "sp";
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new SQLException(Messages.get("jdbc_invalid_identifier", name));
        }
        return name;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return createStatement();
    }
    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareStatement(sql);
    }
    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareCall(sql);
    }
    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        AxonStatement.refuseGeneratedKeys(autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS);
        return prepareStatement(sql);
    }
    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        AxonStatement.refuseGeneratedKeys(columnIndexes != null && columnIndexes.length > 0);
        return prepareStatement(sql);
    }
    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        AxonStatement.refuseGeneratedKeys(columnNames != null && columnNames.length > 0);
        return prepareStatement(sql);
    }

    @Override
    public Clob createClob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override
    public Blob createBlob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override    public NClob createNClob() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override    public SQLXML createSQLXML() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override    public boolean isValid(int timeout) throws SQLException { return !closed && axon != null && axon.isConnected(); }
    @Override    public void setClientInfo(String name, String value) throws SQLClientInfoException {}
    @Override    public void setClientInfo(Properties properties) throws SQLClientInfoException {}
    @Override    public String getClientInfo(String name) throws SQLException { return null; }
    @Override    public Properties getClientInfo() throws SQLException { return new Properties(); }
    @Override    public Array createArrayOf(String typeName, Object[] elements) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override    public Struct createStruct(String typeName, Object[] attributes) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override    public void setSchema(String schema) throws SQLException {
        if (schema != null && !schema.equals(ns)) {
            throw AxonStatement.unsupported("setSchema " + schema);
        }
    }
    @Override    public String getSchema() throws SQLException { return ns; }
    @Override    public void abort(Executor executor) throws SQLException { close(); }
    @Override    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {}
    @Override    public int getNetworkTimeout() throws SQLException { return 0; }
    @Override    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException(Messages.get("jdbc_unwrap_unsupported", iface.getName()));
    }
    @Override    public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this); }

    private static boolean translate(Properties info) {
        return "true".equalsIgnoreCase(info.getProperty("sql.translate"));
    }

    private record AxonSavepoint(String name) implements Savepoint {
        @Override public int getSavepointId() throws SQLException { return 0; }
        @Override public String getSavepointName() throws SQLException { return name != null ? name : "sp"; }
    }
}
