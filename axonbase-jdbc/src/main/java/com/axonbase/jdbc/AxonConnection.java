package com.axonbase.jdbc;

import com.axonbase.common.Messages;
import com.axonbase.sdk.Axon;
import com.axonbase.sdk.AxonSdkException;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Conexão JDBC do AxonBase. Mantém uma conexão WebSocket para o servidor.
 */
public class AxonConnection implements Connection {

    private final Axon axon;
    private final String ns;
    private final String db;
    private final boolean translate;
    private boolean closed;
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

        try {
            String ks = info.getProperty("keystore");
            String ksp = info.getProperty("keystorePassword");
            String ts = info.getProperty("truststore");
            String tsp = info.getProperty("truststorePassword");
            this.axon = Axon.connect(wsUrl, ks, ksp, ts, tsp);
            axon.use(this.ns, this.db);
            String user = info.getProperty("user");
            String pass = info.getProperty("password");
            if (user != null && !user.isEmpty() && pass != null && !pass.isEmpty()) {
                String token = axon.signin(user, pass);
                if (token != null) axon.authenticate(token);
            }
        } catch (AxonSdkException e) {
            throw new SQLException(Messages.get("jdbc_connection_failed", e.getMessage()), e);
        } catch (Exception e) {
            throw new SQLException(Messages.get("jdbc_connection_failed", e.getMessage()), e);
        }
    }

    public boolean isTranslateEnabled() { return translate; }

    @Override
    public Statement createStatement() throws SQLException {
        return new AxonStatement(axon, ns, db, translate);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return new AxonPreparedStatement(axon, ns, db, sql, translate);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException(Messages.get("jdbc_prepare_call_unsupported"));
    }

    @Override
    public String nativeSQL(String sql) throws SQLException { return sql; }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (autoCommit && inTransaction) {
            try { axon.query("COMMIT"); inTransaction = false; } catch (Exception e) { throw new SQLException(e); }
        } else if (!autoCommit && !inTransaction) {
            try { axon.query("BEGIN"); inTransaction = true; } catch (Exception e) { throw new SQLException(e); }
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException { return !inTransaction; }

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
    public void setCatalog(String catalog) throws SQLException {}
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
        try { axon.query("SAVEPOINT " + (name != null ? name : "sp")); } catch (Exception e) { throw new SQLException(e); }
        return new AxonSavepoint(name);
    }
    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        try { axon.query("ROLLBACK TO " + savepoint.getSavepointName()); } catch (Exception e) { throw new SQLException(e); }
    }
    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        try { axon.query("RELEASE " + savepoint.getSavepointName()); } catch (Exception e) { throw new SQLException(e); }
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
        return prepareStatement(sql);
    }
    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }
    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
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
    @Override    public void setSchema(String schema) throws SQLException {}
    @Override    public String getSchema() throws SQLException { return ns; }
    @Override    public void abort(Executor executor) throws SQLException { close(); }
    @Override    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {}
    @Override    public int getNetworkTimeout() throws SQLException { return 0; }
    @Override    public <T> T unwrap(Class<T> iface) throws SQLException { return iface.cast(this); }
    @Override    public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this); }

    private static boolean translate(Properties info) {
        return "true".equalsIgnoreCase(info.getProperty("sql.translate"));
    }

    private record AxonSavepoint(String name) implements Savepoint {
        @Override public int getSavepointId() throws SQLException { return 0; }
        @Override public String getSavepointName() throws SQLException { return name != null ? name : "sp"; }
    }
}
