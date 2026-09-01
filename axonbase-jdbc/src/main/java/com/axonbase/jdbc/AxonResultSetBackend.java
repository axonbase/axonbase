package com.axonbase.jdbc;

import com.axonbase.value.AxonValue;

import java.sql.*;
import java.util.*;

/**
 * Backend do ResultSet JDBC. Não implementa ResultSet diretamente;
 * é envolvido por {@link JdbcProxy} que trata os métodos não implementados.
 */
public class AxonResultSetBackend {
    private final List<Map<String, AxonValue>> rows = new ArrayList<>();
    private final List<String> columns = new ArrayList<>();
    private int rowIndex = -1;

    public AxonResultSetBackend(AxonValue value) {
        if (value == null) return;
        if (value.isArray()) {
            for (AxonValue item : value.asArray()) {
                if (item.isObject()) {
                    Map<String, AxonValue> row = new LinkedHashMap<>();
                    for (var entry : item.asObject().entrySet()) {
                        row.put(entry.getKey(), entry.getValue());
                        if (!columns.contains(entry.getKey())) columns.add(entry.getKey());
                    }
                    rows.add(row);
                } else {
                    // SELECT VALUE retorna arrays de escalares (string, datetime, etc)
                    Map<String, AxonValue> row = new LinkedHashMap<>();
                    row.put("value", item);
                    if (!columns.contains("value")) columns.add("value");
                    rows.add(row);
                }
            }
        } else if (value.isObject()) {
            Map<String, AxonValue> row = value.asObject();
            for (String key : row.keySet()) if (!columns.contains(key)) columns.add(key);
            rows.add(row);
        } else {
            // Caso extremo: um único valor escalar
            Map<String, AxonValue> row = new LinkedHashMap<>();
            row.put("value", value);
            if (!columns.contains("value")) columns.add("value");
            rows.add(row);
        }
    }

    public ResultSetMetaData getMetaData() throws SQLException {
        return JdbcProxy.create(ResultSetMetaData.class, new AxonResultSetMetaData(columns));
    }

    public boolean next() { rowIndex++; return rowIndex < rows.size(); }
    public void close() {}
    public boolean isClosed() { return false; }
    public boolean wasNull() { return false; }

    private Map<String, AxonValue> currentRow() { return rowIndex >= 0 && rowIndex < rows.size() ? rows.get(rowIndex) : Map.of(); }
    private AxonValue col(String label) {
        Map<String, AxonValue> row = currentRow(); AxonValue v = row.get(label);
        if (v == null && label.indexOf('.') > 0) v = row.get(label.substring(label.indexOf('.') + 1));
        return v;
    }
    private String colLabel(int i) { return i <= columns.size() ? columns.get(i - 1) : ""; }

    public String getString(int i) { return getString(colLabel(i)); }
    public String getString(String label) {
        AxonValue v = col(label);
        if (v == null) return null;
        if (v.isString()) return v.asString();
        if (v.isDatetime()) return v.asInstant().toString();
        return v.isRecordId() ? v.asRecordId().toString() : v.toString();
    }
    public int getInt(int i) { return (int) getLong(i); }
    public int getInt(String label) { return (int) getLong(label); }
    public long getLong(int i) { return getLong(colLabel(i)); }
    public long getLong(String label) { AxonValue v = col(label); return v != null && v.isNumber() ? v.asLong() : 0; }
    public double getDouble(int i) { return getDouble(colLabel(i)); }
    public double getDouble(String label) { AxonValue v = col(label); return v != null && v.isNumber() ? v.asDouble() : 0; }
    public boolean getBoolean(int i) { return getBoolean(colLabel(i)); }
    public boolean getBoolean(String label) { AxonValue v = col(label); return v != null && v.isBool() && v.asBool(); }
    public Object getObject(int i) { return getObject(colLabel(i)); }
    public Object getObject(String label) {
        AxonValue v = col(label); if (v == null) return null;
        return switch (v.type()) {
            case STRING -> v.asString(); case NUMBER -> v.isInteger() ? v.asLong() : v.asDouble();
            case BOOL -> v.asBool(); case NULL, NONE -> null;
            case RECORD_ID -> v.asRecordId().toString(); case ARRAY, SET -> v.asArray();
            case OBJECT -> v.asObject(); case DATETIME -> java.util.Date.from(v.asInstant());
            default -> v.toString();
        };
    }
    public int findColumn(String label) { int idx = columns.indexOf(label); return idx >= 0 ? idx + 1 : 1; }
    public int getRow() { return rowIndex + 1; }

    public boolean first() { rowIndex = 0; return rowIndex < rows.size(); }
    public boolean last() { rowIndex = rows.size() - 1; return rowIndex >= 0; }
    public boolean absolute(int row) { rowIndex = row - 1; return rowIndex >= 0 && rowIndex < rows.size(); }
    public boolean relative(int rows) { return absolute(rowIndex + 1 + rows); }
    public boolean previous() { rowIndex--; return rowIndex >= 0; }
    public void beforeFirst() { rowIndex = -1; }
    public void afterLast() { rowIndex = rows.size(); }
    public boolean isBeforeFirst() { return rowIndex < 0 && !rows.isEmpty(); }
    public boolean isAfterLast() { return rowIndex >= rows.size(); }
    public boolean isFirst() { return rowIndex == 0; }
    public boolean isLast() { return rowIndex == rows.size() - 1; }
}