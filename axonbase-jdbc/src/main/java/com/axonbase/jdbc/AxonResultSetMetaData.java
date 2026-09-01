package com.axonbase.jdbc;

import java.sql.*;
import java.util.List;

public class AxonResultSetMetaData implements ResultSetMetaData {

    private final List<String> columns;

    public AxonResultSetMetaData(List<String> columns) {
        this.columns = columns;
    }

    @Override public int getColumnCount() { return columns.size(); }
    @Override public String getColumnName(int column) { return columns.get(column - 1); }
    @Override public String getColumnLabel(int column) { return columns.get(column - 1); }
    @Override public String getTableName(int column) { return ""; }
    @Override public String getSchemaName(int column) { return ""; }
    @Override public String getCatalogName(int column) { return ""; }
    @Override public int getColumnType(int column) { return Types.VARCHAR; }
    @Override public String getColumnTypeName(int column) { return "VARCHAR"; }
    @Override public int getColumnDisplaySize(int column) { return 255; }
    @Override public String getColumnClassName(int column) { return "java.lang.String"; }
    @Override public int getPrecision(int column) { return 0; }
    @Override public int getScale(int column) { return 0; }
    @Override public boolean isAutoIncrement(int column) { return false; }
    @Override public boolean isCaseSensitive(int column) { return true; }
    @Override public boolean isSearchable(int column) { return true; }
    @Override public boolean isCurrency(int column) { return false; }
    @Override public int isNullable(int column) { return columnNullable; }
    @Override public boolean isSigned(int column) { return false; }
    @Override public boolean isReadOnly(int column) { return true; }
    @Override public boolean isWritable(int column) { return false; }
    @Override public boolean isDefinitelyWritable(int column) { return false; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return iface.cast(this); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this); }
}
