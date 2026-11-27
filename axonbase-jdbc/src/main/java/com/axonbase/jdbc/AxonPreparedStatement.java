package com.axonbase.jdbc;

import com.axonbase.common.Messages;
import com.axonbase.sdk.Axon;
import java.sql.*;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.util.*;

public class AxonPreparedStatement extends AxonStatement implements PreparedStatement {
    private final String sqlTemplate;
    private final List<Object> params = new ArrayList<>();

    public AxonPreparedStatement(Axon axon, String ns, String db, AxonConnection connection, String sql) {
        super(axon, ns, db, connection, false);
        this.sqlTemplate = sql;
    }

    public AxonPreparedStatement(Axon axon, String ns, String db, AxonConnection connection, String sql, boolean translate) {
        super(axon, ns, db, connection, translate);
        this.sqlTemplate = sql;
    }

    private String buildSql() throws SQLException {
        StringBuilder sb = new StringBuilder();
        int p = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < sqlTemplate.length(); i++) {
            char c = sqlTemplate.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < sqlTemplate.length()) {
                    sb.append(sqlTemplate.charAt(++i));
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inString = true;
                quote = c;
                sb.append(c);
                continue;
            }
            if (c == '?') {
                if (p >= params.size()) {
                    throw new SQLException(Messages.get("jdbc_param_not_set", p + 1));
                }
                Object val = params.get(p++);
                if (val == null) sb.append("null");
                else if (val instanceof Number || val instanceof Boolean) sb.append(val);
                else sb.append(literal(val.toString()));
            } else {
                sb.append(c);
            }
        }
        // a tradução acontece uma vez só, na execução
        return sb.toString();
    }

    @Override
    public boolean execute() throws SQLException { return super.execute(buildSql()); }
    @Override
    public ResultSet executeQuery() throws SQLException { execute(); return getResultSet(); }
    @Override
    public int executeUpdate() throws SQLException { return super.executeUpdate(buildSql()); }

    /** Literal de texto do AxonQL, em que a barra invertida é o caractere de escape (dobrar a aspa não vale). */
    static String literal(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void setParam(int index, Object value) throws SQLException {
        if (index < 1) throw new IllegalArgumentException("parameter index must be >= 1, got " + index);
        // sem conversão própria, o texto gravado seria o nome do objeto Java, e não o conteúdo
        if (value != null && (value.getClass().isArray() || value instanceof InputStream || value instanceof Reader
            || value instanceof Blob || value instanceof Clob || value instanceof Array || value instanceof Ref
            || value instanceof SQLXML || value instanceof RowId)) {
            throw unsupported("parameter type " + value.getClass().getSimpleName());
        }
        while (params.size() < index) params.add(null);
        params.set(index - 1, value);
    }

    @Override public void setNull(int index, int sqlType) throws SQLException { setParam(index, null); }
    @Override public void setNull(int index, int sqlType, String typeName) throws SQLException { setParam(index, null); }
    @Override public void setBoolean(int index, boolean x) throws SQLException { setParam(index, x); }
    @Override public void setByte(int index, byte x) throws SQLException { setParam(index, (int)x); }
    @Override public void setShort(int index, short x) throws SQLException { setParam(index, (int)x); }
    @Override public void setInt(int index, int x) throws SQLException { setParam(index, x); }
    @Override public void setLong(int index, long x) throws SQLException { setParam(index, x); }
    @Override public void setFloat(int index, float x) throws SQLException { setParam(index, (double)x); }
    @Override public void setDouble(int index, double x) throws SQLException { setParam(index, x); }
    @Override public void setString(int index, String x) throws SQLException { setParam(index, x); }
    @Override public void setBytes(int index, byte[] x) throws SQLException { setParam(index, x); }
    @Override public void setDate(int index, java.sql.Date x) throws SQLException { setParam(index, x); }
    @Override public void setTime(int index, java.sql.Time x) throws SQLException { setParam(index, x); }
    @Override public void setTime(int index, java.sql.Time x, java.util.Calendar cal) throws SQLException { setParam(index, x); }
    @Override public void setTimestamp(int index, java.sql.Timestamp x) throws SQLException { setParam(index, x); }
    @Override public void setTimestamp(int index, java.sql.Timestamp x, java.util.Calendar cal) throws SQLException { setParam(index, x); }
    @Override public void setBigDecimal(int index, BigDecimal x) throws SQLException { setParam(index, x); }
    @Override public void setArray(int index, Array x) throws SQLException { setParam(index, x); }
    @Override public void setRef(int index, Ref x) throws SQLException { setParam(index, x); }
    @Override public void setURL(int index, URL x) throws SQLException { setParam(index, x); }
    @Override public void setObject(int index, Object x) throws SQLException { setParam(index, x); }
    @Override public void setObject(int index, Object x, int targetSqlType) throws SQLException { setParam(index, x); }
    @Override public void setObject(int index, Object x, int targetSqlType, int scale) throws SQLException { setParam(index, x); }
    @Override public void setRowId(int index, RowId x) throws SQLException { setParam(index, x); }
    @Override public void setSQLXML(int index, SQLXML x) throws SQLException { setParam(index, x); }
    @Override public void setAsciiStream(int index, InputStream x) throws SQLException { setParam(index, x); }
    @Override public void setAsciiStream(int index, InputStream x, int length) throws SQLException { setParam(index, x); }
    @Override public void setAsciiStream(int index, InputStream x, long length) throws SQLException { setParam(index, x); }
    @Override public void setBinaryStream(int index, InputStream x) throws SQLException { setParam(index, x); }
    @Override public void setBinaryStream(int index, InputStream x, int length) throws SQLException { setParam(index, x); }
    @Override public void setBinaryStream(int index, InputStream x, long length) throws SQLException { setParam(index, x); }
    @Override public void setCharacterStream(int index, Reader x) throws SQLException { setParam(index, x); }
    @Override public void setCharacterStream(int index, Reader x, int length) throws SQLException { setParam(index, x); }
    @Override public void setCharacterStream(int index, Reader x, long length) throws SQLException { setParam(index, x); }
    @Override public void setNCharacterStream(int index, Reader x) throws SQLException { setParam(index, x); }
    @Override public void setNCharacterStream(int index, Reader x, long length) throws SQLException { setParam(index, x); }
    @Override public void setNString(int index, String x) throws SQLException { setParam(index, x); }
    @Override public void setNClob(int index, NClob x) throws SQLException { setParam(index, x); }
    @Override public void setNClob(int index, Reader x) throws SQLException { setParam(index, x); }
    @Override public void setNClob(int index, Reader x, long length) throws SQLException { setParam(index, x); }
    @Override public void setClob(int index, Clob x) throws SQLException { setParam(index, x); }
    @Override public void setClob(int index, Reader x) throws SQLException { setParam(index, x); }
    @Override public void setClob(int index, Reader x, long length) throws SQLException { setParam(index, x); }
    @Override public void setBlob(int index, Blob x) throws SQLException { setParam(index, x); }
    @Override public void setBlob(int index, InputStream x) throws SQLException { setParam(index, x); }
    @Override public void setBlob(int index, InputStream x, long length) throws SQLException { setParam(index, x); }
    @Override public void setUnicodeStream(int index, InputStream x, int length) throws SQLException { setParam(index, x); }
    @Override public void clearParameters() { params.clear(); }
    @Override public ResultSetMetaData getMetaData() { return null; }
    @Override public ParameterMetaData getParameterMetaData() { return null; }
    @Override public void addBatch() throws SQLException { queue(buildSql()); }
    @Override public void addBatch(String sql) throws SQLException {
        throw new SQLException(Messages.get("jdbc_batch_sql_on_prepared"));
    }
    @Override public void setDate(int index, java.sql.Date x, java.util.Calendar cal) throws SQLException { setParam(index, x); }
}