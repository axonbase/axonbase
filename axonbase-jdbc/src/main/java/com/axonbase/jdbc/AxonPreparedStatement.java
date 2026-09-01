package com.axonbase.jdbc;

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

    public AxonPreparedStatement(Axon axon, String ns, String db, String sql) {
        super(axon, ns, db, false);
        this.sqlTemplate = sql;
    }

    public AxonPreparedStatement(Axon axon, String ns, String db, String sql, boolean translate) {
        super(axon, ns, db, translate);
        this.sqlTemplate = sql;
    }

    private String buildSql() {
        StringBuilder sb = new StringBuilder();
        int p = 0;
        for (int i = 0; i < sqlTemplate.length(); i++) {
            char c = sqlTemplate.charAt(i);
            if (c == '?' && (i == 0 || sqlTemplate.charAt(i - 1) != '\'')) {
                Object val = p < params.size() ? params.get(p++) : null;
                if (val == null) sb.append("null");
                else if (val instanceof Number || val instanceof Boolean) sb.append(val);
                else sb.append('\'').append(val.toString().replace("'", "''")).append('\'');
            } else {
                sb.append(c);
            }
        }
        String built = sb.toString();
        return translate ? SqlTranslator.translate(built) : built;
    }

    @Override
    public boolean execute() throws SQLException { return super.execute(buildSql()); }
    @Override
    public ResultSet executeQuery() throws SQLException { execute(); return getResultSet(); }
    @Override
    public int executeUpdate() throws SQLException { return super.executeUpdate(buildSql()); }

    private void setParam(int index, Object value) {
        while (params.size() < index) params.add(null);
        params.set(index - 1, value);
    }

    @Override public void setNull(int index, int sqlType) { setParam(index, null); }
    @Override public void setNull(int index, int sqlType, String typeName) { setParam(index, null); }
    @Override public void setBoolean(int index, boolean x) { setParam(index, x); }
    @Override public void setByte(int index, byte x) { setParam(index, (int)x); }
    @Override public void setShort(int index, short x) { setParam(index, (int)x); }
    @Override public void setInt(int index, int x) { setParam(index, x); }
    @Override public void setLong(int index, long x) { setParam(index, x); }
    @Override public void setFloat(int index, float x) { setParam(index, (double)x); }
    @Override public void setDouble(int index, double x) { setParam(index, x); }
    @Override public void setString(int index, String x) { setParam(index, x); }
    @Override public void setBytes(int index, byte[] x) { setParam(index, x); }
    @Override public void setDate(int index, java.sql.Date x) { setParam(index, x); }
    @Override public void setTime(int index, java.sql.Time x) { setParam(index, x); }
    @Override public void setTime(int index, java.sql.Time x, java.util.Calendar cal) { setParam(index, x); }
    @Override public void setTimestamp(int index, java.sql.Timestamp x) { setParam(index, x); }
    @Override public void setTimestamp(int index, java.sql.Timestamp x, java.util.Calendar cal) { setParam(index, x); }
    @Override public void setBigDecimal(int index, BigDecimal x) { setParam(index, x); }
    @Override public void setArray(int index, Array x) { setParam(index, x); }
    @Override public void setRef(int index, Ref x) { setParam(index, x); }
    @Override public void setURL(int index, URL x) { setParam(index, x); }
    @Override public void setObject(int index, Object x) { setParam(index, x); }
    @Override public void setObject(int index, Object x, int targetSqlType) { setParam(index, x); }
    @Override public void setObject(int index, Object x, int targetSqlType, int scale) { setParam(index, x); }
    @Override public void setRowId(int index, RowId x) { setParam(index, x); }
    @Override public void setSQLXML(int index, SQLXML x) { setParam(index, x); }
    @Override public void setAsciiStream(int index, InputStream x) { setParam(index, x); }
    @Override public void setAsciiStream(int index, InputStream x, int length) { setParam(index, x); }
    @Override public void setAsciiStream(int index, InputStream x, long length) { setParam(index, x); }
    @Override public void setBinaryStream(int index, InputStream x) { setParam(index, x); }
    @Override public void setBinaryStream(int index, InputStream x, int length) { setParam(index, x); }
    @Override public void setBinaryStream(int index, InputStream x, long length) { setParam(index, x); }
    @Override public void setCharacterStream(int index, Reader x) { setParam(index, x); }
    @Override public void setCharacterStream(int index, Reader x, int length) { setParam(index, x); }
    @Override public void setCharacterStream(int index, Reader x, long length) { setParam(index, x); }
    @Override public void setNCharacterStream(int index, Reader x) { setParam(index, x); }
    @Override public void setNCharacterStream(int index, Reader x, long length) { setParam(index, x); }
    @Override public void setNString(int index, String x) { setParam(index, x); }
    @Override public void setNClob(int index, NClob x) { setParam(index, x); }
    @Override public void setNClob(int index, Reader x) { setParam(index, x); }
    @Override public void setNClob(int index, Reader x, long length) { setParam(index, x); }
    @Override public void setClob(int index, Clob x) { setParam(index, x); }
    @Override public void setClob(int index, Reader x) { setParam(index, x); }
    @Override public void setClob(int index, Reader x, long length) { setParam(index, x); }
    @Override public void setBlob(int index, Blob x) { setParam(index, x); }
    @Override public void setBlob(int index, InputStream x) { setParam(index, x); }
    @Override public void setBlob(int index, InputStream x, long length) { setParam(index, x); }
    @Override public void setUnicodeStream(int index, InputStream x, int length) { setParam(index, x); }
    @Override public void clearParameters() { params.clear(); }
    @Override public ResultSetMetaData getMetaData() { return null; }
    @Override public ParameterMetaData getParameterMetaData() { return null; }
    @Override public boolean execute(String sql, String[] columnNames) { return false; }
    @Override public void addBatch() {}
    @Override public void addBatch(String sql) {}
    @Override public int[] executeBatch() { return new int[0]; }
    @Override public void clearBatch() {}
    @Override public void setDate(int index, java.sql.Date x, java.util.Calendar cal) { setParam(index, x); }
}